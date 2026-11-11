package com.manhuatuji.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object ImageApiSpec {
    const val DEFAULT_BASE_URL = "https://direct-api.cangyuansuanli.cn/v1"
    const val DEFAULT_MODEL = "gpt-image-2.5"
    const val DEFAULT_SIZE = "1024x1365"
    const val DEFAULT_INTERFACE = "OpenAI Images API"
    const val MAX_RETRIES = 2
    val sizeChoices = listOf(
        "1024x1365" to "3:4 竖图",
        "1024x1792" to "9:16 竖屏",
        "1024x1024" to "1:1 正方形",
        "1792x1024" to "16:9 横屏",
    )

    fun isCangyuanHost(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return host in setOf("ai.cangyuansuanli.cn", "www.ai.cangyuansuanli.cn", "direct-api.cangyuansuanli.cn")
    }

    /** 基址已以 /v1 结尾时不再加一层。 */
    fun generationsEndpoint(baseUrl: String): String {
        val url = baseUrl.trim().trimEnd('/')
        require(url.startsWith("https://")) { "生图地址必须使用 HTTPS" }
        return when {
            url.endsWith("/images/generations") -> url
            url.endsWith("/v1") -> "$url/images/generations"
            else -> "$url/v1/images/generations"
        }
    }

    /**
     * 蓬源上少数旧模型只接受他们自己的像素尺寸。gpt-image-2.5 和 1024x1365 原样发送。
     */
    fun requestSize(baseUrl: String, model: String, imageSize: String): String {
        val size = imageSize.ifBlank { DEFAULT_SIZE }
        if (!isCangyuanHost(baseUrl)) return size
        if (model.trim().lowercase() !in setOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            return size
        }
        return when (size.trim()) {
            "16:9", "1792x1024" -> "1536x864"
            "9:16", "1024x1792" -> "864x1536"
            "1:1", "1024x1024" -> "1024x1024"
            else -> size
        }
    }

    fun aspect(size: String): Double? = when (size.trim()) {
        "16:9", "1792x1024", "1536x864" -> 16.0 / 9.0
        "9:16", "1024x1792", "864x1536" -> 9.0 / 16.0
        "1:1", "1024x1024" -> 1.0
        "3:4", "1024x1365" -> 3.0 / 4.0
        else -> null
    }

    fun ratioInstruction(size: String): String = when (aspect(size)) {
        16.0 / 9.0 -> "画幅硬性要求：最终图片必须是横屏 16:9，宽度明显大于高度，不要生成竖屏构图。"
        9.0 / 16.0 -> "画幅硬性要求：最终图片必须是竖屏 9:16，高度明显大于宽度。"
        1.0 -> "画幅硬性要求：最终图片必须是 1:1 正方形。"
        3.0 / 4.0 -> "画幅硬性要求：最终图片必须是竖图 3:4，高度大于宽度。"
        else -> ""
    }

    fun requestBody(
        model: String,
        prompt: String,
        size: String,
        async: Boolean,
        referencePngBase64: String? = null,
    ): JSONObject {
        require(model.isNotBlank()) { "请设置生图模型 ID" }
        require(prompt.isNotBlank()) { "生图提示词不能为空" }
        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size.ifBlank { DEFAULT_SIZE })
            .put("n", 1)
        if (async) {
            body.put("response_format", "url").put("async", true)
        } else {
            body.put("response_format", "b64_json")
                .put("stream", false)
                .put("watermark", false)
                .put("sequential_image_generation", "disabled")
        }
        if (!referencePngBase64.isNullOrBlank()) {
            body.put("image", JSONArray().put("data:image/png;base64,$referencePngBase64"))
        }
        return body
    }
}

sealed class RetryDecision {
    data object RetrySame : RetryDecision()
    data object Rewrite : RetryDecision()
    data object Stop : RetryDecision()
}

object ImageRetry {
    fun isModeration(message: String): Boolean {
        val normalized = message.lowercase()
        return listOf("moderation", "content_policy", "content policy", "safety", "审核", "违规", "敏感")
            .any { normalized.contains(it) }
    }

    fun isTransport(message: String): Boolean {
        val normalized = message.lowercase()
        return listOf(
            "timeout", "timed out", "connection", "连接", "重置", "unexpected end",
            "http 408", "http 429", "http 500", "http 502", "http 503", "http 504",
        ).any { normalized.contains(it) }
    }

    fun isUnsupportedReference(message: String): Boolean {
        val normalized = message.lowercase()
        val mentionsImage = normalized.contains("image") || normalized.contains("reference") || normalized.contains("参考")
        val rejects = normalized.contains("unknown") || normalized.contains("unexpected") ||
            normalized.contains("unrecognized") || normalized.contains("不支持") ||
            normalized.contains("invalid parameter") || normalized.contains("extra")
        return mentionsImage && rejects
    }

    /** [retriesAlreadyUsed] 是已经发生过的重试次数，不含第一次请求。最多再试 [ImageApiSpec.MAX_RETRIES] 次。 */
    fun decide(message: String, retriesAlreadyUsed: Int): RetryDecision {
        if (retriesAlreadyUsed >= ImageApiSpec.MAX_RETRIES) return RetryDecision.Stop
        if (isModeration(message)) return RetryDecision.Rewrite
        if (isTransport(message)) return RetryDecision.RetrySame
        return RetryDecision.Stop
    }
}

object PromptBuilder {
    fun imagePrompt(panel: PanelSpec, characters: List<CharacterLock>, stylePrompt: String): String {
        val selected = if (panel.characterIds.isEmpty()) {
            characters
        } else {
            characters.filter { it.id in panel.characterIds || it.name in panel.characterIds }
        }
        val cast = selected.joinToString("\n") { "- ${it.name}（${it.role}）：${it.appearance}" }
        val space = if (panel.kind == "cover") {
            "这是封面插画。画面顶部留出足够的纯白空白，给后期叠两行大标题，人物放在中下部。"
        } else {
            "这是内页插画。画面顶部约三分之一必须是干净的纯白空白，给后期叠加旁白，人物和道具放在中下部。"
        }
        return buildString {
            if (stylePrompt.isNotBlank()) {
                append(stylePrompt.trim())
                append("\n\n")
            }
            append(space)
            append("\n白底，竖构图 3:4，手绘彩铅漫画静帧，纸面纹理，不要照片，不要三维渲染。")
            append("\n画面不要文字、不要字幕、不要对白气泡、不要标语、不要水印、不要 Logo。")
            append("\n人物穿日常便服，场景是家里、饭桌、客厅、门口或街边。")
            append("\n本张出场人物必须保持以下锁定外貌，不要重新设计：\n")
            append(cast)
            append("\n本张画面：")
            append(panel.scene.trim())
            if (panel.imagePrompt.isNotBlank() && panel.imagePrompt.trim() != panel.scene.trim()) {
                append('\n')
                append(panel.imagePrompt.trim())
            }
        }
    }
}

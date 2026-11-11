package com.manhuatuji.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object ImageApiSpec {
    const val CHANNEL = "一号通道"
    const val HOST = "direct-api.cangyuansuanli.cn"
    const val BASE_URL = "https://direct-api.cangyuansuanli.cn"
    const val MODEL = "gpt-image-2.5"
    const val SIZE = "1024x1365"
    const val MAX_RETRIES = 2

    fun generationsUrl(): String = "$BASE_URL/v1/images/generations"

    fun taskUrl(taskId: String): String = "${generationsUrl()}/$taskId"

    fun requireChannel(url: String) {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        require(host == HOST) { "生图只能走一号通道 $HOST" }
        require(url.startsWith("https://")) { "生图地址必须使用 HTTPS" }
    }

    fun requestBody(prompt: String, referencePngBase64: String? = null): JSONObject {
        require(prompt.isNotBlank()) { "生图提示词不能为空" }
        val body = JSONObject()
            .put("model", MODEL)
            .put("prompt", prompt)
            .put("size", SIZE)
            .put("n", 1)
            .put("response_format", "url")
            .put("async", true)
        require(body.getString("model") == "gpt-image-2.5") { "生图模型必须是 gpt-image-2.5" }
        require(body.getString("model") != "gpt-image-2") { "不要使用 gpt-image-2" }
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

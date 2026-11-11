package com.manhuatuji.core

import org.json.JSONArray
import org.json.JSONObject

data class ModelCatalog(
    val textFirst: List<String>,
    val rest: List<String>,
) {
    val isEmpty: Boolean get() = textFirst.isEmpty() && rest.isEmpty()
    val onlyImageModels: Boolean get() = textFirst.isEmpty() && rest.isNotEmpty()
}

object TextModels {
    private val textHints = listOf(
        "gemini", "gpt", "claude", "deepseek", "qwen", "grok", "llama", "mistral",
        "glm", "kimi", "moonshot", "hunyuan", "doubao", "ernie", "chat",
    )
    private val imageOnlyHints = listOf(
        "gpt-image", "flux", "midjourney", "seedream", "nano-banana", "imagine-image",
        "dall-e", "stable-diffusion", "sdxl",
    )

    /** 与对话接口相同的拼接方式：基址已以 /v1 结尾时不再加一层。 */
    fun modelsUrl(base: String): String {
        val url = base.trim().trimEnd('/')
        require(url.startsWith("https://")) { "文本接口必须使用 HTTPS" }
        return when {
            url.endsWith("/models") -> url
            url.endsWith("/v1") -> "$url/models"
            else -> "$url/v1/models"
        }
    }

    fun parseModelIds(body: String): List<String> {
        val trimmed = body.trim()
        require(trimmed.startsWith("{") || trimmed.startsWith("[")) { "模型列表不是 JSON" }
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        }
        val ids = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val id = when (val item = array.opt(index)) {
                is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
                is String -> item
                else -> ""
            }.trim()
            if (id.isNotEmpty()) ids += id
        }
        return ids.toList()
    }

    fun arrange(ids: List<String>): ModelCatalog {
        val text = mutableListOf<String>()
        val rest = mutableListOf<String>()
        ids.forEach { id ->
            if (isLikelyText(id)) text += id else rest += id
        }
        return ModelCatalog(text, rest)
    }

    fun isLikelyText(id: String): Boolean {
        val name = id.lowercase()
        if (imageOnlyHints.any { name.contains(it) }) return false
        return textHints.any { name.contains(it) }
    }
}

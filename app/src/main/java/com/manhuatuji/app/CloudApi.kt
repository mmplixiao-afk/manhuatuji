package com.manhuatuji.app

import android.graphics.BitmapFactory
import android.util.Base64
import com.manhuatuji.core.ImageApiSpec
import com.manhuatuji.core.TextModels
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class CloudApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(900, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun models(baseUrl: String, key: String): List<String> {
        require(key.isNotBlank()) { "请先在设置里填写 API Key" }
        return TextModels.parseModelIds(get(TextModels.modelsUrl(baseUrl), key))
    }

    fun text(baseUrl: String, model: String, key: String, system: String, user: String): String {
        require(key.isNotBlank()) { "请先在设置里填写 API Key" }
        require(model.isNotBlank()) { "请设置文本模型" }
        require(system.isNotBlank()) { "分镜提示词不能为空" }
        val url = chatUrl(baseUrl)
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.4)
            .put("max_tokens", 8192)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是一个严格执行用户任务提示词的生成器。只按本次任务输出。"))
                    .put(JSONObject().put("role", "user").put("content", system.trim() + "\n\n【本次输入】\n" + user))
            )
        val result = JSONObject(post(url, body, key))
        val content = result.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        require(content.isNotBlank()) { "模型返回了空内容" }
        return content
    }

    fun image(key: String, prompt: String, out: File, referenceBase64: String? = null) {
        require(key.isNotBlank()) { "请先在设置里填写 API Key" }
        val url = ImageApiSpec.generationsUrl()
        ImageApiSpec.requireChannel(url)
        val body = ImageApiSpec.requestBody(prompt, referenceBase64)
        var result = JSONObject(post(url, body, key))
        if (!hasImage(result)) {
            val taskId = taskId(result)
            require(taskId.isNotBlank()) { "生图接口未返回任务 ID，未自动重试以避免重复扣费" }
            var polls = 0
            while (!hasImage(result) && polls < 120) {
                val status = result.optString("status").lowercase()
                if (status in setOf("failed", "error", "cancelled", "canceled")) {
                    val detail = (result.opt("error") ?: result.opt("message") ?: status).toString()
                    error("生图任务失败：$detail")
                }
                Thread.sleep(2_500L)
                result = JSONObject(get(ImageApiSpec.taskUrl(taskId), key))
                polls++
            }
        }
        require(hasImage(result)) { "生图任务等待超时，未重复提交" }
        val item = (result.optJSONArray("data") ?: result.optJSONArray("images")).getJSONObject(0)
        val bytes = when {
            item.optString("b64_json").isNotBlank() -> Base64.decode(item.getString("b64_json"), Base64.DEFAULT)
            item.optString("url").isNotBlank() -> download(item.getString("url"))
            else -> error("生图接口没有返回图片")
        }
        require(bytes.size in 1_000..20_000_000) { "图片大小无效" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "下载内容不是有效图片" }
        out.parentFile?.mkdirs()
        val pending = File(out.parentFile, out.name + ".tmp")
        pending.writeBytes(bytes)
        Files.move(pending.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun hasImage(value: JSONObject): Boolean {
        val data = value.optJSONArray("data") ?: value.optJSONArray("images") ?: return false
        if (data.length() == 0) return false
        val item = data.optJSONObject(0) ?: return false
        return item.optString("url").isNotBlank() || item.optString("b64_json").isNotBlank()
    }

    private fun taskId(result: JSONObject): String {
        val first = (result.optJSONArray("data") ?: result.optJSONArray("images"))?.optJSONObject(0)
        return result.optString("id")
            .ifBlank { result.optString("task_id") }
            .ifBlank { first?.optString("id").orEmpty() }
            .ifBlank { first?.optString("task_id").orEmpty() }
            .trim()
    }

    private fun chatUrl(base: String): String {
        val url = base.trim().trimEnd('/')
        require(url.startsWith("https://")) { "文本接口必须使用 HTTPS" }
        return when {
            url.endsWith("/chat/completions") -> url
            url.endsWith("/v1") -> "$url/chat/completions"
            else -> "$url/v1/chat/completions"
        }
    }

    private fun post(url: String, body: JSONObject, key: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = text.replace(Regex("\\s+"), " ").trim().take(300)
                error("API HTTP ${response.code}${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }
            return text.ifBlank { error("API 响应为空") }
        }
    }

    private fun get(url: String, key: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = text.replace(Regex("\\s+"), " ").trim().take(300)
                error("API HTTP ${response.code}${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }
            return text.ifBlank { error("API 响应为空") }
        }
    }

    private fun download(url: String): ByteArray {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null) { "图片链接必须使用 HTTPS" }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载图片 HTTP ${response.code}")
            val source = response.body?.byteStream() ?: error("图片响应为空")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 20_000_000) { "图片超过 20 MB" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

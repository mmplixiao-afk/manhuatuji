package com.manhuatuji.app

import android.content.Context
import com.manhuatuji.core.ImageApiSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class ImageProviderConfig(
    val name: String,
    val baseUrl: String,
    val model: String,
    val key: String,
    val imageSize: String,
)

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "manhuatuji_api_key"

    var textBaseUrl: String
        get() = prefs.getString("text_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT_URL
        set(value) { prefs.edit().putString("text_url", value.trim()).apply() }

    var textModel: String
        get() = prefs.getString("text_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT_MODEL
        set(value) { prefs.edit().putString("text_model", value.trim()).apply() }

    var apiKey: String
        get() = secret()
        set(value) { if (value.isNotBlank()) saveSecret(value.trim()) }

    fun hasApiKey(): Boolean = secret().isNotBlank()

    fun hasImageApiKey(): Boolean = imageSecret().isNotBlank()

    fun imageProvider(): ImageProviderConfig = ImageProviderConfig(
        name = prefs.getString("image_name", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_INTERFACE,
        baseUrl = prefs.getString("image_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_URL,
        model = prefs.getString("image_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_MODEL,
        key = imageSecret(),
        imageSize = prefs.getString("image_size", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_SIZE,
    )

    fun saveImageProvider(config: ImageProviderConfig) {
        prefs.edit()
            .putString("image_name", config.name.trim())
            .putString("image_url", config.baseUrl.trim())
            .putString("image_model", config.model.trim())
            .putString("image_size", config.imageSize.trim())
            .apply()
        if (config.key.isNotBlank()) saveImageSecret(config.key.trim())
    }

    fun prompt(name: String): String = prefs.getString("prompt_$name", null) ?: defaultPrompt(name)

    fun savePrompt(name: String, value: String) {
        check(prefs.edit().putString("prompt_$name", value).commit()) { "提示词保存失败" }
    }

    fun resetPrompt(name: String) {
        check(prefs.edit().remove("prompt_$name").commit()) { "提示词恢复失败" }
    }

    fun defaultPrompt(name: String): String = appContext.assets.open("prompts/$name.md")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun masterKey() = (keyStore.getKey(alias, null) ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        generator.generateKey()
    }) as javax.crypto.SecretKey

    private fun secret(): String {
        val value = prefs.getString("api_key", null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun saveSecret(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("api_key", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    private fun imageSecret(): String {
        val value = prefs.getString("key_image", null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun saveImageSecret(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("key_image", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    companion object {
        const val DEFAULT_TEXT_URL = "https://ai.cangyuansuanli.cn"
        const val DEFAULT_TEXT_MODEL = "gemini-3.8-flash-high"
        const val DEFAULT_IMAGE_URL = ImageApiSpec.DEFAULT_BASE_URL
        const val DEFAULT_IMAGE_MODEL = ImageApiSpec.DEFAULT_MODEL
        const val DEFAULT_IMAGE_SIZE = ImageApiSpec.DEFAULT_SIZE
        const val DEFAULT_IMAGE_INTERFACE = ImageApiSpec.DEFAULT_INTERFACE
        val textModelChoices = listOf(
            "gemini-3.8-flash-high" to "Gemini 3.8",
            "gemini-4.0-flash" to "Gemini 4.0",
            "gpt-5.6" to "GPT 5.6",
        )
        val promptTypes = listOf("storyboard", "image", "image_failure")
    }
}

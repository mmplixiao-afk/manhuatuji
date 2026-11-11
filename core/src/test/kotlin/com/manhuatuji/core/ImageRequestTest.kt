package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRequestTest {
    @Test
    fun imageRequestUsesTheCallerModelAndSize() {
        assertEquals("gpt-image-2.5", ImageApiSpec.DEFAULT_MODEL)
        assertEquals("1024x1365", ImageApiSpec.DEFAULT_SIZE)
        assertEquals(
            "https://direct-api.cangyuansuanli.cn/v1/images/generations",
            ImageApiSpec.generationsEndpoint(ImageApiSpec.DEFAULT_BASE_URL),
        )
        assertEquals(
            "https://example.com/v1/images/generations",
            ImageApiSpec.generationsEndpoint("https://example.com/v1"),
        )
        val body = ImageApiSpec.requestBody("my-model", "白底漫画", "1024x1792", async = true, referencePngBase64 = "aaaa")
        assertEquals("my-model", body.getString("model"))
        assertEquals("1024x1792", body.getString("size"))
        assertEquals(1, body.getJSONArray("image").length())
        val plain = ImageApiSpec.requestBody(ImageApiSpec.DEFAULT_MODEL, "白底漫画", ImageApiSpec.DEFAULT_SIZE, async = false)
        assertEquals("gpt-image-2.5", plain.getString("model"))
        assertEquals("1024x1365", plain.getString("size"))
        assertFalse(plain.has("image"))
        assertFalse(plain.optBoolean("async"))
        assertTrue(ImageApiSpec.sizeChoices.any { it.first == "1024x1365" })
        assertEquals(
            "1024x1365",
            ImageApiSpec.requestSize(ImageApiSpec.DEFAULT_BASE_URL, "gpt-image-2.5", "1024x1365"),
        )
        assertTrue(ImageApiSpec.isCangyuanHost(ImageApiSpec.DEFAULT_BASE_URL))
        assertFalse(ImageApiSpec.isCangyuanHost("https://example.com/v1"))
        assertTrue(runCatching { ImageApiSpec.generationsEndpoint("http://example.com") }.isFailure)
    }

    @Test
    fun retriesStopAtTwoAndModerationRewritesThePrompt() {
        assertTrue(ImageRetry.decide("content moderation rejected", 0) is RetryDecision.Rewrite)
        assertTrue(ImageRetry.decide("内容审核未通过", 1) is RetryDecision.Rewrite)
        assertTrue(ImageRetry.decide("内容审核未通过", 2) is RetryDecision.Stop)
        assertTrue(ImageRetry.decide("request timeout", 0) is RetryDecision.RetrySame)
        assertTrue(ImageRetry.decide("HTTP 429", 1) is RetryDecision.RetrySame)
        assertTrue(ImageRetry.decide("timeout", 2) is RetryDecision.Stop)
        assertTrue(ImageRetry.decide("API HTTP 401", 0) is RetryDecision.Stop)
        assertTrue(ImageRetry.isUnsupportedReference("unknown field image is unexpected"))
        assertEquals(2, ImageApiSpec.MAX_RETRIES)
    }

    @Test
    fun builtPromptKeepsCharacterLockAndForbidsText() {
        val panel = PanelSpec(
            id = 1,
            kind = "page",
            caption = "上周同学阿薇来找我。",
            scene = "小满坐在左边倾听",
            imagePrompt = "彩铅，两人坐在木桌前",
            characterIds = listOf("xiaoman"),
        )
        val characters = listOf(
            CharacterLock("xiaoman", "小满", "同学，倾听者", "东亚女性，短发波波头，蓝色开衫叠白色内搭"),
            CharacterLock("husband", "丈夫", "家人", "东亚男性，短黑发，米色外套"),
        )
        val prompt = PromptBuilder.imagePrompt(panel, characters, "手绘彩铅")
        assertTrue(prompt.contains("东亚女性，短发波波头，蓝色开衫叠白色内搭"))
        assertFalse(prompt.contains("东亚男性，短黑发，米色外套"))
        assertTrue(prompt.contains("不要文字"))
        assertTrue(prompt.contains("纯白"))
        assertTrue(prompt.contains("白底"))
        assertTrue(prompt.contains("3:4"))
        assertTrue(prompt.contains("小满坐在左边倾听"))
    }
}

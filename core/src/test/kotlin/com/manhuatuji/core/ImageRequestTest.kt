package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRequestTest {
    @Test
    fun imageRequestStaysOnChannelOne() {
        val url = ImageApiSpec.generationsUrl()
        ImageApiSpec.requireChannel(url)
        assertEquals("direct-api.cangyuansuanli.cn", ImageApiSpec.HOST)
        assertTrue(url.startsWith("https://direct-api.cangyuansuanli.cn/"))
        val body = ImageApiSpec.requestBody("白底漫画", "aaaa")
        assertEquals("gpt-image-2.5", body.getString("model"))
        assertFalse(body.getString("model") == "gpt-image-2")
        assertEquals("1024x1365", body.getString("size"))
        assertEquals(1, body.getJSONArray("image").length())
        val plain = ImageApiSpec.requestBody("白底漫画")
        assertFalse(plain.has("image"))
        val rejected = runCatching { ImageApiSpec.requireChannel("https://ai.cangyuansuanli.cn/v1/images/generations") }
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("一号通道"))
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

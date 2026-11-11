package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextModelsTest {
    @Test
    fun modelsUrlDoesNotDoubleTheV1Prefix() {
        assertEquals(
            "https://ai.cangyuansuanli.cn/v1/models",
            TextModels.modelsUrl("https://ai.cangyuansuanli.cn"),
        )
        assertEquals(
            "https://ai.cangyuansuanli.cn/v1/models",
            TextModels.modelsUrl("https://ai.cangyuansuanli.cn/v1/"),
        )
        assertTrue(runCatching { TextModels.modelsUrl("http://example.com") }.isFailure)
    }

    @Test
    fun parsesOpenAiModelIds() {
        val ids = TextModels.parseModelIds(
            """{"data":[{"id":"gemini-2.5-pro"},{"id":"gpt-image-2.5"},{"id":"claude-sonnet"}]}"""
        )
        assertEquals(listOf("gemini-2.5-pro", "gpt-image-2.5", "claude-sonnet"), ids)
    }

    @Test
    fun textModelsComeFirstAndImageOnlyIdsStayInTheRest() {
        val catalog = TextModels.arrange(
            listOf("gpt-image-2.5", "flux-pro", "gemini-2.5-pro", "claude-sonnet", "some-custom"),
        )
        assertEquals(listOf("gemini-2.5-pro", "claude-sonnet"), catalog.textFirst)
        assertTrue(catalog.rest.contains("gpt-image-2.5"))
        assertTrue(catalog.rest.contains("flux-pro"))
        assertFalse(catalog.onlyImageModels)
        assertTrue(TextModels.arrange(listOf("gpt-image-2.5", "seedream")).onlyImageModels)
        assertTrue(TextModels.arrange(emptyList()).isEmpty)
    }
}

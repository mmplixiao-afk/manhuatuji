package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryboardRulesTest {
    private val manuscript = "上周同学来找我。她说这日子过不下去。"

    private fun shotsJson(extraCover: Boolean = false): String {
        val cover = if (!extraCover) "" else """
            {"id":0,"kind":"cover","voiceover":"","visual":"两人站在白底前","image_prompt":"白底封面，顶部留白"},
        """.trimIndent()
        return """
            {"shots":[
              $cover
              {"id":1,"voiceover":"上周同学来找我。","visual":"小满坐着听同学说话","image_prompt":"白底彩铅，短发女性倾听","subtitle_chunks":["上周同学来找我。"]},
              {"id":2,"voiceover":"她说这日子过不下去。","visual":"对方低头","image_prompt":"白底，长发女性难过","subtitle_chunks":["她说这日子过不下去。"]}
            ]}
        """.trim()
    }

    @Test
    fun freeFormManuscriptDoesNotNeedOldHeaders() {
        assertTrue(StoryboardRules.canSplit(manuscript))
        assertTrue(runCatching { CopyDocument.parse(manuscript) }.isFailure)
        assertEquals("甲\n乙", StoryboardRules.normalizeManuscript("甲\r\n乙"))
        assertEquals(" 保留空格 ", StoryboardRules.normalizeManuscript("\uFEFF 保留空格 "))
    }

    @Test
    fun parsesShotsAndKeepsTheManuscriptIntact() {
        val board = StoryboardRules.parse(shotsJson())
        assertEquals(2, board.panels.size)
        assertTrue(StoryboardRules.coversManuscript(manuscript, board))
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).isEmpty())
        assertEquals(manuscript, StoryboardRules.joinedVoiceover(board))
    }

    @Test
    fun emptyCoverVoiceoverIsExcludedFromTheManuscript() {
        val board = StoryboardRules.parse(shotsJson(extraCover = true))
        assertEquals("cover", board.panels.first().kind)
        assertEquals("", board.panels.first().caption)
        assertTrue(StoryboardRules.coversManuscript(manuscript, board))
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).isEmpty())
    }

    @Test
    fun verbatimMismatchIsBlockingButTheShotsStillParse() {
        val broken = shotsJson().replace("她说这日子过不下去。", "她说日子很难。")
        val board = StoryboardRules.parse(broken)
        assertEquals(2, board.panels.size)
        assertFalse(StoryboardRules.coversManuscript(manuscript, board))
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).any { it.contains("原稿") })
    }

    @Test
    fun lawyerCostumeIsAWarningNotASplitFailure() {
        val board = StoryboardRules.parse(
            shotsJson().replace("小满坐着听同学说话", "小满穿着律师袍站在法庭")
        )
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).isEmpty())
        assertTrue(StoryboardRules.warnings(board).any { it.contains("律师") || it.contains("法庭") })
    }

    @Test
    fun tooManyPagesBlocksConfirm() {
        val pages = (1..19).joinToString(",") { index ->
            """{"voiceover":"$index","visual":"画面$index","image_prompt":"白底$index"}"""
        }
        val board = StoryboardRules.parse("""{"shots":[$pages]}""")
        val text = (1..19).joinToString("") { it.toString() }
        assertTrue(StoryboardRules.coversManuscript(text, board))
        assertTrue(StoryboardRules.blockingIssues(text, board).any { it.contains("18") })
    }

    @Test
    fun imagesStayBlockedUntilTheUserConfirms() {
        val error = runCatching { StoryboardRules.requireConfirmed(false) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("确认出图"))
        StoryboardRules.requireConfirmed(true)
    }

    @Test
    fun exportNamesMatchTheDeliverable() {
        assertEquals(listOf("00_封面.png", "01.png", "02.png", "文案.txt"), ExportNames.all(2))
    }
}

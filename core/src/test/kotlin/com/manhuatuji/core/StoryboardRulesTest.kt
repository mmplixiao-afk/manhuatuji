package com.manhuatuji.core

import org.junit.Assert.assertEquals
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
    fun parsesShotsWithoutRequiringTheManuscriptVerbatim() {
        val board = StoryboardRules.parse(shotsJson())
        assertEquals(2, board.panels.size)
        assertTrue(board.panels.all { it.kind == "page" })
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).isEmpty())
    }

    @Test
    fun firstEmptyCaptionIsACover() {
        val board = StoryboardRules.parse(
            """
            {"panels":[
              {"caption":"","scene":"两人站在白底前","image_prompt":"白底封面，顶部留白"},
              {"caption":"小满听完把这件事讲给大家。","scene":"小满坐着","image_prompt":"白底，短发女性"}
            ]}
            """.trim()
        )
        assertEquals("cover", board.panels.first().kind)
        assertEquals("page", board.panels[1].kind)
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).none { it.contains("没有原文") })
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).isEmpty())
    }

    @Test
    fun explicitCoverMarkersAreRecognized() {
        val byKind = StoryboardRules.parse(shotsJson(extraCover = true))
        assertEquals("cover", byKind.panels.first().kind)
        assertTrue(StoryboardRules.blockingIssues(manuscript, byKind).isEmpty())
        val byFlag = StoryboardRules.parse(
            """
            {"shots":[
              {"cover":true,"voiceover":"","visual":"封面","image_prompt":"白底封面"},
              {"voiceover":"改写后的旁白。","visual":"小满在听","image_prompt":"白底"}
            ]}
            """.trim()
        )
        assertEquals("cover", byFlag.panels.first().kind)
        assertTrue(StoryboardRules.blockingIssues("完全不同的原稿", byFlag).isEmpty())
    }

    @Test
    fun rewrittenNarrationDoesNotBlockConfirm() {
        val board = StoryboardRules.parse(
            shotsJson().replace("上周同学来找我。", "小满说，同学来找她。")
                .replace("她说这日子过不下去。", "日子已经过不下去了。")
        )
        assertTrue(StoryboardRules.joinedVoiceover(board) != manuscript)
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).none { it.contains("原稿") })
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).isEmpty())
    }

    @Test
    fun emptyCaptionAfterTheCoverStillNeedsNarration() {
        val board = StoryboardRules.parse(
            """
            {"shots":[
              {"caption":"","visual":"封面","image_prompt":"白底封面"},
              {"caption":"","visual":"空镜","image_prompt":"白底"}
            ]}
            """.trim()
        )
        assertEquals("cover", board.panels.first().kind)
        assertTrue(StoryboardRules.blockingIssues(manuscript, board).any { it.contains("没有原文") })
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
        assertTrue(StoryboardRules.blockingIssues("随便一篇原稿", board).any { it.contains("18") })
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

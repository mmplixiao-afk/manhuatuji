package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryboardRulesTest {
    private val copy = CopyDocument(
        title = "丈夫在外面又有了儿子，还要她净身出户",
        coverLine1 = "她结婚十七年守着这个家",
        coverLine2 = "他却说外面的人才是白月光",
        body = "同学结婚快十七年。错不在她不够温柔。\n换你是她，你会选择先不离吗？",
        tags = "#真实故事 #婚姻",
    )

    private fun board(
        firstScene: String = "小满短发波波头，坐在桌边听阿薇说话",
        firstCaption: String = "上周同学阿薇来找我。",
        pageCount: Int = 1,
        xiaomanRole: String = "同学，倾听者",
        extraPrompt: String = "白底彩铅，两人坐在桌边喝咖啡",
    ): Storyboard {
        val characters = listOf(
            CharacterLock("xiaoman", "小满", xiaomanRole, "东亚女性，短发波波头，蓝色开衫叠白色内搭"),
            CharacterLock("awei", "阿薇", "当事人，朋友", "东亚女性，三十六岁，长棕发，米色毛衣"),
            CharacterLock("husband", "丈夫", "家人", "东亚男性，短黑发，米色外套，白衬衫，深色裤子"),
        )
        val pages = (1..pageCount).map { index ->
            PanelSpec(
                id = index,
                kind = "page",
                caption = if (index == 1) firstCaption else "后来的事，第 $index 页。",
                scene = if (index == 1) firstScene else "小满继续听阿薇讲第 $index 段",
                imagePrompt = extraPrompt,
                characterIds = listOf("xiaoman", "awei"),
            )
        }
        val cover = PanelSpec(
            id = 0,
            kind = "cover",
            caption = "",
            scene = "左侧长发女性低头，右侧短发男性看向画面外",
            imagePrompt = "白底，夫妻两人站立，上方留白",
            characterIds = listOf("awei", "husband"),
        )
        return Storyboard(characters, listOf(cover) + pages)
    }

    @Test
    fun acceptsLockedStoryboardAndDrawsAnchorFirst() {
        val parsed = StoryboardRules.parse(
            """
            ```json
            {
              "characters": [
                {"id":"xiaoman","name":"小满","role":"同学，倾听者","appearance":"东亚女性，短发波波头，蓝色开衫叠白色内搭"},
                {"id":"awei","name":"阿薇","role":"当事人，朋友","appearance":"东亚女性，三十六岁，长棕发，米色毛衣"},
                {"id":"husband","name":"丈夫","role":"家人","appearance":"东亚男性，短黑发，米色外套，白衬衫，深色裤子"}
              ],
              "panels": [
                {"kind":"cover","scene":"左侧长发女性低头，右侧短发男性看向画面外","image_prompt":"白底，夫妻两人站立，上方留白","character_ids":["awei","husband"]},
                {"caption":"上周同学阿薇来找我。","scene":"小满短发波波头，坐在桌边听阿薇说话","image_prompt":"白底彩铅，两人坐在桌边喝咖啡","character_ids":["xiaoman","awei"]}
              ]
            }
            ```
            """.trim()
        )
        assertTrue(StoryboardRules.issues(copy, parsed).isEmpty())
        val order = StoryboardRules.drawOrder(parsed)
        assertEquals("page", order.first().kind)
        assertEquals("cover", order[1].kind)
        assertEquals(1, StoryboardRules.pageNumber(parsed.panels, parsed.panels[1]))
    }

    @Test
    fun rejectsLawyerCourtAndMissingNarrator() {
        val lawyer = board(xiaomanRole = "律师")
        assertTrue(StoryboardRules.issues(copy, lawyer).any { it.contains("律师") || it.contains("倾听") })
        val court = board(firstScene = "小满在法庭上听阿薇说话")
        assertTrue(StoryboardRules.issues(copy, court).any { it.contains("法庭") })
        val noName = board(firstScene = "两个人坐在桌边喝咖啡", firstCaption = "上周同学来找我。")
        assertTrue(StoryboardRules.issues(copy, noName).any { it.contains("小满") })
    }

    @Test
    fun rejectsTooManyPagesAndCaptionCopiedIntoPrompt() {
        assertTrue(StoryboardRules.issues(copy, board(pageCount = 19)).any { it.contains("18") })
        val copied = board(extraPrompt = "画面上写着：上周同学阿薇来找我。")
        assertTrue(StoryboardRules.issues(copy, copied).any { it.contains("旁白") })
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

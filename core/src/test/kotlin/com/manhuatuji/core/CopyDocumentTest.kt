package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyDocumentTest {
    private val sample = """
        标题：丈夫在外面又有了儿子，还要她净身出户

        封面字（第二行红圈）：
        她结婚十七年守着这个家
        他却说外面的人才是白月光

        正文：
        同学结婚快十七年，两个儿子十五和十三。她怀老大时就发现丈夫在外面有人，两边老人劝她忍了。十多年后才知道，那女的已经有了一岁多的儿子，还在当地买了房。家产都在公婆名下，丈夫说她要离就净身出户。婆婆怪她不温柔，公公把她百万的车换走，后来还得知公公在外面也有儿子。
        错不在她不够温柔。先守住这个家，别让外面的人转正。
        换你是她，你会选择先不离吗？

        #真实故事 #婚姻 #家庭 #情感故事
    """.trimIndent()

    @Test
    fun freeFormManuscriptIsNotAStructuredCopy() {
        val manuscript = "上周同学来找我。她说这日子过不下去。"
        assertEquals(null, CopyDocument.tryExport(manuscript))
    }

    @Test
    fun parsesDeliverableCopy() {
        val copy = CopyDocument.parse(sample)
        assertEquals("丈夫在外面又有了儿子，还要她净身出户", copy.title)
        assertEquals("她结婚十七年守着这个家", copy.coverLine1)
        assertEquals("他却说外面的人才是白月光", copy.coverLine2)
        assertTrue(copy.body.contains("净身出户"))
        assertTrue(copy.body.endsWith("你会选择先不离吗？"))
        assertEquals("#真实故事 #婚姻 #家庭 #情感故事", copy.tags)
        assertTrue(copy.title.codePointCount(0, copy.title.length) <= 20)
    }

    @Test
    fun formatRoundTripKeepsFields() {
        val copy = CopyDocument.parse(sample)
        val again = CopyDocument.parse(copy.format())
        assertEquals(copy, again)
        assertTrue(copy.format().startsWith("标题："))
        assertTrue(copy.format().contains("封面字（第二行红圈）："))
        assertTrue(copy.format().trimEnd().endsWith("#真实故事 #婚姻 #家庭 #情感故事"))
    }

    @Test
    fun rejectsTitleOverTwentyCharacters() {
        val tooLong = sample.replace("丈夫在外面又有了儿子，还要她净身出户", "一二三四五六七八九十一二三四五六七八九十超")
        val error = runCatching { CopyDocument.parse(tooLong) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("20"))
    }

    @Test
    fun rejectsXiaomanTagAndJoinedTags() {
        val tagged = sample.replace("#真实故事 #婚姻 #家庭 #情感故事", "#真实故事 #小满说")
        assertTrue(runCatching { CopyDocument.parse(tagged) }.exceptionOrNull()?.message.orEmpty().contains("小满说"))
        val joined = sample.replace("#真实故事 #婚姻 #家庭 #情感故事", "#真实故事#婚姻")
        assertTrue(runCatching { CopyDocument.parse(joined) }.exceptionOrNull()?.message.orEmpty().contains("空格"))
    }

    @Test
    fun rejectsMissingCoverLineAndMissingQuestion() {
        val oneLine = sample.replace("他却说外面的人才是白月光\n", "")
        assertTrue(runCatching { CopyDocument.parse(oneLine) }.exceptionOrNull()?.message.orEmpty().contains("两行"))
        val noQuestion = sample.replace("换你是她，你会选择先不离吗？", "换你是她，你会选择先不离。")
        assertTrue(runCatching { CopyDocument.parse(noQuestion) }.exceptionOrNull()?.message.orEmpty().contains("提问"))
    }
}

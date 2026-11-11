package com.manhuatuji.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {
    @Test
    fun coverCircleWrapsOnlyTheSecondLine() {
        val line1 = "她结婚十七年守着这个家"
        val line2 = "他却说外面的人才是白月光"
        val layout = OverlayLayout.cover(line1, line2)
        assertEquals(CanvasSpec.WIDTH, 1024)
        assertEquals(CanvasSpec.HEIGHT, 1365)
        assertTrue(layout.line1.bottom < layout.circle.top)
        assertTrue(OverlayLayout.contains(layout.circle, layout.line2.x, layout.line2.top))
        assertTrue(OverlayLayout.contains(layout.circle, layout.line2.x + layout.line2.width, layout.line2.bottom))
        assertTrue(OverlayLayout.contains(layout.circle, (layout.line2.x + layout.line2.width) / 2f, layout.line2.baseline))
        assertTrue(!OverlayLayout.contains(layout.circle, layout.line1.x + layout.line1.width / 2f, layout.line1.baseline))
        assertTrue(layout.whiteBandBottom >= layout.circle.bottom)
        assertTrue(layout.circlePoints.size > 72)
        layout.circlePoints.forEach { (x, y) ->
            assertTrue(OverlayLayout.distanceToBoundary(layout.circle, x, y) < 8f)
        }
        val short = OverlayLayout.cover("她守着这个家", "白月光")
        assertTrue(short.line1.bottom < short.circle.top)
    }

    @Test
    fun pageCaptionStaysInTheTopThird() {
        val caption = """
            上周同学阿薇来找我。
            她今年三十六，
            结婚快十七年了，
            说这日子不知道
            还能不能过下去
        """.trim()
        val page = OverlayLayout.page(caption)
        assertEquals(5, page.lines.size)
        assertTrue(page.size >= 40f)
        assertTrue(page.lines.last().bottom <= CanvasSpec.HEIGHT / 3f)
        assertTrue(page.whiteBandBottom <= CanvasSpec.HEIGHT / 3f + 24f)
        assertTrue(page.lines.all { it.x >= 48f })

        val longer = (1..12).joinToString("\n") { "她说这日子不知道还能不能过下去" }
        val shrunk = OverlayLayout.page(longer)
        assertTrue(shrunk.size < page.size)
        assertTrue(shrunk.lines.last().bottom <= CanvasSpec.HEIGHT / 3f + 1f)
    }

    @Test
    fun centerCropCoversTheThreeByFourFrame() {
        val wide = OverlayLayout.centerCropDest(2000, 1000)
        assertTrue(wide.left < 0f && wide.right > CanvasSpec.WIDTH)
        assertEquals(0f, wide.top, 0.1f)
        assertEquals(CanvasSpec.HEIGHT.toFloat(), wide.bottom, 0.1f)
        val tall = OverlayLayout.centerCropDest(800, 2000)
        assertEquals(0f, tall.left, 0.1f)
        assertTrue(tall.top < 0f && tall.bottom > CanvasSpec.HEIGHT)
    }
}

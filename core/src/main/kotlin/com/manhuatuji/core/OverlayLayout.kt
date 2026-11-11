package com.manhuatuji.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object CanvasSpec {
    const val WIDTH = 1024
    const val HEIGHT = 1365
}

data class TextRun(
    val text: String,
    val x: Float,
    val baseline: Float,
    val size: Float,
) {
    val width: Float get() = text.codePointCount(0, text.length) * size
    val top: Float get() = baseline - size * 0.82f
    val bottom: Float get() = baseline + size * 0.18f
}

/** 包住第二行标题的胶囊形圈。宽扁，不盖住第一行。 */
data class HandCircle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val radius: Float get() = (bottom - top) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

data class CoverLayout(
    val line1: TextRun,
    val line2: TextRun,
    val circle: HandCircle,
    val circlePoints: List<Pair<Float, Float>>,
    val whiteBandBottom: Float,
)

data class PageLayout(
    val lines: List<TextRun>,
    val size: Float,
    val whiteBandBottom: Float,
)

data class DestRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

object OverlayLayout {
    fun cover(line1: String, line2: String): CoverLayout {
        require(line1.isNotBlank() && line2.isNotBlank()) { "封面两行都不能为空" }
        val side = 36f
        val maxTextWidth = CanvasSpec.WIDTH - side * 2
        var size = 72f
        while (size > 40f && (measure(line1, size) > maxTextWidth || measure(line2, size) > maxTextWidth)) {
            size -= 2f
        }
        val line1Baseline = 40f + size * 0.82f
        val line2Baseline = line1Baseline + size * 1.38f
        val run1 = TextRun(line1, (CanvasSpec.WIDTH - measure(line1, size)) / 2f, line1Baseline, size)
        val run2 = TextRun(line2, (CanvasSpec.WIDTH - measure(line2, size)) / 2f, line2Baseline, size)
        val padX = size * 0.42f
        val padY = size * 0.16f
        val circle = HandCircle(
            left = run2.x - padX,
            top = run2.top - padY,
            right = run2.x + run2.width + padX,
            bottom = run2.bottom + padY,
        )
        val points = circleLoop(circle)
        val whiteBandBottom = (circle.bottom + 22f).coerceAtMost(CanvasSpec.HEIGHT * 0.46f)
        return CoverLayout(run1, run2, circle, points, whiteBandBottom)
    }

    fun page(caption: String): PageLayout {
        val marginX = 68f
        val maxWidth = CanvasSpec.WIDTH - marginX * 2
        val limit = CanvasSpec.HEIGHT / 3f
        var size = 46f
        var lines = wrap(caption, size, maxWidth)
        while (size > 24f && blockBottom(size, lines.size.coerceAtLeast(1), size * 1.45f) > limit) {
            size -= 1f
            lines = wrap(caption, size, maxWidth)
        }
        var step = size * 1.45f
        val minStep = size * 1.12f
        while (lines.isNotEmpty() && blockBottom(size, lines.size, step) > limit && step > minStep) {
            step -= 1f
        }
        val runs = lines.mapIndexed { index, text ->
            TextRun(text, marginX, 40f + size * 0.82f + index * step, size)
        }
        val band = if (runs.isEmpty()) 48f else (runs.last().bottom + 20f).coerceAtMost(CanvasSpec.HEIGHT * 0.42f)
        return PageLayout(runs, size, band)
    }

    fun centerCropDest(srcW: Int, srcH: Int, dstW: Int = CanvasSpec.WIDTH, dstH: Int = CanvasSpec.HEIGHT): DestRect {
        require(srcW > 0 && srcH > 0)
        val scale = max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val width = srcW * scale
        val height = srcH * scale
        val left = (dstW - width) / 2f
        val top = (dstH - height) / 2f
        return DestRect(left, top, left + width, top + height)
    }

    fun contains(circle: HandCircle, x: Float, y: Float): Boolean {
        val dx: Float
        val dy: Float
        val spine = spineX(circle, x)
        dx = x - spine
        dy = y - circle.cy
        return dx * dx + dy * dy <= circle.radius * circle.radius + 0.01f
    }

    fun distanceToBoundary(circle: HandCircle, x: Float, y: Float): Float {
        val dx = x - spineX(circle, x)
        val dy = y - circle.cy
        return kotlin.math.abs(sqrt(dx * dx + dy * dy) - circle.radius)
    }

    private fun spineX(circle: HandCircle, x: Float): Float {
        val radius = circle.radius
        var leftCenter = circle.left + radius
        var rightCenter = circle.right - radius
        if (leftCenter > rightCenter) {
            val mid = (circle.left + circle.right) / 2f
            leftCenter = mid
            rightCenter = mid
        }
        return x.coerceIn(leftCenter, rightCenter)
    }

    fun circleLoop(circle: HandCircle): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>()
        val radius = circle.radius
        var leftCenter = circle.left + radius
        var rightCenter = circle.right - radius
        if (leftCenter > rightCenter) {
            val mid = (circle.left + circle.right) / 2f
            leftCenter = mid
            rightCenter = mid
        }
        val cy = circle.cy
        val straight = max(0f, rightCenter - leftCenter)
        val samples = 72
        val overlap = 10
        val total = samples + overlap
        for (index in 0 until total) {
            val t = index.toFloat() / samples.toFloat()
            val raw = pointOnCapsule(t, leftCenter, rightCenter, cy, radius, straight)
            val wobble = 3.2f * sin(index * 0.55f) + 1.6f * sin(index * 0.23f + 1.2f)
            val dx = raw.first - (leftCenter + rightCenter) / 2f
            val dy = raw.second - cy
            val len = max(1f, sqrt(dx * dx + dy * dy))
            points += (raw.first + dx / len * wobble) to (raw.second + dy / len * wobble)
        }
        return points
    }

    private fun pointOnCapsule(
        t: Float,
        leftCenter: Float,
        rightCenter: Float,
        cy: Float,
        radius: Float,
        straight: Float,
    ): Pair<Float, Float> {
        val cap = (Math.PI * radius).toFloat()
        val perimeter = 2f * straight + 2f * cap
        var distance = (t % 1f) * perimeter
        if (distance < straight) {
            return (leftCenter + distance) to (cy - radius)
        }
        distance -= straight
        if (distance < cap) {
            val angle = -Math.PI / 2.0 + Math.PI * (distance / cap)
            return (rightCenter + radius * cos(angle).toFloat()) to (cy + radius * sin(angle).toFloat())
        }
        distance -= cap
        if (distance < straight) {
            return (rightCenter - distance) to (cy + radius)
        }
        distance -= straight
        val angle = Math.PI / 2.0 + Math.PI * (distance / cap).coerceIn(0f, 1f)
        return (leftCenter + radius * cos(angle).toFloat()) to (cy + radius * sin(angle).toFloat())
    }

    private fun blockBottom(size: Float, count: Int, step: Float): Float {
        if (count <= 0) return 40f
        return 40f + size + (count - 1) * step
    }

    private fun measure(text: String, size: Float) = text.codePointCount(0, text.length) * size

    private fun wrap(caption: String, size: Float, maxWidth: Float): List<String> {
        if (caption.isBlank()) return emptyList()
        val maxChars = max(4, (maxWidth / size).toInt())
        return caption.replace("\r\n", "\n").split('\n').flatMap { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) emptyList() else chunk(trimmed, maxChars)
        }
    }

    private fun chunk(text: String, maxChars: Int): List<String> {
        val points = ArrayList<Int>()
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            points += code
            index += Character.charCount(code)
        }
        if (points.size <= maxChars) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < points.size) {
            val end = min(start + maxChars, points.size)
            out += String(points.subList(start, end).toIntArray(), 0, end - start)
            start = end
        }
        return out
    }
}

package com.manhuatuji.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.manhuatuji.core.CanvasSpec
import com.manhuatuji.core.OverlayLayout
import com.manhuatuji.core.TextRun
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ImageCompositor(context: Context) {
    private val assets = context.applicationContext.assets
    private val coverFace by lazy { Typeface.createFromAsset(assets, "fonts/LXGWWenKai-Medium.ttf") }
    private val pageFace by lazy { Typeface.createFromAsset(assets, "fonts/LXGWWenKai-Light.ttf") }

    fun composeCover(raw: Bitmap, line1: String, line2: String): Bitmap {
        val layout = OverlayLayout.cover(line1, line2)
        val out = base(raw)
        val canvas = Canvas(out)
        paintBand(canvas, layout.whiteBandBottom)
        drawRun(canvas, layout.line1, coverFace)
        drawRun(canvas, layout.line2, coverFace)
        drawCircle(canvas, layout.circlePoints)
        return out
    }

    fun composePage(raw: Bitmap, caption: String): Bitmap {
        val layout = OverlayLayout.page(caption)
        val out = base(raw)
        val canvas = Canvas(out)
        paintBand(canvas, layout.whiteBandBottom)
        layout.lines.forEach { drawRun(canvas, it, pageFace) }
        return out
    }

    fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(pending).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (pending.exists() && pending.path != file.path) pending.delete()
        }
    }

    private fun base(raw: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(CanvasSpec.WIDTH, CanvasSpec.HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val dest = OverlayLayout.centerCropDest(raw.width, raw.height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(raw, null, RectF(dest.left, dest.top, dest.right, dest.bottom), paint)
        return out
    }

    private fun paintBand(canvas: Canvas, band: Float) {
        val solid = (band - 28f).coerceAtLeast(0f)
        val white = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, CanvasSpec.WIDTH.toFloat(), solid, white)
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, solid, 0f, band + 10f,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, solid, CanvasSpec.WIDTH.toFloat(), band + 10f, fade)
    }

    private fun drawRun(canvas: Canvas, run: TextRun, typeface: Typeface) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            textSize = run.size
            this.typeface = typeface
            textAlign = Paint.Align.LEFT
            isSubpixelText = true
        }
        val measured = paint.measureText(run.text)
        val target = run.width
        if (measured > 0f) paint.textScaleX = target / measured
        canvas.drawText(run.text, run.x, run.baseline, paint)
    }

    private fun drawCircle(canvas: Canvas, points: List<Pair<Float, Float>>) {
        if (points.size < 2) return
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE10600.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 8f
        }
        canvas.drawPath(path, paint)
        canvas.save()
        canvas.translate(1.3f, 0.7f)
        paint.strokeWidth = 3.4f
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

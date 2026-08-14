package com.refinepilot.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

object DigitRecognizer {
    data class Result(val level: Int, val confidence: Float)

    // Calibrated from the supplied 1280x576 RAN Item Refinement screenshots.
    // This crop contains only the "+N" target grade beside TARGET.
    private const val LEFT = 0.222f
    private const val TOP = 0.332f
    private const val RIGHT = 0.257f
    private const val BOTTOM = 0.402f

    fun detectLevel(screen: Bitmap): Result? {
        if (screen.width < screen.height) return null
        val x = (screen.width * LEFT).toInt().coerceAtLeast(0)
        val y = (screen.height * TOP).toInt().coerceAtLeast(0)
        val w = (screen.width * (RIGHT - LEFT)).toInt().coerceAtLeast(12).coerceAtMost(screen.width - x)
        val h = (screen.height * (BOTTOM - TOP)).toInt().coerceAtLeast(16).coerceAtMost(screen.height - y)
        if (w <= 0 || h <= 0) return null

        val crop = Bitmap.createBitmap(screen, x, y, w, h)
        val mask = brightTextMask(crop)
        crop.recycle()
        val bounds = contentBounds(mask) ?: run { mask.recycle(); return null }
        val glyph = Bitmap.createBitmap(mask, bounds.left, bounds.top, bounds.width(), bounds.height())
        mask.recycle()

        // The crop contains "+N". Keep the right-hand portion where N lives.
        val digitStart = (glyph.width * 0.40f).toInt().coerceIn(0, max(0, glyph.width - 1))
        val digitWidth = (glyph.width - digitStart).coerceAtLeast(1)
        val digit = Bitmap.createBitmap(glyph, digitStart, 0, digitWidth, glyph.height)
        glyph.recycle()
        val normalized = Bitmap.createScaledBitmap(digit, 26, 38, true)
        digit.recycle()

        var bestDigit = -1
        var best = -1f
        var second = -1f
        for (n in 0..9) {
            val template = renderTemplate(n)
            val score = similarity(normalized, template)
            template.recycle()
            if (score > best) {
                second = best
                best = score
                bestDigit = n
            } else if (score > second) second = score
        }
        normalized.recycle()

        val confidence = (best * 0.78f + max(0f, best - second) * 0.22f).coerceIn(0f, 1f)
        return if (best >= 0.43f && best - second >= 0.025f) Result(bestDigit, confidence) else null
    }

    private fun brightTextMask(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val c = src.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            // Enhancement text is a warm white/yellow. Keep bright neutral and warm pixels.
            val bright = (r > 175 && g > 155 && b > 90) || (r > 205 && g > 205 && b > 205)
            out.setPixel(x, y, if (bright) Color.WHITE else Color.BLACK)
        }
        return out
    }

    private fun contentBounds(mask: Bitmap): Rect? {
        var minX = mask.width; var minY = mask.height; var maxX = -1; var maxY = -1
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (Color.red(mask.getPixel(x, y)) > 128) {
                minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
            }
        }
        return if (maxX <= minX || maxY <= minY) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun renderTemplate(n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(26, 38, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        canvas.drawText(n.toString(), 13f, 19f - (fm.ascent + fm.descent) / 2f, paint)
        return bmp
    }

    private fun similarity(a: Bitmap, b: Bitmap): Float {
        var intersection = 0
        var union = 0
        for (y in 0 until min(a.height, b.height)) for (x in 0 until min(a.width, b.width)) {
            val av = Color.red(a.getPixel(x, y)) > 90
            val bv = Color.red(b.getPixel(x, y)) > 90
            if (av && bv) intersection++
            if (av || bv) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union
    }
}

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

    private const val LEFT = 0.216f
    private const val TOP = 0.315f
    private const val RIGHT = 0.255f
    private const val BOTTOM = 0.425f

    fun detectLevel(screen: Bitmap): Result? {
        if (screen.width < screen.height) return null
        val crop = Bitmap.createBitmap(
            screen,
            (screen.width * LEFT).toInt().coerceAtLeast(0),
            (screen.height * TOP).toInt().coerceAtLeast(0),
            (screen.width * (RIGHT - LEFT)).toInt().coerceAtLeast(8),
            (screen.height * (BOTTOM - TOP)).toInt().coerceAtLeast(12)
        )
        val mask = yellowMask(crop)
        val bounds = contentBounds(mask) ?: return null
        val glyph = Bitmap.createBitmap(mask, bounds.left, bounds.top, bounds.width(), bounds.height())
        val digitStart = (glyph.width * 0.42f).toInt().coerceIn(0, max(0, glyph.width - 1))
        val digit = Bitmap.createBitmap(glyph, digitStart, 0, glyph.width - digitStart, glyph.height)
        val normalized = Bitmap.createScaledBitmap(digit, 26, 38, true)

        var bestDigit = -1
        var best = -1f
        var second = -1f
        for (n in 0..9) {
            val score = similarity(normalized, renderTemplate(n))
            if (score > best) {
                second = best
                best = score
                bestDigit = n
            } else if (score > second) second = score
        }
        val confidence = (best * 0.75f + max(0f, best - second) * 0.25f).coerceIn(0f, 1f)
        return if (best >= 0.48f && best - second >= 0.035f) Result(bestDigit, confidence) else null
    }

    private fun yellowMask(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val c = src.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val yellow = r > 155 && g > 120 && r > b * 1.25 && g > b * 1.15
            out.setPixel(x, y, if (yellow) Color.WHITE else Color.BLACK)
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
        val canvas = Canvas(bmp); canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 36f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        canvas.drawText(n.toString(), 13f, 19f - (fm.ascent + fm.descent) / 2f, paint)
        return bmp
    }

    private fun similarity(a: Bitmap, b: Bitmap): Float {
        var intersection = 0; var union = 0
        for (y in 0 until min(a.height, b.height)) for (x in 0 until min(a.width, b.width)) {
            val av = Color.red(a.getPixel(x, y)) > 90; val bv = Color.red(b.getPixel(x, y)) > 90
            if (av && bv) intersection++
            if (av || bv) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union
    }
}

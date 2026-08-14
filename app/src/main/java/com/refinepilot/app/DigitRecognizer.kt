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

    // Tight crop around only the yellow "+N" TARGET grade in the supplied
    // 1280x576 RAN refinement screen. The previous crop also contained the
    // TARGET label, which polluted the mask and caused every read to fail.
    private const val LEFT = 0.226f
    private const val TOP = 0.337f
    private const val RIGHT = 0.252f
    private const val BOTTOM = 0.390f

    fun detectLevel(screen: Bitmap): Result? {
        if (screen.width < screen.height) return null
        val x = (screen.width * LEFT).toInt().coerceAtLeast(0)
        val y = (screen.height * TOP).toInt().coerceAtLeast(0)
        val w = (screen.width * (RIGHT - LEFT)).toInt().coerceAtLeast(12)
        val h = (screen.height * (BOTTOM - TOP)).toInt().coerceAtLeast(16)
        if (x + w > screen.width || y + h > screen.height) return null

        val crop = Bitmap.createBitmap(screen, x, y, w, h)
        val mask = yellowMask(crop)
        crop.recycle()

        val components = connectedComponents(mask)
            .filter { it.width() >= 2 && it.height() >= 5 }
            .sortedBy { it.left }
        if (components.isEmpty()) {
            mask.recycle()
            return null
        }

        // In "+4", "+7", etc. the digit is the right-most glyph. This avoids
        // needing to recognize the plus sign or the TARGET label.
        val digitBounds = components.last()
        val digit = Bitmap.createBitmap(mask, digitBounds.left, digitBounds.top, digitBounds.width(), digitBounds.height())
        mask.recycle()
        val normalized = Bitmap.createScaledBitmap(digit, 28, 40, true)
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
            } else if (score > second) {
                second = score
            }
        }
        normalized.recycle()

        val gap = best - second
        val confidence = (best * 0.8f + max(0f, gap) * 0.2f).coerceIn(0f, 1f)
        return if (best >= 0.28f && gap >= 0.012f) Result(bestDigit, confidence) else null
    }

    private fun yellowMask(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val c = src.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val yellowOrWhite = (r > 145 && g > 115 && b < 150 && r > b * 1.15f) ||
                (r > 190 && g > 185 && b > 150)
            out.setPixel(x, y, if (yellowOrWhite) Color.WHITE else Color.BLACK)
        }
        return out
    }

    private fun connectedComponents(mask: Bitmap): List<Rect> {
        val w = mask.width
        val h = mask.height
        val seen = BooleanArray(w * h)
        val result = mutableListOf<Rect>()
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)

        fun white(x: Int, y: Int) = Color.red(mask.getPixel(x, y)) > 128

        for (sy in 0 until h) for (sx in 0 until w) {
            val start = sy * w + sx
            if (seen[start] || !white(sx, sy)) continue
            var head = 0
            var tail = 0
            qx[tail] = sx
            qy[tail++] = sy
            seen[start] = true
            var minX = sx
            var maxX = sx
            var minY = sy
            var maxY = sy

            while (head < tail) {
                val x = qx[head]
                val y = qy[head++]
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val idx = ny * w + nx
                    if (!seen[idx] && white(nx, ny)) {
                        seen[idx] = true
                        qx[tail] = nx
                        qy[tail++] = ny
                    }
                }
            }
            result += Rect(minX, minY, maxX + 1, maxY + 1)
        }
        return result
    }

    private fun renderTemplate(n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(28, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        canvas.drawText(n.toString(), 14f, 20f - (fm.ascent + fm.descent) / 2f, paint)
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

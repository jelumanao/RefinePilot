package com.refinepilot.app

import android.graphics.Bitmap
import android.graphics.Color

object BurrInventoryDetector {
    data class Slot(val xNorm: Float, val yNorm: Float, val score: Float)

    // Refine Inventory grid calibrated from the supplied 1280x576 screenshots.
    private const val GRID_LEFT = 0.607f
    private const val GRID_TOP = 0.172f
    private const val GRID_RIGHT = 0.895f
    private const val GRID_BOTTOM = 0.889f
    private const val COLS = 6
    private const val ROWS = 6

    fun findFineBurrSlots(screen: Bitmap): List<Slot> {
        if (screen.width < screen.height) return emptyList()
        val results = mutableListOf<Slot>()
        val cellW = (GRID_RIGHT - GRID_LEFT) / COLS
        val cellH = (GRID_BOTTOM - GRID_TOP) / ROWS

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val left = GRID_LEFT + col * cellW
                val top = GRID_TOP + row * cellH
                val right = left + cellW
                val bottom = top + cellH
                val score = redIconScore(screen, left, top, right, bottom)
                if (score >= 0.035f) {
                    results += Slot(
                        xNorm = left + cellW * 0.50f,
                        yNorm = top + cellH * 0.52f,
                        score = score
                    )
                }
            }
        }
        return results.sortedWith(compareByDescending<Slot> { it.score }.thenBy { it.yNorm }.thenBy { it.xNorm })
    }

    private fun redIconScore(bitmap: Bitmap, leftN: Float, topN: Float, rightN: Float, bottomN: Float): Float {
        val left = (bitmap.width * leftN).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * topN).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * rightN).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * bottomN).toInt().coerceIn(top + 1, bitmap.height)

        // Ignore borders/labels and focus on the icon body in the middle of each slot.
        val x0 = left + ((right - left) * 0.18f).toInt()
        val x1 = right - ((right - left) * 0.18f).toInt()
        val y0 = top + ((bottom - top) * 0.14f).toInt()
        val y1 = bottom - ((bottom - top) * 0.20f).toInt()

        var red = 0
        var sampled = 0
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 step 2) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r > 120 && r > g * 1.35f && r > b * 1.20f) red++
                sampled++
            }
        }
        return if (sampled == 0) 0f else red.toFloat() / sampled
    }
}

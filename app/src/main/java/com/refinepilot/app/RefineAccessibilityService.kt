package com.refinepilot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RefineAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tapNormalized(xRatio: Float, yRatio: Float, durationMs: Long = 80L): Boolean {
        val dm = resources.displayMetrics
        val path = Path().apply { moveTo(dm.widthPixels * xRatio, dm.heightPixels * yRatio) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile var instance: RefineAccessibilityService? = null
    }
}

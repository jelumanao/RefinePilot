package com.refinepilot.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RefineAutomationService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var executor: ScheduledExecutorService? = null
    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var overlayState: TextView? = null
    private var overlayStats: TextView? = null
    private var overlayMiniTarget: TextView? = null
    private var overlayDetails: View? = null
    private var targetSelectorRow: View? = null
    private var pauseButton: Button? = null
    private var minimizeButton: Button? = null
    private var minimized = false
    private var targetLevel = 9
    private var maxAttempts = 250
    private var attempts = 0
    private var attemptsOnCurrentBurrStack = 0
    private var burrReloads = 0
    private var captureMisses = 0
    private var confirmedTargetReads = 0
    private var currentLevel: Int? = null
    private val paused = AtomicBoolean(true)
    private val hasStarted = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAutomation("Stopped by user")
            ACTION_START -> startFromIntent(intent)
        }
        return START_NOT_STICKY
    }

    private fun startFromIntent(intent: Intent) {
        if (projection != null) return
        targetLevel = intent.getIntExtra(EXTRA_TARGET, 9).coerceIn(1, 9)
        maxAttempts = intent.getIntExtra(EXTRA_MAX_ATTEMPTS, 250).coerceIn(1, 5000)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready • open RAN and press Start"))
        if (!Settings.canDrawOverlays(this)) return stopAutomation("Overlay permission missing")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = intent.parcelableExtraCompat(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) return stopAutomation("Screen capture permission missing")

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data).also { mp ->
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopAutomation("Screen capture ended") }
            }, mainHandler)
        }
        createCapture()
        showOverlay()
        startLoop()
    }

    private fun createCapture() {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "RefinePilotCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun startLoop() {
        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay({ tick() }, 1800, 3000, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (stopped.get() || paused.get() || !busy.compareAndSet(false, true)) return
        try {
            if (attempts >= maxAttempts) return stopAutomation("Max attempts reached")

            val bitmap = latestBitmap()
            if (bitmap == null) {
                captureMisses++
                updateOverlay("Waiting for screen capture…")
                if (captureMisses >= 12) stopAutomation("Screen capture unavailable")
                return
            }
            captureMisses = 0

            val result = DigitRecognizer.detectLevel(bitmap)
            if (result != null) {
                currentLevel = result.level

                // Stop only on the exact selected target. Do not require seeing
                // target-1 first because OCR may miss that intermediate grade.
                // Require multiple consecutive confident reads to avoid a false
                // +6 -> +7/+8 classification stopping the session prematurely.
                if (result.level == targetLevel && result.confidence >= TARGET_STOP_MIN_CONFIDENCE) {
                    confirmedTargetReads++
                    bitmap.recycle()
                    if (confirmedTargetReads >= TARGET_CONFIRM_READS) {
                        return stopAutomation("+$targetLevel confirmed ✅ Refinement stopped")
                    }
                    updateOverlay("Confirming exact +$targetLevel (${confirmedTargetReads}/$TARGET_CONFIRM_READS)…")
                    return
                } else {
                    confirmedTargetReads = 0
                }
            } else {
                currentLevel = null
                confirmedTargetReads = 0
            }

            val accessibility = RefineAccessibilityService.instance
                ?: run { bitmap.recycle(); return stopAutomation("Accessibility service disconnected") }

            if (attemptsOnCurrentBurrStack >= BURR_STACK_SIZE) {
                val burrSlots = BurrInventoryDetector.findFineBurrSlots(bitmap)
                bitmap.recycle()
                if (burrSlots.isEmpty()) return stopAutomation("No Fine Burr stack detected — stopped")

                val next = burrSlots.first()
                updateOverlay("Loading next Fine Burr stack…")
                if (!accessibility.tapNormalized(next.xNorm, next.yNorm)) {
                    return stopAutomation("Could not select next Fine Burr stack")
                }
                attemptsOnCurrentBurrStack = 0
                burrReloads++
                return
            }

            bitmap.recycle()
            val state = currentLevel?.let { "+$it detected • refining…" }
                ?: "Level unread • refining in fixed-position mode…"
            updateOverlay(state)

            if (!accessibility.tapNormalized(REFINE_X, REFINE_Y)) {
                return stopAutomation("Tap could not be dispatched")
            }
            attempts++
            attemptsOnCurrentBurrStack++
            updateOverlay("Attempt #$attempts sent")
        } catch (_: Throwable) {
            stopAutomation("Safety stop: capture error")
        } finally {
            busy.set(false)
        }
    }

    private fun latestBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try { image.toBitmap() } finally { image.close() }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun showOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_refiner, null)
        overlay = view
        overlayState = view.findViewById(R.id.overlayState)
        overlayStats = view.findViewById(R.id.overlayStats)
        overlayMiniTarget = view.findViewById(R.id.overlayMiniTarget)
        overlayDetails = view.findViewById(R.id.overlayDetails)
        targetSelectorRow = view.findViewById(R.id.targetSelectorRow)
        pauseButton = view.findViewById(R.id.btnPauseOverlay)
        minimizeButton = view.findViewById(R.id.btnMinimizeOverlay)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 80
        }

        pauseButton?.text = "Start"
        targetSelectorRow?.visibility = View.VISIBLE
        pauseButton?.setOnClickListener {
            val wasStarted = hasStarted.get()
            if (!wasStarted) {
                hasStarted.set(true)
                paused.set(false)
                pauseButton?.text = "Pause"
                targetSelectorRow?.visibility = View.GONE
                updateOverlay("Started")
                return@setOnClickListener
            }

            val nowPaused = !paused.get()
            paused.set(nowPaused)
            pauseButton?.text = if (nowPaused) "Resume" else "Pause"
            targetSelectorRow?.visibility = if (nowPaused) View.VISIBLE else View.GONE
            updateOverlay(if (nowPaused) "Paused • choose target if needed" else "Resumed")
        }

        view.findViewById<Button>(R.id.btnResetOverlay).setOnClickListener {
            resetSessionState()
        }

        minimizeButton?.setOnClickListener {
            minimized = !minimized
            overlayDetails?.visibility = if (minimized) View.GONE else View.VISIBLE
            minimizeButton?.text = if (minimized) "+" else "−"
            runCatching { wm.updateViewLayout(view, params) }
        }

        view.findViewById<Button>(R.id.btnTarget7).setOnClickListener { updateTargetWhilePaused(7) }
        view.findViewById<Button>(R.id.btnTarget8).setOnClickListener { updateTargetWhilePaused(8) }
        view.findViewById<Button>(R.id.btnTarget9).setOnClickListener { updateTargetWhilePaused(9) }
        view.findViewById<Button>(R.id.btnStopOverlay).setOnClickListener { stopAutomation("Stopped by user") }

        var startX = 0f
        var startY = 0f
        var originalX = 0
        var originalY = 0
        view.findViewById<TextView>(R.id.overlayTitle).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originalX = params.x
                    originalY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originalX + (event.rawX - startX).toInt()
                    params.y = originalY + (event.rawY - startY).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        updateOverlay("Ready • set up RAN, then press Start")
    }

    private fun resetSessionState() {
        attempts = 0
        attemptsOnCurrentBurrStack = 0
        burrReloads = 0
        captureMisses = 0
        confirmedTargetReads = 0
        currentLevel = null
        updateOverlay(
            when {
                !hasStarted.get() -> "Ready • session reset"
                paused.get() -> "Paused • session reset"
                else -> "Session reset • refining continues"
            }
        )
    }

    private fun updateTargetWhilePaused(newTarget: Int) {
        if (!paused.get()) return
        targetLevel = newTarget.coerceIn(1, 9)
        confirmedTargetReads = 0
        updateOverlay(if (hasStarted.get()) "Paused • target changed to +$targetLevel" else "Ready • target changed to +$targetLevel")
    }

    private fun updateOverlay(state: String) {
        mainHandler.post {
            overlayState?.text = state
            overlayMiniTarget?.text = "+$targetLevel"
            val level = currentLevel?.let { "+$it" } ?: "?"
            overlayStats?.text = "Current: $level   Target: +$targetLevel\nAttempts: $attempts / $maxAttempts   Burr reloads: $burrReloads"
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun stopAutomation(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        mainHandler.post {
            overlayState?.text = reason
            runCatching { overlay?.let { wm.removeView(it) } }
            overlay = null
            executor?.shutdownNow()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            runCatching { projection?.stop() }
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!stopped.get()) stopAutomation("Service ended")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RefinePilot automation", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, RefineAutomationService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("RefinePilot • Target +$targetLevel")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "STOP", stopPending)
            .build()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

    companion object {
        const val ACTION_START = "com.refinepilot.app.START"
        const val ACTION_STOP = "com.refinepilot.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_TARGET = "target"
        const val EXTRA_MAX_ATTEMPTS = "maxAttempts"
        private const val CHANNEL_ID = "refinepilot_automation"
        private const val NOTIFICATION_ID = 4109
        private const val BURR_STACK_SIZE = 30
        private const val TARGET_CONFIRM_READS = 3
        private const val TARGET_STOP_MIN_CONFIDENCE = 0.34f

        private const val REFINE_X = 0.364f
        private const val REFINE_Y = 0.937f
    }
}

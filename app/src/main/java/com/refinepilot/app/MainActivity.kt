package com.refinepilot.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.refinepilot.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult

        val target = when (binding.targetGroup.checkedRadioButtonId) {
            R.id.target7 -> 7
            R.id.target8 -> 8
            else -> 9
        }
        val maxAttempts = binding.maxAttempts.text.toString().toIntOrNull()?.coerceIn(1, 5000) ?: 250

        val serviceIntent = Intent(this, RefineAutomationService::class.java).apply {
            action = RefineAutomationService.ACTION_START
            putExtra(RefineAutomationService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(RefineAutomationService.EXTRA_RESULT_DATA, result.data)
            putExtra(RefineAutomationService.EXTRA_TARGET, target)
            putExtra(RefineAutomationService.EXTRA_MAX_ATTEMPTS, maxAttempts)
        }
        startForegroundService(serviceIntent)
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        binding.btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                binding.txtHint.text = "Allow the floating overlay first."
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                binding.txtHint.text = "Enable RefinePilot Accessibility Service first."
                return@setOnClickListener
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        binding.statusAccessibility.text = if (isAccessibilityEnabled()) "Accessibility: ✓ Ready" else "Accessibility: ✕ Required"
        binding.statusOverlay.text = if (Settings.canDrawOverlays(this)) "Overlay: ✓ Ready" else "Overlay: ✕ Required"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}

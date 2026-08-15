package com.refinepilot.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.refinepilot.app.databinding.ActivityActivationBinding
import java.util.concurrent.Executors

class ActivationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityActivationBinding
    private lateinit var store: SecureLicenseStore
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SecureLicenseStore(this)

        if (!BuildConfig.LICENSE_ENFORCEMENT_ENABLED) {
            openMain()
            return
        }

        binding.btnActivate.setOnClickListener { activate() }
        verifyExistingOrShowActivation()
    }

    private fun verifyExistingOrShowActivation() {
        val cache = store.load()
        if (cache == null) {
            showActivation("Enter your activation key to continue.")
            return
        }

        showBusy("Verifying RefinePilot license…")
        executor.execute {
            val result = LicenseApi.verify(cache.installationId, cache.installationToken)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    result.ok && result.status == "active" -> {
                        store.refreshVerification(
                            status = result.status,
                            plan = result.plan,
                            expiresAt = result.expiresAt,
                            serverTimeMs = result.serverTimeMs,
                            graceSeconds = result.graceSeconds
                        )
                        openMain()
                    }
                    result.code == "network_error" && store.isOfflineGraceValid() -> openMain()
                    result.code == "network_error" -> showActivation("Unable to verify. Connect to the internet to verify your RefinePilot license.")
                    result.code == "device_limit" -> showActivation("Device Limit Reached. This license is already registered to another device.")
                    result.code == "expired" -> showActivation("License Expired. Your RefinePilot license has expired.")
                    result.code == "revoked" || result.code == "suspended" -> {
                        store.clearAuthorization()
                        showActivation("License Disabled. This RefinePilot license is no longer active. Please contact support.")
                    }
                    else -> {
                        store.clearAuthorization()
                        showActivation(result.message.ifBlank { "Unable to verify RefinePilot license." })
                    }
                }
            }
        }
    }

    private fun activate() {
        val key = binding.inputLicense.text?.toString()?.trim().orEmpty()
        if (!LICENSE_PATTERN.matches(key.uppercase())) {
            showActivation("Invalid activation key format. Example: RP-A7K9-X2QM-84PL")
            return
        }

        showBusy("Activating RefinePilot…")
        val installationId = store.getOrCreateInstallationId()
        executor.execute {
            val result = LicenseApi.activate(key, installationId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.ok && !result.installationToken.isNullOrBlank()) {
                    store.saveActivation(
                        installationToken = result.installationToken,
                        plan = result.plan,
                        status = result.status,
                        expiresAt = result.expiresAt,
                        serverTimeMs = result.serverTimeMs,
                        graceSeconds = result.graceSeconds
                    )
                    binding.txtStatus.text = "✓ RefinePilot Activated\nLicense: ${result.plan}\nDevice: Registered"
                    binding.txtStatus.visibility = View.VISIBLE
                    binding.root.postDelayed({ openMain() }, 650)
                } else {
                    val message = when (result.code) {
                        "device_limit" -> "Device Limit Reached. This license is already activated on another device."
                        "expired" -> "License Expired. Your RefinePilot license has expired."
                        "revoked", "suspended" -> "License Disabled. Please contact support."
                        "invalid_license", "invalid_request" -> "Invalid Activation Key. Check your key and try again."
                        "rate_limited" -> "Too many activation attempts. Please wait and try again."
                        "network_error" -> "Unable to Verify. Connect to the internet and try again."
                        else -> result.message
                    }
                    showActivation(message)
                }
            }
        }
    }

    private fun showBusy(message: String) {
        binding.progress.visibility = View.VISIBLE
        binding.inputLicense.isEnabled = false
        binding.btnActivate.isEnabled = false
        binding.txtStatus.visibility = View.VISIBLE
        binding.txtStatus.text = message
    }

    private fun showActivation(message: String) {
        binding.progress.visibility = View.GONE
        binding.inputLicense.isEnabled = true
        binding.btnActivate.isEnabled = true
        binding.txtStatus.visibility = View.VISIBLE
        binding.txtStatus.text = message
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private val LICENSE_PATTERN = Regex("^RP-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
    }
}

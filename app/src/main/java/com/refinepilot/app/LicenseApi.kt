package com.refinepilot.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object LicenseApi {
    data class Result(
        val ok: Boolean,
        val code: String,
        val message: String,
        val installationToken: String? = null,
        val plan: String = "Unknown",
        val status: String = "unknown",
        val expiresAt: String? = null,
        val serverTimeMs: Long = 0L,
        val graceSeconds: Long = SecureLicenseStore.DEFAULT_GRACE_SECONDS
    )

    fun activate(licenseKey: String, installationId: String): Result = post(
        "activate",
        JSONObject()
            .put("license_key", licenseKey.trim().uppercase())
            .put("installation_id", installationId)
    )

    fun verify(installationId: String, installationToken: String): Result = post(
        "verify",
        JSONObject()
            .put("installation_id", installationId)
            .put("installation_token", installationToken)
    )

    private fun post(path: String, body: JSONObject): Result {
        val base = BuildConfig.LICENSE_API_BASE_URL.trim().trimEnd('/')
        if (base.isBlank()) return Result(false, "not_configured", "License server is not configured.")

        val connection = (URL("$base/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            Result(
                ok = connection.responseCode in 200..299 && json.optBoolean("ok", false),
                code = json.optString("code", if (connection.responseCode in 200..299) "unknown" else "server_error"),
                message = json.optString("message", "Unable to verify RefinePilot license."),
                installationToken = json.optString("installation_token").takeIf { it.isNotBlank() },
                plan = json.optString("plan", "Unknown"),
                status = json.optString("status", "unknown"),
                expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
                serverTimeMs = json.optLong("server_time_ms", System.currentTimeMillis()),
                graceSeconds = json.optLong("grace_seconds", SecureLicenseStore.DEFAULT_GRACE_SECONDS)
            )
        } catch (_: Exception) {
            Result(false, "network_error", "Unable to contact the RefinePilot license server.")
        } finally {
            connection.disconnect()
        }
    }
}

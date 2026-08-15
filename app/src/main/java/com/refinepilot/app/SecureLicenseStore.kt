package com.refinepilot.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureLicenseStore(private val context: Context) {
    data class Cache(
        val installationId: String,
        val installationToken: String,
        val plan: String,
        val status: String,
        val expiresAt: String?,
        val verifiedServerMs: Long,
        val verifiedElapsedMs: Long,
        val bootCount: Int,
        val graceSeconds: Long
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreateInstallationId(): String {
        val current = readEncrypted(INSTALL_ID_KEY)
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString()
        writeEncrypted(INSTALL_ID_KEY, generated)
        return generated
    }

    fun saveActivation(
        installationToken: String,
        plan: String,
        status: String,
        expiresAt: String?,
        serverTimeMs: Long,
        graceSeconds: Long
    ) {
        val payload = JSONObject()
            .put("installationId", getOrCreateInstallationId())
            .put("installationToken", installationToken)
            .put("plan", plan)
            .put("status", status)
            .put("expiresAt", expiresAt ?: JSONObject.NULL)
            .put("verifiedServerMs", serverTimeMs)
            .put("verifiedElapsedMs", SystemClock.elapsedRealtime())
            .put("bootCount", currentBootCount())
            .put("graceSeconds", graceSeconds)
        writeEncrypted(CACHE_KEY, payload.toString())
    }

    fun refreshVerification(status: String, plan: String, expiresAt: String?, serverTimeMs: Long, graceSeconds: Long) {
        val current = load() ?: return
        saveActivation(
            installationToken = current.installationToken,
            plan = plan,
            status = status,
            expiresAt = expiresAt,
            serverTimeMs = serverTimeMs,
            graceSeconds = graceSeconds
        )
    }

    fun load(): Cache? {
        val raw = readEncrypted(CACHE_KEY) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Cache(
                installationId = json.getString("installationId"),
                installationToken = json.getString("installationToken"),
                plan = json.optString("plan", "Unknown"),
                status = json.optString("status", "active"),
                expiresAt = json.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" },
                verifiedServerMs = json.optLong("verifiedServerMs", 0L),
                verifiedElapsedMs = json.optLong("verifiedElapsedMs", 0L),
                bootCount = json.optInt("bootCount", -1),
                graceSeconds = json.optLong("graceSeconds", DEFAULT_GRACE_SECONDS)
            )
        }.getOrNull()
    }

    fun isOfflineGraceValid(): Boolean {
        val cache = load() ?: return false
        if (cache.status != "active") return false
        val boot = currentBootCount()
        if (boot < 0 || cache.bootCount < 0 || boot != cache.bootCount) return false
        val elapsed = SystemClock.elapsedRealtime() - cache.verifiedElapsedMs
        return elapsed >= 0 && elapsed <= cache.graceSeconds * 1000L
    }

    fun clearAuthorization() {
        prefs.edit().remove(CACHE_KEY).apply()
    }

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    private fun writeEncrypted(key: String, plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val blob = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, blob, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, blob, cipher.iv.size, encrypted.size)
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun readEncrypted(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size <= IV_BYTES) return null
            val iv = blob.copyOfRange(0, IV_BYTES)
            val encrypted = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "refinepilot_secure_license"
        private const val CACHE_KEY = "license_cache"
        private const val INSTALL_ID_KEY = "installation_id"
        private const val KEY_ALIAS = "refinepilot_license_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        const val DEFAULT_GRACE_SECONDS = 72L * 60L * 60L
    }
}

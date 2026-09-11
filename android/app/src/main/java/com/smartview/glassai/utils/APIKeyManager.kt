package com.smartview.glassai.utils

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smartview.glassai.managers.AlibabaEndpoint
import com.smartview.glassai.managers.APIProvider
import com.smartview.glassai.managers.APIProviderManager

/**
 * API Key Manager
 * Secure storage and retrieval of API keys using EncryptedSharedPreferences
 * Supports multiple API providers (Alibaba Dashscope Beijing/Singapore, OpenRouter, Google)
 * 1:1 port from iOS APIKeyManager.swift
 */
class APIKeyManager(context: Context) {

    companion object {
        private const val TAG = "APIKeyManager"
        private const val PREFS_NAME = "turbometa_secure_prefs"

        // Account names for different providers
        private const val KEY_ALIBABA_BEIJING = "alibaba-beijing-api-key"
        private const val KEY_ALIBABA_SINGAPORE = "alibaba-singapore-api-key"
        private const val KEY_OPENROUTER = "openrouter-api-key"
        private const val KEY_GOOGLE = "google-api-key"
        private const val KEY_LEGACY = "qwen_api_key" // For backward compatibility

        // Settings keys
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_OUTPUT_LANGUAGE = "output_language"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_RTMP_URL = "rtmp_url"
        private const val KEY_RTMP_STREAM_KEY = "rtmp_stream_key"
        private const val KEY_RTMP_BITRATE = "rtmp_bitrate"
        private const val KEY_RTMP_SPLIT_MIGRATED = "rtmp_split_migrated_v2"
        const val DEFAULT_RTMP_BITRATE = 2_000_000

        // OpenClaw (Phase B). Non-secret settings live next to rtmp_url; the token and the
        // Ed25519 seed need the encrypted store (Android Keystore has no Ed25519).
        private const val KEY_OPENCLAW_HOST = "openclaw_host"
        private const val KEY_OPENCLAW_PORT = "openclaw_port"
        private const val KEY_OPENCLAW_SCHEME = "openclaw_scheme"
        private const val KEY_OPENCLAW_TOKEN = "openclaw_gateway_token"
        private const val KEY_OPENCLAW_DEVICE_SEED = "openclaw_ed25519_seed"

        @Volatile
        private var instance: APIKeyManager? = null

        fun getInstance(context: Context): APIKeyManager {
            return instance ?: synchronized(this) {
                instance ?: APIKeyManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        migrateLegacyKey()
        migrateRtmpUrl()
    }

    // MARK: - Migration

    private fun migrateLegacyKey() {
        try {
            // Migrate old qwen key to new Alibaba Beijing format
            val legacyKey = sharedPreferences.getString(KEY_LEGACY, null)
            if (!legacyKey.isNullOrBlank() && sharedPreferences.getString(KEY_ALIBABA_BEIJING, null).isNullOrBlank()) {
                sharedPreferences.edit()
                    .putString(KEY_ALIBABA_BEIJING, legacyKey)
                    .remove(KEY_LEGACY)
                    .apply()
                Log.i(TAG, "Migrated legacy qwen API key to Alibaba Beijing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration error: ${e.message}")
        }
    }

    /**
     * 2.0.0: the single rtmp_url used to embed the stream key. Split it once into rtmp_url
     * (server) + rtmp_stream_key so the key is never rendered on screen.
     */
    private fun migrateRtmpUrl() {
        try {
            if (sharedPreferences.getBoolean(KEY_RTMP_SPLIT_MIGRATED, false)) return
            val full = sharedPreferences.getString(KEY_RTMP_URL, null)
            val editor = sharedPreferences.edit().putBoolean(KEY_RTMP_SPLIT_MIGRATED, true)
            if (!full.isNullOrBlank() && sharedPreferences.getString(KEY_RTMP_STREAM_KEY, null).isNullOrBlank()) {
                val (server, key) = RtmpUrlSplitter.split(full)
                editor.putString(KEY_RTMP_URL, server)
                if (key.isNotEmpty()) editor.putString(KEY_RTMP_STREAM_KEY, key)
                Log.i(TAG, "Migrated rtmp_url into server URL + stream key")
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "RTMP migration error: ${e.message}")
        }
    }

    /**
     * Instrumented-test hook (Task 9): forgets that the split ran and runs it again, so the
     * 1.5.0 -> 2.0.0 upgrade path can be exercised on a device without reinstalling.
     */
    @VisibleForTesting
    internal fun rerunRtmpMigrationForTests() {
        sharedPreferences.edit().remove(KEY_RTMP_SPLIT_MIGRATED).apply()
        migrateRtmpUrl()
    }

    // MARK: - Provider-specific API Key Management

    fun saveAPIKey(key: String, provider: APIProvider, endpoint: AlibabaEndpoint? = null): Boolean {
        return try {
            if (key.isBlank()) return false
            val accountKey = accountName(provider, endpoint)
            sharedPreferences.edit().putString(accountKey, key).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key: ${e.message}")
            false
        }
    }

    fun getAPIKey(provider: APIProvider, endpoint: AlibabaEndpoint? = null): String? {
        return try {
            val accountKey = accountName(provider, endpoint)
            sharedPreferences.getString(accountKey, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key: ${e.message}")
            null
        }
    }

    fun deleteAPIKey(provider: APIProvider, endpoint: AlibabaEndpoint? = null): Boolean {
        return try {
            val accountKey = accountName(provider, endpoint)
            sharedPreferences.edit().remove(accountKey).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API key: ${e.message}")
            false
        }
    }

    fun hasAPIKey(provider: APIProvider, endpoint: AlibabaEndpoint? = null): Boolean {
        return !getAPIKey(provider, endpoint).isNullOrBlank()
    }

    // MARK: - Google API Key (for Live AI)

    fun saveGoogleAPIKey(key: String): Boolean {
        return try {
            if (key.isBlank()) return false
            sharedPreferences.edit().putString(KEY_GOOGLE, key).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Google API key: ${e.message}")
            false
        }
    }

    fun getGoogleAPIKey(): String? {
        return try {
            sharedPreferences.getString(KEY_GOOGLE, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Google API key: ${e.message}")
            null
        }
    }

    fun deleteGoogleAPIKey(): Boolean {
        return try {
            sharedPreferences.edit().remove(KEY_GOOGLE).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Google API key: ${e.message}")
            false
        }
    }

    fun hasGoogleAPIKey(): Boolean {
        return !getGoogleAPIKey().isNullOrBlank()
    }

    // MARK: - Backward Compatible Methods (defaults to current provider)

    fun saveAPIKey(key: String): Boolean {
        return saveAPIKey(key, APIProviderManager.staticCurrentProvider, APIProviderManager.staticAlibabaEndpoint)
    }

    fun getAPIKey(): String? {
        return getAPIKey(APIProviderManager.staticCurrentProvider, APIProviderManager.staticAlibabaEndpoint)
    }

    fun deleteAPIKey(): Boolean {
        return deleteAPIKey(APIProviderManager.staticCurrentProvider, APIProviderManager.staticAlibabaEndpoint)
    }

    fun hasAPIKey(): Boolean {
        return hasAPIKey(APIProviderManager.staticCurrentProvider, APIProviderManager.staticAlibabaEndpoint)
    }

    // MARK: - Private Helpers

    private fun accountName(provider: APIProvider, endpoint: AlibabaEndpoint?): String {
        return when (provider) {
            APIProvider.ALIBABA -> {
                val effectiveEndpoint = endpoint ?: APIProviderManager.staticAlibabaEndpoint
                when (effectiveEndpoint) {
                    AlibabaEndpoint.BEIJING -> KEY_ALIBABA_BEIJING
                    AlibabaEndpoint.SINGAPORE -> KEY_ALIBABA_SINGAPORE
                }
            }
            APIProvider.OPENROUTER -> KEY_OPENROUTER
        }
    }

    // MARK: - Settings (non-sensitive data)

    // AI Model
    fun saveAIModel(model: String) {
        sharedPreferences.edit().putString(KEY_AI_MODEL, model).apply()
    }

    fun getAIModel(): String {
        return sharedPreferences.getString(KEY_AI_MODEL, "qwen3-omni-flash-realtime") ?: "qwen3-omni-flash-realtime"
    }

    // Output Language
    fun saveOutputLanguage(language: String) {
        sharedPreferences.edit().putString(KEY_OUTPUT_LANGUAGE, language).apply()
    }

    fun getOutputLanguage(): String {
        return sharedPreferences.getString(KEY_OUTPUT_LANGUAGE, "zh-CN") ?: "zh-CN"
    }

    // Video Quality
    fun saveVideoQuality(quality: String) {
        sharedPreferences.edit().putString(KEY_VIDEO_QUALITY, quality).apply()
    }

    fun getVideoQuality(): String {
        return sharedPreferences.getString(KEY_VIDEO_QUALITY, "MEDIUM") ?: "MEDIUM"
    }

    // RTMP URL
    fun saveRtmpUrl(url: String) {
        sharedPreferences.edit().putString(KEY_RTMP_URL, url).apply()
    }

    fun getRtmpUrl(): String? {
        return sharedPreferences.getString(KEY_RTMP_URL, null)
    }

    // RTMP stream key (secret; encrypted like API keys) and persisted bitrate
    fun getRtmpStreamKey(): String? = try {
        sharedPreferences.getString(KEY_RTMP_STREAM_KEY, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read RTMP stream key: ${e.message}")
        null
    }

    fun saveRtmpStreamKey(key: String) {
        try {
            if (key.isBlank()) deleteRtmpStreamKey() else sharedPreferences.edit().putString(KEY_RTMP_STREAM_KEY, key.trim()).apply()
        } catch (e: Exception) {
            // Never log the key itself, only the failure.
            Log.e(TAG, "Failed to save RTMP stream key: ${e.message}")
        }
    }

    fun deleteRtmpStreamKey() {
        try {
            sharedPreferences.edit().remove(KEY_RTMP_STREAM_KEY).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete RTMP stream key: ${e.message}")
        }
    }

    fun getRtmpBitrate(): Int = try {
        val value = sharedPreferences.getInt(KEY_RTMP_BITRATE, 0)
        if (value > 0) value else DEFAULT_RTMP_BITRATE
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read RTMP bitrate: ${e.message}")
        DEFAULT_RTMP_BITRATE
    }

    fun saveRtmpBitrate(bitrate: Int) {
        try {
            sharedPreferences.edit().putInt(KEY_RTMP_BITRATE, bitrate).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RTMP bitrate: ${e.message}")
        }
    }

    // MARK: - OpenClaw (Phase B)

    fun getOpenClawHost(): String =
        sharedPreferences.getString(KEY_OPENCLAW_HOST, null)?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    fun saveOpenClawHost(host: String) {
        sharedPreferences.edit().putString(KEY_OPENCLAW_HOST, host.trim()).apply()
    }

    fun getOpenClawPort(): Int {
        val port = sharedPreferences.getInt(KEY_OPENCLAW_PORT, 0)
        return if (port in 1..65535) port else 18789
    }

    fun saveOpenClawPort(port: Int) {
        sharedPreferences.edit().putInt(KEY_OPENCLAW_PORT, port).apply()
    }

    fun getOpenClawScheme(): String =
        if (sharedPreferences.getString(KEY_OPENCLAW_SCHEME, null) == "wss") "wss" else "ws"

    fun saveOpenClawScheme(scheme: String) {
        sharedPreferences.edit().putString(KEY_OPENCLAW_SCHEME, if (scheme == "wss") "wss" else "ws").apply()
    }

    fun getOpenClawToken(): String? = try {
        sharedPreferences.getString(KEY_OPENCLAW_TOKEN, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read OpenClaw token: ${e.message}")
        null
    }

    fun saveOpenClawToken(token: String) {
        sharedPreferences.edit().putString(KEY_OPENCLAW_TOKEN, token.trim()).apply()
    }

    fun deleteOpenClawToken() {
        sharedPreferences.edit().remove(KEY_OPENCLAW_TOKEN).apply()
    }

    fun isGlassesDisplayEnabled(): Boolean =
        sharedPreferences.getBoolean("glasses_display_enabled", true)

    fun setGlassesDisplayEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("glasses_display_enabled", enabled).apply()
    }

    /** The 32-byte Ed25519 seed, stored as standard base64. */
    fun getOpenClawDeviceSeed(): ByteArray? = try {
        sharedPreferences.getString(KEY_OPENCLAW_DEVICE_SEED, null)
            ?.let { java.util.Base64.getDecoder().decode(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read OpenClaw device seed: ${e.message}")
        null
    }

    fun saveOpenClawDeviceSeed(seed: ByteArray) {
        sharedPreferences.edit()
            .putString(KEY_OPENCLAW_DEVICE_SEED, java.util.Base64.getEncoder().encodeToString(seed))
            .apply()
    }
}

// Available AI models for Live AI
enum class AIModel(val id: String, val displayName: String) {
    // Alibaba Qwen Omni
    QWEN_FLASH_REALTIME("qwen3-omni-flash-realtime", "Qwen3 Omni Flash (Realtime)"),
    QWEN_STANDARD_REALTIME("qwen3-omni-standard-realtime", "Qwen3 Omni Standard (Realtime)"),
    // Google Gemini
    GEMINI_FLASH("gemini-2.0-flash-exp", "Gemini 2.0 Flash")
}

// Available output languages
enum class OutputLanguage(val code: String, val displayName: String, val nativeName: String) {
    CHINESE("zh-CN", "Chinese", "\u4e2d\u6587"),
    ENGLISH("en-US", "English", "English"),
    JAPANESE("ja-JP", "Japanese", "\u65e5\u672c\u8a9e"),
    KOREAN("ko-KR", "Korean", "\ud55c\uad6d\uc5b4"),
    SPANISH("es-ES", "Spanish", "Espa\u00f1ol"),
    FRENCH("fr-FR", "French", "Fran\u00e7ais")
}

// Video quality options
enum class StreamQuality(val id: String, val displayNameResId: Int, val descriptionResId: Int) {
    LOW("LOW", com.smartview.glassai.R.string.quality_low, com.smartview.glassai.R.string.quality_low_desc),
    MEDIUM("MEDIUM", com.smartview.glassai.R.string.quality_medium, com.smartview.glassai.R.string.quality_medium_desc),
    HIGH("HIGH", com.smartview.glassai.R.string.quality_high, com.smartview.glassai.R.string.quality_high_desc)
}

package com.smartview.glassai.services.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class AssistantSettingsError { INVALID_CONFIG, STORAGE }

/** Dedicated encrypted preferences. No conversation, photos or notification contents are stored. */
class AssistantSettingsStore(context: Context, prefsName: String = "assistant_credentials") {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        prefsName,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): AssistantConfig = AssistantConfig(
        endpoint = prefs.getString("endpoint", "").orEmpty(),
        model = prefs.getString("model", "").orEmpty(),
        apiKey = prefs.getString("api_key", "").orEmpty(),
        supportsImages = prefs.getBoolean("supports_images", false),
        supportsTools = prefs.getBoolean("supports_tools", true),
        speakReplies = prefs.getBoolean("speak_replies", true),
        allowInsecureHttp = prefs.getBoolean("allow_insecure_http", false),
    )

    /** Explicit Save only. Validation happens before editing, including when a key is removed. */
    fun save(config: AssistantConfig): AssistantSettingsError? {
        val normalized = config.copy(endpoint = config.endpoint.trim(), model = config.model.trim(), apiKey = config.apiKey.trim())
        try {
            normalized.validate()
        } catch (_: IllegalArgumentException) {
            return AssistantSettingsError.INVALID_CONFIG
        }
        return try {
            val editor = prefs.edit()
                .putString("endpoint", normalized.endpoint)
                .putString("model", normalized.model)
                .putBoolean("supports_images", normalized.supportsImages)
                .putBoolean("supports_tools", normalized.supportsTools)
                .putBoolean("speak_replies", normalized.speakReplies)
                .putBoolean("allow_insecure_http", normalized.allowInsecureHttp)
            if (normalized.apiKey.isBlank()) editor.remove("api_key") else editor.putString("api_key", normalized.apiKey)
            if (editor.commit()) null else AssistantSettingsError.STORAGE
        } catch (_: Exception) {
            AssistantSettingsError.STORAGE
        }
    }
}

package com.smartview.glassai.bridge

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LEGACY_MEDIA_PACKAGES = setOf("com.netease.cloudmusic", "com.luna.music")
val DEFAULT_MEDIA_PACKAGES = LEGACY_MEDIA_PACKAGES + "com.tencent.qqmusic"

internal fun migrateMediaPackages(saved: Set<String>?, customized: Boolean): Set<String> =
    if (saved == null || (!customized && saved == LEGACY_MEDIA_PACKAGES)) DEFAULT_MEDIA_PACKAGES
    else saved.toSet()

private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
private fun cleanPackages(value: Set<String>): Set<String> =
    value.map(String::trim).filter { packageNamePattern.matches(it) }.toSet()

data class BridgeSettings(
    val wechatEnabled: Boolean = false,
    val musicEnabled: Boolean = false,
    val mediaPackages: Set<String> = DEFAULT_MEDIA_PACKAGES,
    val aiNotificationsEnabled: Boolean = false,
    val aiNotificationPackages: Set<String> = emptySet(),
    /** Process-local consent generation; intentionally never read from or written to preferences. */
    val aiConsentRevision: Long = 0L,
)

/** Preferences contain switches/package names only. Notification contents are never persisted. */
class BridgePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("notification_bridge", Context.MODE_PRIVATE)
    private val mediaPackages = migrateMediaPackages(prefs.getStringSet("media_packages", null),
        prefs.getBoolean("media_packages_customized", false)).also { packages ->
        if (prefs.contains("media_packages") && prefs.getStringSet("media_packages", null) != packages) {
            prefs.edit().putStringSet("media_packages", packages).apply()
        }
    }
    private val mutable = MutableStateFlow(BridgeSettings(
        wechatEnabled = prefs.getBoolean("wechat_enabled", false),
        musicEnabled = prefs.getBoolean("music_enabled", false),
        mediaPackages = mediaPackages,
        aiNotificationsEnabled = prefs.getBoolean("ai_notifications_enabled", false),
        aiNotificationPackages = cleanPackages(prefs.getStringSet("ai_notification_packages", emptySet()).orEmpty()),
    ))
    val settings: StateFlow<BridgeSettings> = mutable.asStateFlow()

    fun setWechatEnabled(value: Boolean) {
        prefs.edit().putBoolean("wechat_enabled", value).apply()
        mutable.value = mutable.value.copy(wechatEnabled = value)
    }
    fun setMusicEnabled(value: Boolean) {
        prefs.edit().putBoolean("music_enabled", value).apply()
        mutable.value = mutable.value.copy(musicEnabled = value)
    }
    fun setMediaPackages(value: Set<String>) {
        val packages = cleanPackages(value)
        prefs.edit().putStringSet("media_packages", packages).putBoolean("media_packages_customized", true).apply()
        mutable.value = mutable.value.copy(mediaPackages = packages)
    }

    @MainThread
    fun setAiNotificationsEnabled(value: Boolean) {
        val current = mutable.value
        if (current.aiNotificationsEnabled == value) return
        val settings = current.copy(aiNotificationsEnabled = value, aiConsentRevision = current.aiConsentRevision + 1)
        // Synchronous eviction: a rapid off/on cannot be conflated away by a flow collector.
        NotificationBridgeRuntime.onAssistantSettingsChanged(settings)
        prefs.edit().putBoolean("ai_notifications_enabled", value).apply()
        mutable.value = settings
    }

    @MainThread
    fun setAiNotificationPackages(value: Set<String>) {
        val packages = cleanPackages(value)
        val current = mutable.value
        if (current.aiNotificationPackages == packages) return
        val settings = current.copy(aiNotificationPackages = packages, aiConsentRevision = current.aiConsentRevision + 1)
        NotificationBridgeRuntime.onAssistantSettingsChanged(settings)
        prefs.edit().putStringSet("ai_notification_packages", settings.aiNotificationPackages).apply()
        mutable.value = settings
    }
    companion object {
        @Volatile private var instance: BridgePreferences? = null
        fun getInstance(context: Context): BridgePreferences = instance ?: synchronized(this) {
            instance ?: BridgePreferences(context.applicationContext).also { instance = it }
        }
    }
}

package com.smartview.glassai.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val DEFAULT_MEDIA_PACKAGES = setOf("com.netease.cloudmusic", "com.luna.music")

data class BridgeSettings(
    val wechatEnabled: Boolean = false,
    val musicEnabled: Boolean = false,
    val mediaPackages: Set<String> = DEFAULT_MEDIA_PACKAGES,
)

/** Preferences contain switches/package names only. Notification contents are never persisted. */
class BridgePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("notification_bridge", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(BridgeSettings(
        prefs.getBoolean("wechat_enabled", false), prefs.getBoolean("music_enabled", false),
        prefs.getStringSet("media_packages", DEFAULT_MEDIA_PACKAGES)!!.toSet(),
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
        val packages = value.map(String::trim).filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }.toSet()
        prefs.edit().putStringSet("media_packages", packages).apply()
        mutable.value = mutable.value.copy(mediaPackages = packages)
    }
    companion object {
        @Volatile private var instance: BridgePreferences? = null
        fun getInstance(context: Context): BridgePreferences = instance ?: synchronized(this) {
            instance ?: BridgePreferences(context.applicationContext).also { instance = it }
        }
    }
}

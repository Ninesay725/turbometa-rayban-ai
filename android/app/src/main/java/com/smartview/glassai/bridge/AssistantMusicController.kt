package com.smartview.glassai.bridge

import androidx.annotation.MainThread

/** Separate request/result capability; the existing MusicController remains unchanged. */
interface AssistantMusicController {
    @MainThread
    fun controlForAssistant(action: String, packageName: String? = null): String
}

internal fun validateAssistantMusicRequest(action: String, packageName: String?, settings: BridgeSettings,
    hasAccess: Boolean, connected: Boolean) {
    check(action in setOf("play", "pause", "next", "previous")) {
        "Unsupported music action. Use play, pause, next, or previous."
    }
    check(settings.musicEnabled) { "Music control is disabled in bridge settings." }
    check(hasAccess) { "Grant Android notification access to control music." }
    check(connected) { "The notification bridge is disconnected. Reconnect notification access." }
    check(settings.mediaPackages.isNotEmpty()) { "Select an allowed music app in bridge settings." }
    check(packageName == null || packageName in settings.mediaPackages) {
        "The requested app is not allowed for music control."
    }
}

internal class AssistantMusicPermissionException : IllegalStateException(
    "Music control lost notification access. Grant access and reconnect the notification bridge.",
)

/** Keep only platform reads/dispatch inside this guard; never expose their messages or causes. */
private inline fun <T> assistantMusicPlatformCall(block: () -> T): T = try {
    block()
} catch (_: SecurityException) {
    throw AssistantMusicPermissionException()
} catch (_: RuntimeException) {
    throw IllegalStateException("The active music session is unavailable. Open the music app and try again.")
}

/** Fresh candidate provider also makes selection, capability checks and dispatch testable with fakes. */
internal fun <T> executeAssistantMusicControl(
    action: String, packageName: String?, settings: BridgeSettings, hasAccess: Boolean, connected: Boolean,
    candidates: () -> List<T>, snapshot: (T) -> MediaSnapshot,
    supports: (T, String) -> Boolean, send: (T, String) -> Unit,
): String {
    validateAssistantMusicRequest(action, packageName, settings, hasAccess, connected)
    val allowed = if (packageName == null) settings.mediaPackages else setOf(packageName)
    val target = assistantMusicPlatformCall { resolveMediaTarget(candidates(), allowed, snapshot) }
        ?: throw IllegalStateException("No active session is available for the requested music app.")
    check(assistantMusicPlatformCall { supports(target, action) }) {
        "The active music session does not support the requested action."
    }
    assistantMusicPlatformCall { send(target, action) }
    return "Music command sent."
}

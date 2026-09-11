package com.smartview.glassai.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A notification snapshot, never persisted or logged by the bridge. */
data class AssistantNotification(
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)

internal const val ASSISTANT_NOTIFICATION_TITLE_LIMIT = 256
internal const val ASSISTANT_NOTIFICATION_TEXT_LIMIT = 2_000

/** Main-confined; one current snapshot per key, with no historical replay. */
internal class AssistantNotificationInbox {
    private val mutable = MutableStateFlow<List<AssistantNotification>>(emptyList())
    val notifications: StateFlow<List<AssistantNotification>> = mutable.asStateFlow()
    private val mutableAccessEpoch = MutableStateFlow(0L)
    val accessEpoch: StateFlow<Long> = mutableAccessEpoch.asStateFlow()
    private var hadAccess = false
    private var wasConnected = false
    var deviceLocked: Boolean = true
        private set

    /** Event generation remains changed after loss/recovery even if state emissions conflate. */
    fun observeAccess(hasAccess: Boolean, connected: Boolean, locked: Boolean) {
        val lost = (hadAccess && !hasAccess) || (wasConnected && !connected) || (!deviceLocked && locked)
        hadAccess = hasAccess
        wasConnected = connected
        deviceLocked = locked
        if (lost) invalidateAccess()
    }

    fun onDeviceLocked() {
        deviceLocked = true
        invalidateAccess()
    }

    fun invalidateAccess() {
        clear()
        mutableAccessEpoch.value += 1
    }

    fun replace(key: String, value: AssistantNotification?, privacyRemoval: Boolean = false) {
        val wasReadable = mutable.value.any { it.notificationKey == key }
        val remaining = mutable.value.filterNot { it.notificationKey == key }
        val replacement = value?.takeIf { it.notificationKey == key }?.let {
            it.copy(title = it.title.take(ASSISTANT_NOTIFICATION_TITLE_LIMIT),
                text = it.text.take(ASSISTANT_NOTIFICATION_TEXT_LIMIT))
        }
        mutable.value = (listOfNotNull(replacement) + remaining)
            .sortedByDescending { it.timestamp }.take(20)
        if (privacyRemoval && wasReadable && replacement == null) mutableAccessEpoch.value += 1
    }

    /** Erase before parsing, but remember whether a privacy/unreadable rejection invalidates a copy. */
    fun replacePosted(key: String, read: () -> AssistantNotification?) {
        val wasReadable = mutable.value.any { it.notificationKey == key }
        val epoch = mutableAccessEpoch.value
        replace(key, null)
        val replacement = try { read()?.takeIf { it.notificationKey == key } }
            catch (_: RuntimeException) { null } // Never propagate an external content-bearing error.
        replace(key, replacement)
        if (wasReadable && replacement == null && mutableAccessEpoch.value == epoch) {
            mutableAccessEpoch.value += 1
        }
    }

    fun clear() { mutable.value = emptyList() }

    fun reconcile(settings: BridgeSettings, hasAccess: Boolean, connected: Boolean) {
        if (!settings.aiNotificationsEnabled || !hasAccess || !connected) clear()
        else mutable.value = mutable.value.filter { it.packageName in settings.aiNotificationPackages }
    }

    fun forAssistant(settings: BridgeSettings, hasAccess: Boolean, connected: Boolean,
        packageName: String? = null): List<AssistantNotification> {
        reconcile(settings, hasAccess, connected)
        check(settings.aiNotificationsEnabled) { "Assistant notification access is disabled." }
        check(hasAccess) { "Grant Android notification access to use assistant notifications." }
        check(connected) { "The notification bridge is disconnected. Reconnect notification access." }
        check(settings.aiNotificationPackages.isNotEmpty()) { "Select apps for assistant notification access." }
        check(packageName == null || packageName in settings.aiNotificationPackages) {
            "The requested app is not allowed for assistant notification access."
        }
        return mutable.value.filter { packageName == null || it.packageName == packageName }
    }
}

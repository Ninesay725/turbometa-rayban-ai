package com.smartview.glassai.bridge

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.smartview.glassai.R
import com.smartview.glassai.glasses.*
import com.smartview.glassai.services.NotificationBridgeService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Main-confined hub. Only the connected listener may publish platform data or execute controls. */
object NotificationBridgeRuntime : MusicController {
    private const val MUSIC_OWNER = "BridgeMusicPage"
    private const val WECHAT_OWNER = "BridgeWeChatPreview"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connected = MutableStateFlow(false)
    val listenerConnected = connected.asStateFlow()
    private val track = MutableStateFlow<MediaSnapshot?>(null)
    val music = track.asStateFlow()
    private val inbox = MutableStateFlow<List<BridgeMessage>>(emptyList())
    val messages = inbox.asStateFlow()
    private val assistantInbox = AssistantNotificationInbox()
    val assistantNotifications: StateFlow<List<AssistantNotification>> = assistantInbox.notifications
    /** RAM-only invalidation generation; callers compare equality, not an exact increment count. */
    val assistantAccessEpoch: StateFlow<Long> = assistantInbox.accessEpoch
    private val problem = MutableStateFlow<String?>(null)
    val error = problem.asStateFlow()
    private var application: Application? = null
    private var listener: MusicController? = null
    private var assistantController: AssistantMusicController? = null
    private var pageVisible = false
    private var musicClaimed = false
    private var startMusicJob: Job? = null
    private var notificationLease: NotificationDisplayLease? = null
    private var musicSink: GlassesDisplaySink? = null
    private var wechatSink: GlassesDisplaySink? = null
    private val musicOrigin = Any()
    private val wechatOrigin = Any()

    private fun sessions() = GlassesSessionManager.getInstance(checkNotNull(application))
    private fun display() = GlassesDisplayIntegration.displayManager(checkNotNull(application))
    private fun preferences() = BridgePreferences.getInstance(checkNotNull(application))
    private fun ready() = sessions().sessionState.value == DeviceSessionState.STARTED &&
        sessions().displayState.value == GlassesDisplayState.STARTED

    private fun initialize(app: Application) {
        checkMain()
        if (application != null) return
        application = app
        musicSink = display().ownedSink(musicOrigin)
        wechatSink = display().ownedSink(wechatOrigin)
        notificationLease = NotificationDisplayLease(scope, ::ready,
            { sessions().acquire(WECHAT_OWNER) }, { sessions().release(WECHAT_OWNER) },
            { wechatSink?.showStatus() })
        scope.launch {
            preferences().settings.collect { settings ->
                reconcileAssistantNotifications()
                if (!settings.wechatEnabled) { inbox.value = emptyList(); notificationLease?.retire() }
                if (!settings.musicEnabled) { track.value = null; musicSink?.showStatus() }
                updateMusicClaim()
            }
        }
        scope.launch {
            sessions().displayState.collect {
                if (!ready()) {
                    notificationLease?.retire()
                    musicSink?.showStatus() // No obsolete track queued for the next capability.
                } else showMusicIfAppropriate()
            }
        }
        scope.launch {
            display().currentCard.collect { card ->
                // Done and supersession end the temporary claim. Page changes remain WeChat.
                if (card !is DisplayCard.WeChat) notificationLease?.retire()
            }
        }
    }

    internal fun attach(app: Application, controller: MusicController) {
        initialize(app)
        if (listener != null) assistantInbox.invalidateAccess() else assistantInbox.clear()
        assistantInbox.observeAccess(hasAccess = hasNotificationAccess(app), connected = true, locked = isDeviceLocked(app))
        assistantController = controller as? AssistantMusicController
        listener = controller; connected.value = true; problem.value = null
        GlassesDisplayIntegration.router(app).registerMusic(this)
        updateMusicClaim()
    }

    internal fun detach(controller: MusicController) {
        checkMain()
        if (listener !== controller) return
        listener = null; connected.value = false
        assistantInbox.observeAccess(hasAccess = false, connected = false, locked = assistantInbox.deviceLocked)
        assistantController = null; assistantInbox.clear()
        inbox.value = emptyList(); track.value = null
        notificationLease?.retire(); musicSink?.showStatus()
        application?.let { GlassesDisplayIntegration.router(it).unregisterMusic(this) }
        updateMusicClaim()
    }

    internal fun reportError(controller: MusicController, message: String) {
        if (listener === controller) problem.value = message
    }

    internal fun publishMessages(controller: MusicController, value: List<BridgeMessage>, announce: Boolean) {
        checkMain()
        if (listener !== controller || !preferences().settings.value.wechatEnabled) return
        inbox.value = value.take(3)
        if (value.isEmpty()) { notificationLease?.retire(); return }
        // Removals may update an existing card, but must not surface an old card on a new session.
        if (!announce && display().currentCard.value !is DisplayCard.WeChat) return
        val newest = value.first()
        val text = value.joinToString("\n\n") { "${it.sender}\n${it.text}" }
        notificationLease?.show {
            wechatSink?.show(DisplayCard.WeChat(newest.sender, text, value.size, newest.timestamp))
        }
    }

    internal fun publishMusic(controller: MusicController, value: MediaSnapshot?) {
        checkMain()
        if (listener !== controller || !preferences().settings.value.musicEnabled) return
        if (value != null && value.packageName !in preferences().settings.value.mediaPackages) return
        track.value = value
        if (value == null) musicSink?.showStatus() else showMusicIfAppropriate()
    }

    internal fun onAssistantSettingsChanged(settings: BridgeSettings) {
        checkMain()
        // Called synchronously by setters, including before a collector can observe off/on.
        val access = reconcileAssistantNotifications()
        assistantInbox.reconcile(settings, access, connected.value)
    }

    /** Listener screen-off/keyguard event: invalidate copied work even with no cached notifications. */
    internal fun clearAssistantNotifications(controller: MusicController) {
        checkMain()
        if (listener === controller) assistantInbox.onDeviceLocked()
    }

    internal fun replaceAssistantNotification(controller: MusicController, key: String,
        value: AssistantNotification?, privacyRemoval: Boolean = false) {
        checkMain()
        if (listener !== controller) return
        assistantInbox.replace(key, authorizedAssistantNotification(controller, value), privacyRemoval)
    }

    internal fun readAssistantNotification(controller: MusicController, key: String,
        read: () -> AssistantNotification?) {
        checkMain()
        if (listener !== controller) return
        assistantInbox.replacePosted(key) { authorizedAssistantNotification(controller, read()) }
    }

    internal fun updateAssistantNotificationVisibility(controller: MusicController, key: String,
        visibilityOverride: Int?, deviceLocked: Boolean) {
        checkMain()
        if (visibilityOverride == null || AssistantNotificationParser.hiddenByPolicy(visibilityOverride, deviceLocked)) {
            // An absent ranking also occurs during ordinary removal; it alone is not privacy loss.
            replaceAssistantNotification(controller, key, null, privacyRemoval = visibilityOverride != null)
        }
    }

    private fun authorizedAssistantNotification(controller: MusicController,
        value: AssistantNotification?): AssistantNotification? {
        val wasLocked = assistantInbox.deviceLocked
        val access = reconcileAssistantNotifications()
        val settings = preferences().settings.value
        val permitted = listener === controller && connected.value && access && settings.aiNotificationsEnabled &&
            value != null && value.packageName in settings.aiNotificationPackages &&
            !(assistantInbox.deviceLocked && !wasLocked) // The device may lock between parsing and publication.
        return value.takeIf { permitted }
    }

    private fun reconcileAssistantNotifications(): Boolean {
        val app = application ?: run { assistantInbox.clear(); return false }
        val locked = isDeviceLocked(app)
        val access = hasNotificationAccess(app)
        assistantInbox.observeAccess(hasAccess = access, connected = connected.value, locked = locked)
        assistantInbox.reconcile(preferences().settings.value, access, connected.value)
        return access
    }

    /** Read at request time; a previously collected list is not a continuing authorization. */
    @MainThread
    fun notificationsForAssistant(packageName: String? = null): List<AssistantNotification> {
        checkMain()
        val access = reconcileAssistantNotifications()
        val settings = application?.let { preferences().settings.value } ?: BridgeSettings()
        return assistantInbox.forAssistant(settings, access, connected.value, packageName)
    }

    @MainThread
    fun controlForAssistant(action: String, packageName: String? = null): String {
        checkMain()
        val app = application
        val access = reconcileAssistantNotifications()
        validateAssistantMusicRequest(action, packageName,
            app?.let { preferences().settings.value } ?: BridgeSettings(), access, connected.value)
        val controller = assistantController
            ?: throw IllegalStateException("Assistant music control is unavailable. Reconnect the notification bridge.")
        return controller.controlForAssistant(action, packageName)
    }

    private fun showMusicIfAppropriate() {
        val current = track.value ?: return
        if (!connected.value || !preferences().settings.value.musicEnabled || !ready()) return
        val card = display().currentCard.value
        if (card == null || card is DisplayCard.Status || card is DisplayCard.Music) {
            musicSink?.show(DisplayCard.Music(current.title, current.artist, current.isPlaying,
                current.packageName, current.artJpeg))
        }
    }

    fun enterMusicPage(app: Application) {
        initialize(app); pageVisible = true; updateMusicClaim(); showMusicIfAppropriate()
    }
    fun leaveMusicPage() {
        checkMain(); pageVisible = false; updateMusicClaim(); musicSink?.showStatus()
    }

    private fun updateMusicClaim() {
        val app = application ?: return
        val wanted = pageVisible && connected.value && preferences().settings.value.musicEnabled
        if (!wanted) {
            startMusicJob?.cancel(); startMusicJob = null
            if (musicClaimed) { musicClaimed = false; sessions().release(MUSIC_OWNER) }
            return
        }
        if (musicClaimed) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            problem.value = app.getString(R.string.bridge_session_unavailable); return
        }
        GlassesDisplayIntegration.ensureStarted(app)
        sessions().startMonitoring()
        musicClaimed = true
        startMusicJob = scope.launch {
            val result = sessions().acquireAndStart(MUSIC_OWNER, 12_000)
            if (result != SessionStartResult.STARTED) {
                problem.value = app.getString(R.string.bridge_session_unavailable)
                musicClaimed = false; sessions().release(MUSIC_OWNER)
            } else { problem.value = null; showMusicIfAppropriate() }
        }
    }

    override fun playPause() { checkMain(); if (preferences().settings.value.musicEnabled) listener?.playPause() }
    override fun next() { checkMain(); if (preferences().settings.value.musicEnabled) listener?.next() }
    override fun previous() { checkMain(); if (preferences().settings.value.musicEnabled) listener?.previous() }

    fun hasNotificationAccess(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(
            ComponentName(context, NotificationBridgeService::class.java)) == true
    } catch (_: RuntimeException) { false }

    internal fun isDeviceLocked(context: Context): Boolean = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        keyguard == null || power?.isInteractive != true || keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    } catch (_: RuntimeException) { true }

    fun openNotificationAccessSettings(context: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationBridgeService::class.java).flattenToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (detail.resolveActivity(context.packageManager) != null) context.startActivity(detail)
        else context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Notification bridge calls must run on the main thread." }
    }
}

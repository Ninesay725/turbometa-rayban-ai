package com.smartview.glassai.bridge

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
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
    private val problem = MutableStateFlow<String?>(null)
    val error = problem.asStateFlow()
    private var application: Application? = null
    private var listener: MusicController? = null
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
        listener = controller; connected.value = true; problem.value = null
        GlassesDisplayIntegration.router(app).registerMusic(this)
        updateMusicClaim()
    }

    internal fun detach(controller: MusicController) {
        checkMain()
        if (listener !== controller) return
        listener = null; connected.value = false
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

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun openNotificationAccessSettings(context: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationBridgeService::class.java).flattenToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (detail.resolveActivity(context.packageManager) != null) context.startActivity(detail)
        else context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

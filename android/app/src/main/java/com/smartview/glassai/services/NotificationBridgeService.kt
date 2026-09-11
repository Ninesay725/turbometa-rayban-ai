package com.smartview.glassai.services

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.smartview.glassai.R
import com.smartview.glassai.bridge.*
import com.smartview.glassai.glasses.MusicController
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Public notification/media-session bridge. No contents are stored or logged. */
class NotificationBridgeService : NotificationListenerService(), MusicController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val inbox = WeChatInbox()
    private val preferences by lazy { BridgePreferences.getInstance(this) }
    private val mediaManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val component by lazy { ComponentName(this, NotificationBridgeService::class.java) }
    private var connected = false
    private var mediaObserving = false
    private var settingsJob: Job? = null
    private var metadataJob: Job? = null
    private var generation = 0L
    private var selected: MediaController? = null
    private var artworkSource: Bitmap? = null
    private var artworkBytes: ByteArray? = null
    private var lastSettings: BridgeSettings? = null
    private data class Observed(
        val controller: MediaController, val callback: MediaController.Callback,
        var changedAt: Long,
    )
    private val controllers = linkedMapOf<MediaSession.Token, Observed>()
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        if (connected && preferences.settings.value.musicEnabled) safely { replaceControllers(list.orEmpty()) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        NotificationBridgeRuntime.attach(application, this)
        settingsJob?.cancel()
        settingsJob = scope.launch { preferences.settings.collect { applySettings(it) } }
    }

    private fun applySettings(settings: BridgeSettings) {
        if (!connected) return
        val previous = lastSettings
        lastSettings = settings
        if (!settings.wechatEnabled) {
            inbox.clear(); NotificationBridgeRuntime.publishMessages(this, emptyList(), false)
        }
        // Do not replay active historical notifications on a new grant/toggle. Only posted updates.
        if (!settings.musicEnabled) stopMedia()
        else if (!mediaObserving || previous?.mediaPackages != settings.mediaPackages) safely {
            if (!mediaObserving) {
                mediaManager.addOnActiveSessionsChangedListener(sessionListener, component, handler)
                mediaObserving = true
            }
            replaceControllers(mediaManager.getActiveSessions(component))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!connected || !preferences.settings.value.wechatEnabled || sbn.packageName != "com.tencent.mm") return
        // Same-key replacement is authoritative even when the new bundle contains no details.
        inbox.remove(sbn.key)
        val messages = runCatching { WeChatNotificationParser.parse(sbn) }.getOrDefault(emptyList())
        val replacement = messages.ifEmpty {
            listOf(BridgeMessage(sbn.key, "WeChat", getString(R.string.bridge_content_unavailable), sbn.postTime))
        }
        NotificationBridgeRuntime.publishMessages(this, inbox.update(replacement), true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!connected || !preferences.settings.value.wechatEnabled || sbn.packageName != "com.tencent.mm") return
        NotificationBridgeRuntime.publishMessages(this, inbox.remove(sbn.key), false)
    }

    private fun replaceControllers(list: List<MediaController>) {
        val allowed = preferences.settings.value.mediaPackages
        val wanted = list.filter { it.packageName in allowed }.associateBy { it.sessionToken }
        controllers.keys.filter { it !in wanted }.forEach { token ->
            controllers.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }
        wanted.forEach { (token, controller) ->
            if (token !in controllers) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        if (controllers[token]?.callback === this) refreshMedia()
                    }
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (controllers[token]?.callback !== this) return
                        controllers[token]?.changedAt = SystemClock.elapsedRealtime()
                        refreshMedia()
                    }
                    override fun onSessionDestroyed() {
                        if (controllers[token]?.callback !== this) return
                        controllers.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
                        refreshMedia()
                    }
                }
                controllers[token] = Observed(controller, callback, controller.playbackState?.lastPositionUpdateTime ?: 0)
                controller.registerCallback(callback, handler)
            }
        }
        refreshMedia()
    }

    private fun refreshMedia() {
        if (!connected || !preferences.settings.value.musicEnabled) return
        safely {
            val indexed = controllers.values.toList()
            val snapshots = indexed.mapIndexed { index, observed ->
                val state = observed.controller.playbackState
                val metadata = observed.controller.metadata
                val playing = state?.state == PlaybackState.STATE_PLAYING
                val actions = state?.actions ?: 0L
                MediaSnapshot(index.toString(), observed.controller.packageName,
                    metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                    metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), playing, observed.changedAt,
                    canPlayPause = supportsToggle(actions, playing),
                    canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                    canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            }
            val snapshot = selectMedia(snapshots, preferences.settings.value.mediaPackages)
            selected = snapshot?.let { indexed[it.id.toInt()].controller }
            metadataJob?.cancel()
            val attempt = ++generation
            val metadata = selected?.metadata
            // Only embedded bitmaps; URI artwork would require a content/network fetch.
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val cached = if (bitmap != null && bitmap === artworkSource) artworkBytes else null
            NotificationBridgeRuntime.publishMusic(this, snapshot?.copy(artJpeg = cached))
            if (cached != null) return@safely
            artworkSource = null; artworkBytes = null
            if (snapshot != null && bitmap != null) metadataJob = scope.launch {
                val bytes = withContext(Dispatchers.Default) { encodeArtwork(bitmap) }
                if (attempt == generation && connected && preferences.settings.value.musicEnabled) {
                    artworkSource = bitmap; artworkBytes = bytes
                    NotificationBridgeRuntime.publishMusic(this@NotificationBridgeService, snapshot.copy(artJpeg = bytes))
                }
            }
        }
    }

    override fun playPause() = control { controller, state ->
        val playing = state.state == PlaybackState.STATE_PLAYING
        if (supportsToggle(state.actions, playing)) {
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }
    }
    override fun next() = control { controller, state ->
        if (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) controller.transportControls.skipToNext()
    }
    override fun previous() = control { controller, state ->
        if (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) controller.transportControls.skipToPrevious()
    }
    private fun control(block: (MediaController, PlaybackState) -> Unit) {
        if (!connected || !preferences.settings.value.musicEnabled) return
        safely {
            val candidates = controllers.values.toList()
            val target = resolveMediaTarget(candidates, preferences.settings.value.mediaPackages) { observed ->
                val current = observed.controller.playbackState
                MediaSnapshot(candidates.indexOf(observed).toString(), observed.controller.packageName, "", "",
                    current?.state == PlaybackState.STATE_PLAYING, observed.changedAt)
            } ?: return@safely
            val controller = target.controller
            if (controller.packageName !in preferences.settings.value.mediaPackages) return@safely
            val state = controller.playbackState ?: return@safely
            block(controller, state)
        }
    }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (_: SecurityException) {
            NotificationBridgeRuntime.reportError(this, getString(R.string.bridge_access_lost))
            retire()
        }
    }

    private fun stopMedia() {
        ++generation; metadataJob?.cancel(); metadataJob = null; selected = null
        artworkSource = null; artworkBytes = null
        controllers.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        controllers.clear()
        if (mediaObserving) runCatching { mediaManager.removeOnActiveSessionsChangedListener(sessionListener) }
        mediaObserving = false
        NotificationBridgeRuntime.publishMusic(this, null)
    }

    private fun retire() {
        connected = false; settingsJob?.cancel(); settingsJob = null
        stopMedia(); inbox.clear(); lastSettings = null
        NotificationBridgeRuntime.detach(this)
    }
    override fun onListenerDisconnected() { retire(); super.onListenerDisconnected() }
    override fun onDestroy() { retire(); scope.cancel(); super.onDestroy() }

    companion object {
        internal fun supportsToggle(actions: Long, playing: Boolean): Boolean =
            actions and (PlaybackState.ACTION_PLAY_PAUSE or if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L

        internal fun encodeArtwork(bitmap: Bitmap): ByteArray? = runCatching {
            val scale = minOf(1f, 240f / maxOf(bitmap.width, bitmap.height))
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            try {
                ByteArrayOutputStream().use { out ->
                    if (scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)) out.toByteArray() else null
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        }.getOrNull()
    }
}

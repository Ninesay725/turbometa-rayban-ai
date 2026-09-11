package com.smartview.glassai.services

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.smartview.glassai.R
import com.smartview.glassai.bridge.*
import com.smartview.glassai.glasses.MusicController
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Public notification/media-session bridge. No contents are stored or logged. */
class NotificationBridgeService : NotificationListenerService(), MusicController, AssistantMusicController {
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
    private var privacyObserving = false
    private val accessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (connected && !NotificationBridgeRuntime.hasNotificationAccess(this@NotificationBridgeService)) retire()
        }
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Drop unlocked previews at screen-off; never replay them after unlock.
            if (intent.action == Intent.ACTION_SCREEN_OFF || NotificationBridgeRuntime.isDeviceLocked(context)) {
                NotificationBridgeRuntime.clearAssistantNotifications(this@NotificationBridgeService)
            }
        }
    }
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
        if (!privacyObserving) {
            // Mark first so a partially registered observer is also cleaned up on failure.
            privacyObserving = true
            try {
                contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_notification_listeners"),
                    false, accessObserver)
                ContextCompat.registerReceiver(this, lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: RuntimeException) {
                retire()
                return
            }
        }
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
        if (!connected) return
        if (!NotificationBridgeRuntime.hasNotificationAccess(this)) { retire(); return }
        val settings = preferences.settings.value
        if (settings.aiNotificationsEnabled && sbn.packageName in settings.aiNotificationPackages) {
            val locked = NotificationBridgeRuntime.isDeviceLocked(this)
            val visibility = assistantVisibilityOverride(sbn.key) ?: Notification.VISIBILITY_SECRET
            val notification = sbn.notification
            val visibleSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
                !AssistantNotificationParser.hiddenByPolicy(notification.visibility, locked) &&
                !AssistantNotificationParser.hiddenByPolicy(visibility, locked)
            if (visibleSummary) {
                NotificationBridgeRuntime.replaceAssistantNotification(this, sbn.key, null)
            } else {
                // Erase first, remembering prior readability until a guarded parse can reject it.
                NotificationBridgeRuntime.readAssistantNotification(this, sbn.key) {
                    AssistantNotificationParser.parse(sbn, settings.aiNotificationPackages, locked, visibility)
                }
            }
        }
        if (!settings.wechatEnabled || sbn.packageName != "com.tencent.mm") return
        // Same-key replacement is authoritative even when the new bundle contains no details.
        inbox.remove(sbn.key)
        val messages = runCatching { WeChatNotificationParser.parse(sbn) }.getOrDefault(emptyList())
        val replacement = messages.ifEmpty {
            listOf(BridgeMessage(sbn.key, "WeChat", getString(R.string.bridge_content_unavailable), sbn.postTime))
        }
        NotificationBridgeRuntime.publishMessages(this, inbox.update(replacement), true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationBridgeRuntime.replaceAssistantNotification(this, sbn.key, null)
        if (!connected || !preferences.settings.value.wechatEnabled || sbn.packageName != "com.tencent.mm") return
        NotificationBridgeRuntime.publishMessages(this, inbox.remove(sbn.key), false)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        if (!connected) return
        if (!NotificationBridgeRuntime.hasNotificationAccess(this)) { retire(); return }
        val locked = NotificationBridgeRuntime.isDeviceLocked(this)
        // Ranking changes include user/device policy redaction; never reread or replay contents.
        NotificationBridgeRuntime.assistantNotifications.value.forEach { notification ->
            val visibility = assistantVisibilityOverride(notification.notificationKey, rankingMap)
            NotificationBridgeRuntime.updateAssistantNotificationVisibility(this,
                notification.notificationKey, visibility, locked)
        }
    }

    private fun assistantVisibilityOverride(key: String, rankingMap: RankingMap? = null): Int? = try {
        val ranking = Ranking()
        if ((rankingMap ?: currentRanking)?.getRanking(key, ranking) == true) ranking.lockscreenVisibilityOverride
        else null
    } catch (_: RuntimeException) { Notification.VISIBILITY_SECRET }

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

    override fun controlForAssistant(action: String, packageName: String?): String {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Assistant music control must run on the main thread." }
        val access = NotificationBridgeRuntime.hasNotificationAccess(this)
        if (!access) retire()
        return try {
            val settings = preferences.settings.value
            executeAssistantMusicControl(action, packageName, settings, access, connected,
                candidates = {
                    // Query active sessions now: cached callbacks can lag behind token removal/replacement.
                    mediaManager.getActiveSessions(component).filter {
                        it.packageName in settings.mediaPackages && (packageName == null || it.packageName == packageName)
                    }
                },
                snapshot = { controller ->
                    val state = controller.playbackState
                    MediaSnapshot(controller.sessionToken.hashCode().toString(), controller.packageName, "", "",
                        state?.state == PlaybackState.STATE_PLAYING, state?.lastPositionUpdateTime ?: 0)
                },
                supports = { controller, name -> supportsAssistantAction(controller.playbackState?.actions ?: 0, name) },
                send = { controller, name ->
                    when (name) {
                        "play" -> controller.transportControls.play()
                        "pause" -> controller.transportControls.pause()
                        "next" -> controller.transportControls.skipToNext()
                        "previous" -> controller.transportControls.skipToPrevious()
                    }
                },
            )
        } catch (error: AssistantMusicPermissionException) {
            retire()
            throw error
        }
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
        // Notify in-flight assistant work before controller/receiver cleanup can fail or reenter.
        NotificationBridgeRuntime.detach(this)
        if (privacyObserving) {
            privacyObserving = false
            runCatching { contentResolver.unregisterContentObserver(accessObserver) }
            runCatching { unregisterReceiver(lockReceiver) }
        }
        stopMedia(); inbox.clear(); lastSettings = null
    }
    override fun onListenerDisconnected() { retire(); super.onListenerDisconnected() }
    override fun onDestroy() { retire(); scope.cancel(); super.onDestroy() }

    companion object {
        internal fun supportsAssistantAction(actions: Long, action: String): Boolean =
            actions and when (action) {
                "play" -> PlaybackState.ACTION_PLAY
                "pause" -> PlaybackState.ACTION_PAUSE
                "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
                "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                else -> 0L
            } != 0L

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

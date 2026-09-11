package com.smartview.glassai.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.services.NotificationBridgeService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaBridgeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext

    @Test fun defaultsAreOffAndSettingsPersistWithoutNotificationContents() {
        val name = "bridge_test_${java.util.UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(name, mode)
        }
        try {
            val preferences = BridgePreferences(isolated)
            assertFalse(preferences.settings.value.wechatEnabled)
            assertFalse(preferences.settings.value.musicEnabled)
            preferences.setMusicEnabled(true)
            preferences.setMediaPackages(setOf("com.example.music", "bad package"))
            val restored = BridgePreferences(isolated).settings.value
            assertTrue(restored.musicEnabled)
            assertFalse(restored.wechatEnabled)
            assertEquals(setOf("com.example.music"), restored.mediaPackages)
            assertEquals(setOf("music_enabled", "media_packages", "media_packages_customized"),
                context.getSharedPreferences(name, 0).all.keys)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun artworkIsJpegAndBoundedTo240Pixels() {
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        try {
            val bytes = NotificationBridgeService.encodeArtwork(source)!!
            assertEquals(0xff, bytes[0].toInt() and 255)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(240, decoded.width); assertEquals(120, decoded.height)
            assertFalse(source.isRecycled)
            decoded.recycle()
        } finally { source.recycle() }
    }

    /** Requires a grant to this test-installed listener; runner restores it afterwards. */
    @Test fun enabledListenerReadsAndControlsATestOwnedMediaSession(): Unit = runBlocking {
        assumeTrue("Notification listener access must be granted by the emulator runner", NotificationBridgeRuntime.hasNotificationAccess(context))
        val preferences = BridgePreferences.getInstance(context)
        val saved = preferences.settings.value
        val play = CountDownLatch(1)
        val pause = CountDownLatch(2); val next = CountDownLatch(2); val previous = CountDownLatch(2)
        val session = MediaSession(context, "phase-e-test-only")
        try {
            instrumentation.runOnMainSync {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationBridgeService::class.java))
                session.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { play.countDown() }
                    override fun onPause() { pause.countDown() }
                    override fun onSkipToNext() { next.countDown() }
                    override fun onSkipToPrevious() { previous.countDown() }
                }, Handler(Looper.getMainLooper()))
                session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Bridge fixture")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test artist").build())
                session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                session.isActive = true
                preferences.setMediaPackages(setOf(context.packageName)); preferences.setMusicEnabled(true)
            }
            withTimeout(15_000) { NotificationBridgeRuntime.listenerConnected.first { it } }
            val track = withTimeout(15_000) { NotificationBridgeRuntime.music.first { it?.title == "Bridge fixture" } }!!
            assertEquals(context.packageName, track.packageName); assertTrue(track.isPlaying)
            instrumentation.runOnMainSync {
                NotificationBridgeRuntime.playPause(); NotificationBridgeRuntime.next(); NotificationBridgeRuntime.previous()
                listOf("play", "pause", "next", "previous").forEach { action ->
                    assertEquals("Music command sent.", NotificationBridgeRuntime.controlForAssistant(action, context.packageName))
                }
                assertThrows(IllegalStateException::class.java) {
                    NotificationBridgeRuntime.controlForAssistant("play", "not.allowed")
                }
            }
            assertTrue(play.await(3, TimeUnit.SECONDS))
            assertTrue(pause.await(3, TimeUnit.SECONDS))
            assertTrue(next.await(3, TimeUnit.SECONDS))
            assertTrue(previous.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                assertThrows(IllegalStateException::class.java) {
                    NotificationBridgeRuntime.controlForAssistant("next", context.packageName)
                }
                preferences.setMusicEnabled(false)
                assertThrows(IllegalStateException::class.java) { NotificationBridgeRuntime.controlForAssistant("play") }
            }
            withTimeout(3_000) { NotificationBridgeRuntime.music.first { it == null } }
        } finally {
            instrumentation.runOnMainSync {
                session.release()
                preferences.setMusicEnabled(saved.musicEnabled)
                preferences.setWechatEnabled(saved.wechatEnabled)
                preferences.setMediaPackages(saved.mediaPackages)
            }
            // Instrumentation can terminate before asynchronous apply writes drain.
            assertTrue(context.getSharedPreferences("notification_bridge", 0).edit().commit())
        }
    }
}

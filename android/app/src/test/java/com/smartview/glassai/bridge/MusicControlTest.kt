package com.smartview.glassai.bridge

import android.media.session.PlaybackState
import com.smartview.glassai.glasses.*
import com.smartview.glassai.services.NotificationBridgeService
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

class MusicControlTest {
    @Test fun toggleRequiresTheActionThatMatchesTheCurrentPlaybackState() {
        assertTrue(NotificationBridgeService.supportsToggle(PlaybackState.ACTION_PAUSE, true))
        assertFalse(NotificationBridgeService.supportsToggle(PlaybackState.ACTION_PLAY, true))
        assertTrue(NotificationBridgeService.supportsToggle(PlaybackState.ACTION_PLAY, false))
        assertFalse(NotificationBridgeService.supportsToggle(0, false))
        assertTrue(NotificationBridgeService.supportsToggle(PlaybackState.ACTION_PLAY_PAUSE, true))
    }

    @Test fun staleUnregisterCannotRemoveTheNewMusicController() {
        val sink = object : GlassesDisplaySink {
            override fun show(card: DisplayCard) {}
            override fun showStatus() {}
            override fun clear() {}
        }
        val router = GlassesActionRouter(sink, MutableSharedFlow()) {}
        val calls = mutableListOf<String>()
        fun controller(name: String) = object : MusicController {
            override fun playPause() { calls += "$name:toggle" }
            override fun next() { calls += "$name:next" }
            override fun previous() { calls += "$name:prev" }
        }
        val first = controller("old"); val second = controller("new")
        router.registerMusic(first); router.registerMusic(second); router.unregisterMusic(first)
        router.dispatch(DisplayAction.MusicPlayPause)
        router.dispatch(DisplayAction.MusicNext)
        router.dispatch(DisplayAction.MusicPrev)
        assertEquals(listOf("new:toggle", "new:next", "new:prev"), calls)
        router.unregisterMusic(second); router.dispatch(DisplayAction.MusicNext)
        assertEquals(3, calls.size)
    }
}

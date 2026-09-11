package com.smartview.glassai.bridge

import org.junit.Assert.*
import org.junit.Test

class MediaControlTargetTest {
    private class Controller(val id: String, var playing: Boolean = false) {
        fun snapshot() = MediaSnapshot(id, "app.$id", "", "", playing, 0)
    }

    @Test fun readsCurrentPlaybackWithoutWaitingForACallback() {
        val a = Controller("a"); val b = Controller("b")
        val candidates = listOf(a, b); val allowed = setOf("app.a", "app.b")
        assertSame(a, resolveMediaTarget(candidates, allowed, Controller::snapshot))
        b.playing = true // No callback or cached selection refresh.
        assertSame(b, resolveMediaTarget(candidates, allowed, Controller::snapshot))
        assertSame(a, resolveMediaTarget(candidates, setOf("app.a"), Controller::snapshot))
        assertNull(resolveMediaTarget(emptyList<Controller>(), allowed, Controller::snapshot))
    }
}

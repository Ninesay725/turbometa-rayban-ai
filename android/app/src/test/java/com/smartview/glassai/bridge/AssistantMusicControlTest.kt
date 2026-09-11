package com.smartview.glassai.bridge

import org.junit.Assert.*
import org.junit.Test

class AssistantMusicControlTest {
    private class Session(val pkg: String, var playing: Boolean, val supported: Set<String>) {
        val commands = mutableListOf<String>()
        fun snapshot() = MediaSnapshot(pkg, pkg, "", "", playing, 0)
    }
    private val settings = BridgeSettings(musicEnabled = true, mediaPackages = setOf("app.a", "app.b"))
    private val a = Session("app.a", false, setOf("play", "pause", "next", "previous"))
    private val b = Session("app.b", true, setOf("play", "pause"))
    private var active = listOf(a, b)
    private var platformReads = 0

    private fun command(action: String, pkg: String? = null, config: BridgeSettings = settings,
        access: Boolean = true, connected: Boolean = true): String = executeAssistantMusicControl(
        action, pkg, config, access, connected,
        candidates = { platformReads++; active }, snapshot = Session::snapshot,
        supports = { session, name -> name in session.supported },
        send = { session, name -> session.commands += name },
    )

    @Test fun explicitPackageNeverFallsBackAndCommandsStayExplicit() {
        assertEquals("Music command sent.", command("play", "app.a"))
        command("pause", "app.a"); command("next", "app.a"); command("previous", "app.a")
        assertEquals(listOf("play", "pause", "next", "previous"), a.commands)
        assertTrue(b.commands.isEmpty())
        active = listOf(b)
        assertThrows(IllegalStateException::class.java) { command("play", "app.a") }
        assertTrue(b.commands.isEmpty())
    }

    @Test fun omittedPackageResolvesFreshPlaybackOnEveryCall() {
        command("pause")
        a.playing = true; b.playing = false
        command("pause")
        assertEquals(listOf("pause"), a.commands)
        assertEquals(listOf("pause"), b.commands)
        active = emptyList()
        assertThrows(IllegalStateException::class.java) { command("play") }
    }

    @Test fun invalidUnauthorizedAndUnsupportedCallsNeverSendACommand() {
        listOf("toggle", "PLAY", "private-input").forEach { action ->
            val failure = assertThrows(IllegalStateException::class.java) { command(action) }
            assertFalse(failure.toString().contains(action))
        }
        assertThrows(IllegalStateException::class.java) { command("play", "app.a.sensitive") }
        assertThrows(IllegalStateException::class.java) { command("play", config = settings.copy(musicEnabled = false)) }
        assertThrows(IllegalStateException::class.java) { command("play", access = false) }
        assertThrows(IllegalStateException::class.java) { command("play", connected = false) }
        assertEquals(0, platformReads)
        assertThrows(IllegalStateException::class.java) { command("next", "app.b") }
        assertTrue(a.commands.isEmpty()); assertTrue(b.commands.isEmpty())
    }

    @Test fun platformExceptionContentAndCauseAreNeverExposed() {
        listOf(SecurityException("PRIVATE TOKEN"), IllegalStateException("PRIVATE BODY")).forEach { failure ->
            val error = assertThrows(IllegalStateException::class.java) {
                executeAssistantMusicControl("play", null, settings, true, true,
                    candidates = { listOf(a) }, snapshot = Session::snapshot,
                    supports = { _, _ -> true }, send = { _, _ -> throw failure })
            }
            assertFalse(error.toString().contains("PRIVATE")); assertNull(error.cause)
        }
    }
}

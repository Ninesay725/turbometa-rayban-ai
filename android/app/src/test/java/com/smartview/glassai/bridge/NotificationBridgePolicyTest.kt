package com.smartview.glassai.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real lease's callback boundary, not the Android runtime's observer wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationBridgePolicyTest {
    @Test
    fun synchronousCardCleanupObserverCannotReleaseTheSameClaimTwice() = runTest {
        val events = mutableListOf<String>()
        var observedCleanup = false
        lateinit var lease: NotificationDisplayLease
        lease = NotificationDisplayLease(
            scope = this,
            canShow = { true },
            acquire = { events += "acquire" },
            release = { events += "release" },
            clearOwnedCard = {
                events += "clear"
                // Publishing Status can synchronously notify the runtime's card observer.
                if (!observedCleanup) {
                    observedCleanup = true
                    lease.retire()
                }
            },
        )

        assertTrue(lease.show { events += "show" })
        runCurrent()
        lease.retire()

        assertEquals(listOf("acquire", "show", "clear", "release"), events)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf("acquire", "show", "clear", "release"), events)
    }

    @Test
    fun dismissedPreviewTimerCannotRetireANewerPreview() = runTest {
        var claims = 0
        var preview: String? = null
        val lease = NotificationDisplayLease(
            scope = this,
            canShow = { true },
            acquire = { claims++ },
            release = { claims-- },
            clearOwnedCard = { preview = null },
        )

        assertTrue(lease.show { preview = "First" })
        runCurrent()
        advanceTimeBy(1_000)
        lease.retire()
        assertEquals(0, claims)
        assertNull(preview)

        advanceTimeBy(8_000)
        assertTrue(lease.show { preview = "New preview" })
        runCurrent()
        advanceTimeBy(1_000) // The dismissed preview's original expiry is now due.
        runCurrent()

        assertEquals(1, claims)
        assertEquals("New preview", preview)

        advanceTimeBy(9_000) // Only the new preview's own dwell may retire it.
        runCurrent()
        assertEquals(0, claims)
        assertNull(preview)
    }
}

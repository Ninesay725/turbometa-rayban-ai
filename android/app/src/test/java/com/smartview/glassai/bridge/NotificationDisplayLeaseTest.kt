package com.smartview.glassai.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDisplayLeaseTest {
    @Test fun noLiveSessionNeverAcquiresOrShows() = runTest {
        var calls = 0
        val lease = NotificationDisplayLease(this, { false }, { calls++ }, { calls++ }, { calls++ })
        assertFalse(lease.show { calls++ })
        assertEquals(0, calls)
    }

    @Test fun replacementRestartsDwellWithoutDuplicatingTheClaim() = runTest {
        var acquire = 0; var release = 0; var clear = 0; var shown = 0
        val lease = NotificationDisplayLease(this, { true }, { acquire++ }, { release++ }, { clear++ })
        lease.show { shown++ }; runCurrent()
        advanceTimeBy(9_000)
        lease.show { shown++ }; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, release)
        advanceTimeBy(9_000); runCurrent()
        assertEquals(1, acquire); assertEquals(1, release); assertEquals(1, clear)
        assertEquals(2, shown)
    }

    @Test fun retireOnDisableCancelsTimerAndReleasesExactlyOnce() = runTest {
        var releases = 0; var clears = 0
        val lease = NotificationDisplayLease(this, { true }, {}, { releases++ }, { clears++ })
        lease.show {}; runCurrent(); lease.retire(); lease.retire()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, releases); assertEquals(1, clears)
    }

    @Test fun lossOfLiveSessionRetiresRatherThanReplayingTheNextNotification() = runTest {
        var live = true; var releases = 0; var shows = 0
        val lease = NotificationDisplayLease(this, { live }, {}, { releases++ }, {})
        lease.show { shows++ }; live = false
        assertFalse(lease.show { shows++ })
        live = true; advanceTimeBy(20_000); runCurrent()
        assertEquals(1, releases); assertEquals(1, shows)
    }
}

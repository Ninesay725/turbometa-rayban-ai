package com.smartview.glassai.viewmodels

import com.smartview.glassai.managers.ScoRequestOwnership
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationAudioRouteTest {
    @Test fun foreignScoDuringAvailabilityWaitIsNeitherClaimedNorStopped() = runTest {
        val device = FakeDevice()
        var routeFree = true
        val route = OwnedTranslationAudioRoute({ true }, { routeFree }, { device })
        val prepared = async { runCatching { route.prepare() } }
        runCurrent()
        routeFree = false
        device.connectedBroadcast() // The same ownership policy guards the actual Android receiver.
        device.available.value = true
        runCurrent()
        assertTrue(prepared.await().exceptionOrNull() is TranslationRouteBusyException)
        assertFalse(route.connected.value)
        assertEquals(0, device.requests)
        route.close(); route.close()
        assertEquals(0, device.stops); assertEquals(1, device.closes)
    }

    @Test fun cancellationBeforeRequestDoesNotStopAnUnrequestedForeignRoute() = runTest {
        val device = FakeDevice()
        val route = OwnedTranslationAudioRoute({ true }, { true }, { device })
        val pending = async { route.prepare() }
        runCurrent(); device.connectedBroadcast()
        pending.cancelAndJoin(); route.close()
        assertEquals(0, device.requests); assertEquals(0, device.stops)
        assertFalse(device.connected.value)
    }

    @Test fun requestedRoutePublishesLossAndIsReleasedOnce() = runTest {
        val device = FakeDevice().apply { available.value = true }
        val route = OwnedTranslationAudioRoute({ true }, { true }, { device })
        val prepared = async { route.prepare() }
        runCurrent(); assertEquals(1, device.requests)
        assertFalse(prepared.isCompleted)
        device.connectedBroadcast(); runCurrent(); assertTrue(prepared.await())
        assertTrue(route.connected.value)
        device.connected.value = false; assertFalse(route.connected.value)
        route.close(); route.close()
        assertEquals(1, device.stops); assertEquals(1, device.closes)
    }

    @Test fun deniedPermissionDoesNotCreateBluetoothResources() = runTest {
        var creates = 0
        val route = OwnedTranslationAudioRoute({ false }, { true }, { creates++; FakeDevice() })
        assertFalse(route.prepare()); route.close()
        assertEquals(0, creates)
    }

    @Test fun ownedModeRejectsUnrequestedConnectedButLegacyModeKeepsItsBehavior() {
        val owned = ScoRequestOwnership(ownedOnly = true)
        assertFalse(owned.acceptsConnected()); assertFalse(owned.shouldStop(connected = true))
        owned.request()
        assertTrue(owned.acceptsConnected()); assertTrue(owned.shouldStop(connected = false))
        owned.clear()
        assertFalse(owned.acceptsConnected()); assertFalse(owned.shouldStop(connected = true))
        val legacy = ScoRequestOwnership(ownedOnly = false)
        assertTrue(legacy.acceptsConnected()); assertTrue(legacy.shouldStop(connected = true))
        assertFalse(legacy.shouldStop(connected = false))
    }

    private class FakeDevice : TranslationScoDevice {
        override val available = MutableStateFlow(false)
        override val connected = MutableStateFlow(false)
        private val ownership = ScoRequestOwnership(ownedOnly = true)
        var requests = 0; var stops = 0; var closes = 0
        override fun supported() = true
        override fun request() { requests++; ownership.request() }
        fun connectedBroadcast() { if (ownership.acceptsConnected()) connected.value = true }
        override fun close() {
            closes++
            if (ownership.shouldStop(connected.value)) stops++
            ownership.clear(); connected.value = false
        }
    }
}

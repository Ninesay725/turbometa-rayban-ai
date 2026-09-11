package com.smartview.glassai.glasses

import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppStatusProviderTest {
    private val observer = FakeDatDeviceObserver()
    private val device = GlassesDeviceInfo(
        "display-1", "Meta Ray-Ban Display", DeviceType.META_RAYBAN_DISPLAY,
        true, DeviceCompatibility.COMPATIBLE,
    )

    private fun TestScope.manager() =
        GlassesSessionManager(FakeDatSessionFactory(), observer, backgroundScope).also { it.startMonitoring() }

    @Test fun deviceNameComesFromTheActiveDevice() = runTest(UnconfinedTestDispatcher()) {
        val provider = AppStatusProvider(manager(), { false }, { OpenClawConnectionState.Disconnected })
        observer.device.value = device
        assertEquals(device.name, provider.currentStatus().deviceName)
        observer.device.value = device.copy(name = "Renamed glasses")
        assertEquals("Renamed glasses", provider.currentStatus().deviceName)
    }

    @Test fun emptyNameWhenNoDevice() = runTest(UnconfinedTestDispatcher()) {
        val provider = AppStatusProvider(manager(), { false }, { OpenClawConnectionState.Disconnected })
        assertEquals("", provider.currentStatus().deviceName)
        observer.device.value = device
        observer.device.value = null
        assertEquals("", provider.currentStatus().deviceName)
    }

    @Test fun flagsReflectKeyAndOpenClawState() = runTest(UnconfinedTestDispatcher()) {
        var hasKey = false
        var connection: OpenClawConnectionState = OpenClawConnectionState.Disconnected
        val provider = AppStatusProvider(manager(), { hasKey }, { connection })
        for (keyReady in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                hasKey = keyReady
                connection = if (connected) OpenClawConnectionState.Connected else OpenClawConnectionState.Disconnected
                assertEquals(DisplayCard.Status("", keyReady, connected), provider.currentStatus())
            }
        }
    }

    @Test fun transitionalOpenClawStatesAreNotConnected() = runTest(UnconfinedTestDispatcher()) {
        var connection: OpenClawConnectionState = OpenClawConnectionState.Connecting
        val provider = AppStatusProvider(manager(), { true }, { connection })
        for (state in listOf(
            OpenClawConnectionState.Connecting,
            OpenClawConnectionState.Reconnecting(1),
            OpenClawConnectionState.WaitingForPairing,
        )) {
            connection = state
            assertFalse(provider.currentStatus().openClawConnected)
        }
    }

    @Test fun constructingTheProviderDoesNotReadKeysOrOpenClawState() = runTest(UnconfinedTestDispatcher()) {
        var reads = 0
        val provider = AppStatusProvider(manager(), { reads++; true }, { reads++; OpenClawConnectionState.Connected })
        assertEquals(0, reads)
        provider.currentStatus()
        assertEquals(2, reads)
    }
}

package com.smartview.glassai.debug

import android.app.Application
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDevice
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitError
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.MockGlassesServices
import com.meta.wearable.dat.mockdevice.api.permissions.MockPermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockDeviceKitViewModelTest {
    private val device = FakeGlasses()
    private val kit = FakeKit(device)

    @Before fun setUp() = MockDeviceState.clear()
    @After fun tearDown() = MockDeviceState.clear()

    private fun viewModel() = MockDeviceKitViewModel(Application(), kit)

    @Test fun staleCallbacksPreserveOtherTogglesAndNewScreenRestoresThem() {
        val firstScreen = viewModel()
        val original = firstScreen.uiState.value.pairedDevices.single()
        assertNull(original.isPoweredOn)
        assertNull(original.isDonned)
        assertNull(original.isUnfolded)
        firstScreen.powerOn(original)
        firstScreen.don(original)
        val restored = viewModel().uiState.value.pairedDevices.single()
        assertEquals(true, restored.isPoweredOn)
        assertEquals(true, restored.isDonned)
        assertEquals(true, restored.isUnfolded)
    }

    @Test fun powerOffPreservesWornAndHingeStateLikePinnedSdk() {
        val model = viewModel()
        val original = model.uiState.value.pairedDevices.single()
        model.powerOn(original)
        model.don(original)
        model.powerOff(original)
        assertEquals(MockDeviceFlags(false, true, true), MockDeviceState.get("test-device"))
        model.fold(original)
        assertEquals(MockDeviceFlags(false, false, false), MockDeviceState.get("test-device"))
    }

    @Test fun failedCommandDoesNotRecordSuccess() {
        val model = viewModel()
        val original = model.uiState.value.pairedDevices.single()
        device.failCommands = true
        model.powerOn(original)
        assertNull(model.uiState.value.pairedDevices.single().isPoweredOn)
        assertNull(MockDeviceState.get("test-device").isPoweredOn)
        assertNotNull(model.uiState.value.lastError)
        device.failCommands = false
        model.powerOn(original)
        assertEquals(true, MockDeviceState.get("test-device").isPoweredOn)
        assertNull(model.uiState.value.lastError)
    }

    @Test fun unpairForgetsOnlyAfterSuccessAndDisableClearsTheStore() {
        val model = viewModel()
        val original = model.uiState.value.pairedDevices.single()
        model.powerOn(original)
        kit.failUnpair = true
        model.unpairDevice(original)
        assertEquals(1, model.uiState.value.pairedDevices.size)
        assertEquals(true, MockDeviceState.get("test-device").isPoweredOn)
        kit.failUnpair = false
        model.unpairDevice(original)
        assertTrue(model.uiState.value.pairedDevices.isEmpty())
        assertNull(MockDeviceState.get("test-device").isPoweredOn)
        model.powerOn(original) // An old callback must not resurrect an unpaired device.
        assertTrue(MockDeviceState.flags.value.isEmpty())
        MockDeviceState.update("another-device") { MockDeviceFlags(true, true, true) }
        model.disable()
        assertTrue(MockDeviceState.flags.value.isEmpty())
    }

    private class FakeGlasses : MockGlasses {
        var failCommands = false
        override val deviceIdentifier = DeviceIdentifier("test-device")
        override val services: MockGlassesServices get() = error("No media services needed")
        private fun command() { check(!failCommands) { "Device command failed" } }
        override fun powerOn() = command()
        override fun powerOff() = command()
        override fun don() = command()
        override fun doff() = command()
        override fun fold() = command()
        override fun unfold() = command()
    }

    private class FakeKit(device: MockGlasses) : MockDeviceKitInterface {
        var failUnpair = false
        override var isEnabled = true
        override val pairedDevices = mutableListOf<MockDevice>(device)
        override val permissions: MockPermissions get() = error("No permissions needed")
        override fun enable(config: MockDeviceKitConfig) { isEnabled = true }
        override fun disable() { isEnabled = false; pairedDevices.clear() }
        override fun unpairDevice(device: MockDevice) {
            check(!failUnpair) { "Unpair failed" }
            pairedDevices.remove(device)
        }
        override fun pairGlasses(model: GlassesModel): DatResult<MockGlasses, MockDeviceKitError> =
            error("These tests start with an already paired device")
    }
}

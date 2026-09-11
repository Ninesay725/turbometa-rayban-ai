package com.smartview.glassai.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockDeviceStateTest {
    @Before fun setUp() = MockDeviceState.clear()
    @After fun tearDown() = MockDeviceState.clear()

    @Test fun recoveredDeviceWithoutRecordedFlagsIsUnknown() {
        val flags = MockDeviceState.get("recovered")
        assertNull(flags.isPoweredOn)
        assertNull(flags.isDonned)
        assertNull(flags.isUnfolded)
        assertTrue(MockDeviceState.flags.value.isEmpty())
    }

    @Test fun updateCreatesFlagsWithoutInventingOtherRecoveredStates() {
        MockDeviceState.update("recovered") { it.copy(isUnfolded = true) }
        val flags = MockDeviceState.get("recovered")
        assertEquals(true, flags.isUnfolded)
        assertNull(flags.isPoweredOn)
        assertNull(flags.isDonned)
    }

    @Test fun flagsSurviveAcrossReadsAndUpdatesAreDeviceSpecific() {
        MockDeviceState.update("first") { MockDeviceFlags(true, true, true) }
        MockDeviceState.update("second") { MockDeviceFlags(false, false, false) }
        // A newly created screen reads the process store, not default-off UI flags.
        assertEquals(MockDeviceFlags(true, true, true), MockDeviceState.get("first"))
        MockDeviceState.update("first") { it.copy(isPoweredOn = false) }
        assertEquals(MockDeviceFlags(false, true, true), MockDeviceState.get("first"))
        assertEquals(MockDeviceFlags(false, false, false), MockDeviceState.get("second"))
        assertEquals(MockDeviceState.get("first"), MockDeviceState.flags.value["first"])
    }

    @Test fun removeAndClearForgetStatesInsteadOfReportingOff() {
        MockDeviceState.update("first") { MockDeviceFlags(true, true, true) }
        MockDeviceState.update("second") { MockDeviceFlags(true, false, true) }
        MockDeviceState.remove("first")
        assertFalse(MockDeviceState.flags.value.containsKey("first"))
        assertNull(MockDeviceState.get("first").isPoweredOn)
        assertEquals(true, MockDeviceState.get("second").isPoweredOn)
        MockDeviceState.clear()
        assertTrue(MockDeviceState.flags.value.isEmpty())
        assertNull(MockDeviceState.get("second").isPoweredOn)
    }
}

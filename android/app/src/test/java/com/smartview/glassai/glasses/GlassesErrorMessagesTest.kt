package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import com.smartview.glassai.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every DAT error case must map to its own string resource (spec §5.9). If the SDK adds an enum
 * case, that case falls through to dat_error_unknown and the assertFalse(...) below fails on
 * purpose so a real string gets added (the distinctness check alone would still pass for a
 * single new case).
 */
class GlassesErrorMessagesTest {

    @Test
    fun everyDeviceSessionErrorHasADistinctString() {
        val ids = DeviceSessionError.entries.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun everyStreamErrorHasADistinctString() {
        val ids = StreamError.entries.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun everyRegistrationErrorHasADistinctString() {
        val ids = RegistrationError.entries.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun captureErrorsHaveDistinctStrings() {
        val cases = listOf(
            CaptureError.DeviceDisconnected,
            CaptureError.NotStreaming,
            CaptureError.CaptureInProgress,
            CaptureError.CaptureFailed,
        )
        val ids = cases.map { GlassesErrorMessages.resId(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.contains(R.string.dat_error_unknown))
    }

    @Test
    fun cameraBusyMapsToBusyString() {
        assertTrue(GlassesErrorMessages.resId(CameraError.CameraBusy("x")) != 0)
        assertNotEquals(
            GlassesErrorMessages.resId(CameraError.CameraBusy("x")),
            GlassesErrorMessages.resId(CameraError.NoSession),
        )
    }
}

package com.smartview.glassai.services

import com.smartview.glassai.glasses.GlassesDisplayState
import org.junit.Assert.*
import org.junit.Test

class QuickVisionDisplayPolicyTest {
    @Test fun dwellRequiresStartedDisplayAndHeldClaim() {
        for (state in GlassesDisplayState.entries) {
            assertEquals(if (state == GlassesDisplayState.STARTED) 15_000L else 500L,
                QuickVisionDisplayPolicy.dwellMs(state, true, true, 500))
            assertEquals(if (state == GlassesDisplayState.STARTED) 5_000L else 2_000L,
                QuickVisionDisplayPolicy.dwellMs(state, true, false, 2_000))
            assertEquals(500L, QuickVisionDisplayPolicy.dwellMs(state, false, true, 500))
        }
    }
    @Test fun onlyAvailableDisplayNeedsSessionClaim() {
        assertTrue(QuickVisionDisplayPolicy.wantsDisplayClaim(true))
        assertFalse(QuickVisionDisplayPolicy.wantsDisplayClaim(false))
    }
}

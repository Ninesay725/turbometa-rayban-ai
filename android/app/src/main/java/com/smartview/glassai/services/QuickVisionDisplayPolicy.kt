package com.smartview.glassai.services

import com.smartview.glassai.glasses.GlassesDisplayState

object QuickVisionDisplayPolicy {
    const val RESULT_DWELL_MS = 15_000L
    const val ERROR_DWELL_MS = 5_000L
    const val DEFAULT_DWELL_MS = 500L
    fun wantsDisplayClaim(isDisplayAvailable: Boolean): Boolean = isDisplayAvailable
    fun dwellMs(displayState: GlassesDisplayState, claimHeld: Boolean,
                success: Boolean, fallbackMs: Long): Long =
        if (displayState == GlassesDisplayState.STARTED && claimHeld) {
            if (success) RESULT_DWELL_MS else ERROR_DWELL_MS
        } else fallbackMs
}

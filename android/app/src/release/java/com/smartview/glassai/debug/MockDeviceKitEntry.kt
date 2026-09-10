package com.smartview.glassai.debug

import androidx.compose.runtime.Composable

/** Release builds ship no MockDeviceKit; the Settings entry and route are hidden. */
object MockDeviceKitEntry {
    const val isAvailable: Boolean = false

    @Composable
    fun Screen(onBackClick: () -> Unit) {
        // Intentionally empty: unreachable in release builds.
    }
}

package com.smartview.glassai.debug

import androidx.compose.runtime.Composable

/** Same entry point as debug, without preview code or MockDeviceKit in release. */
object GlassesDisplayPreviewEntry {
    const val isAvailable: Boolean = false

    @Composable
    fun Screen(onBackClick: () -> Unit) {
        // Unreachable: the Settings entry and navigation route are guarded by isAvailable.
    }
}

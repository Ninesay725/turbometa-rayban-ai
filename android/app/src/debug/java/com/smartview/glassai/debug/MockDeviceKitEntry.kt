package com.smartview.glassai.debug

import androidx.compose.runtime.Composable

/** Debug builds expose the MockDeviceKit screen (spec §5.10). */
object MockDeviceKitEntry {
    const val isAvailable: Boolean = true

    @Composable
    fun Screen(onBackClick: () -> Unit) {
        MockDeviceKitScreen(onBackClick = onBackClick)
    }
}

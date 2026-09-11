package com.smartview.glassai.debug

import androidx.compose.runtime.Composable

object GlassesDisplayPreviewEntry {
    const val isAvailable: Boolean = true

    @Composable
    fun Screen(onBackClick: () -> Unit) {
        GlassesDisplayPreviewScreen(onBackClick)
    }
}

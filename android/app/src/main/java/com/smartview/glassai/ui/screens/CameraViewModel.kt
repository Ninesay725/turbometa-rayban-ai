package com.smartview.glassai.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.viewmodels.WearablesViewModel

/** Nav-entry lifetime keeps the preview stable across rotation and photo-analysis handoff. */
class CameraViewModel : ViewModel() {
    private var session: CameraSessionController<Bitmap>? = null

    internal fun session(wearables: WearablesViewModel): CameraSessionController<Bitmap> =
        session ?: CameraSessionController(
            scope = viewModelScope,
            stopStream = { owner -> wearables.stopStream(owner) },
            capturePhoto = { owner -> wearables.capturePhoto(owner) },
            nowMillis = SystemClock::elapsedRealtime,
        ).also { session = it }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }
}

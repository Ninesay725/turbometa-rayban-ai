package com.smartview.glassai.ui.navigation

import android.graphics.Bitmap
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.R
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.viewmodels.WearablesViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Direct analysis entries own a camera; a photo from the hub needs no second stream. */
@Composable
internal fun AnalysisPhotoRoute(
    wearables: WearablesViewModel,
    initialPhoto: Bitmap?,
    requestPermission: suspend (Permission) -> PermissionStatus,
    keepDisplaySession: Boolean = false,
    content: @Composable (Bitmap?, Bitmap?, () -> Unit) -> Unit,
) {
    val owner = remember { Any() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { GlassesSessionManager.getInstance(context.applicationContext) }
    val displayOwner = remember { "AnalysisDisplay:${UUID.randomUUID()}" }
    val permission by rememberUpdatedState(requestPermission)
    var photo by remember { mutableStateOf(initialPhoto) }
    var work by remember { mutableStateOf<Job?>(null) }
    var visible by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val frame by wearables.currentFrame.collectAsState()

    suspend fun ensureStream(): Boolean {
        if (!visible) return false
        if (!started || wearables.streamState.value is WearablesViewModel.StreamState.Stopped ||
            wearables.streamState.value is WearablesViewModel.StreamState.Error) {
            started = wearables.startStream(owner, permission)
        }
        return started && visible
    }

    LifecycleStartEffect(owner) {
        visible = true
        // Nutrition results outlive camera capture. Retain a session-only claim, never a second camera.
        val displayJob = if (keepDisplaySession && ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                manager.isDisplayAvailable.first { it }
                manager.acquireAndStart(displayOwner, 12_000)
            }
        } else null
        if (photo == null) work = scope.launch { ensureStream() }
        onStopOrDispose {
            visible = false
            work?.cancel()
            work = null
            started = false
            wearables.stopStream(owner)
            displayJob?.cancel()
            manager.release(displayOwner)
        }
    }

    content(photo, if (started) frame else null) {
        if (visible && work?.isActive != true) {
            work = scope.launch {
                if (!ensureStream()) return@launch
                val ready = withTimeoutOrNull(20_000) {
                    wearables.streamState.first {
                        it is WearablesViewModel.StreamState.Streaming ||
                            it is WearablesViewModel.StreamState.Error ||
                            it is WearablesViewModel.StreamState.Stopped
                    }
                }
                if (ready is WearablesViewModel.StreamState.Streaming) {
                    val fresh = wearables.capturePhoto(owner)
                    if (fresh != null) photo = fresh
                    else wearables.setError(context.getString(R.string.photo_capture_failed))
                } else if (ready !is WearablesViewModel.StreamState.Error) {
                    wearables.setError(context.getString(R.string.glasses_session_timeout))
                }
                wearables.stopStream(owner)
                started = false
            }
        }
    }
}

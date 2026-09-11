package com.smartview.glassai.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.R
import com.smartview.glassai.viewmodels.WearablesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    wearablesViewModel: WearablesViewModel,
    onBackClick: () -> Unit,
    onAnalyzePhoto: (Bitmap) -> Unit,
    onNutritionPhoto: (Bitmap) -> Unit,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
) {
    val page: CameraViewModel = viewModel()
    val controller = remember(page, wearablesViewModel) { page.session(wearablesViewModel) }
    val owner = remember { Any() }
    val requestPermission by rememberUpdatedState(onRequestWearablesPermission)
    val state by controller.state.collectAsStateWithLifecycle()
    val stream by wearablesViewModel.streamState.collectAsStateWithLifecycle()
    val frame by wearablesViewModel.currentFrame.collectAsStateWithLifecycle()
    val error by wearablesViewModel.errorMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LifecycleStartEffect(controller, owner) {
        controller.enter(owner) { wearablesViewModel.startStream(owner, requestPermission) }
        val monitor = scope.launch {
            wearablesViewModel.streamState.collect { next ->
                if (controller.state.value.active) {
                    when (next) {
                        WearablesViewModel.StreamState.Streaming,
                        WearablesViewModel.StreamState.Paused -> Unit
                        is WearablesViewModel.StreamState.Error -> controller.streamFailed()
                        WearablesViewModel.StreamState.Stopped -> controller.streamFailed()
                        WearablesViewModel.StreamState.Waiting -> Unit
                    }
                }
            }
        }
        onStopOrDispose {
            monitor.cancel()
            controller.leave(owner)
        }
    }

    val photo = state.photo
    BackHandler(enabled = photo != null) { controller.retake() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (photo == null) R.string.camera_title else R.string.camera_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (photo != null) controller.retake() else onBackClick() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (photo != null) {
                CameraPhotoPreview(photo, controller::retake, onAnalyzePhoto, onNutritionPhoto)
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val current = frame
                    if (state.active && current != null && !current.isRecycled) {
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription = stringResource(R.string.camera_live_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else if (state.starting || (state.active && stream == WearablesViewModel.StreamState.Waiting)) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.stream_no_video), color = Color.White)
                    }
                }
                val notice = when {
                    state.stopReason == CameraStopReason.TIMER -> R.string.camera_timer_expired
                    state.stopReason == CameraStopReason.CAPTURE_FAILED -> R.string.camera_capture_timeout
                    state.stopReason == CameraStopReason.START_DENIED -> R.string.camera_start_denied
                    state.stopReason == CameraStopReason.STREAM_FAILED -> R.string.camera_stream_failed
                    state.starting || (state.active && stream == WearablesViewModel.StreamState.Waiting) -> R.string.camera_starting
                    stream == WearablesViewModel.StreamState.Paused && state.active -> R.string.stream_paused_subtitle
                    state.active -> R.string.camera_stream_active
                    else -> R.string.camera_stream_stopped
                }
                Text(stringResource(notice))
                error?.takeIf { state.stopReason != null && state.stopReason != CameraStopReason.TIMER }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.camera_auto_stop), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to R.string.stream_1min, 5 to R.string.stream_5min,
                        10 to R.string.stream_10min, 15 to R.string.stream_15min).forEach { (minutes, label) ->
                        FilterChip(
                            selected = state.minutes == minutes,
                            onClick = { controller.selectMinutes(minutes) },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (state.active) {
                    Text(stringResource(R.string.camera_remaining,
                        state.remainingSeconds / 60, state.remainingSeconds % 60))
                }
                Button(
                    onClick = controller::capture,
                    enabled = state.active && !state.capturing && stream == WearablesViewModel.StreamState.Streaming,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (state.capturing) R.string.camera_capturing else R.string.stream_capture))
                }
                OutlinedButton(
                    onClick = { if (state.active || state.starting) controller.stop() else controller.restart() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (state.active || state.starting) R.string.stream_stop else R.string.camera_restart))
                }
            }
        }
    }
}

@Composable
private fun CameraPhotoPreview(
    photo: Bitmap,
    onRetake: () -> Unit,
    onAnalyzePhoto: (Bitmap) -> Unit,
    onNutritionPhoto: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var sharing by remember(photo) { mutableStateOf(false) }
    var shareFailed by remember(photo) { mutableStateOf(false) }
    Image(
        bitmap = photo.asImageBitmap(),
        contentDescription = stringResource(R.string.camera_preview_title),
        modifier = Modifier.fillMaxWidth().height(300.dp),
        contentScale = ContentScale.Fit
    )
    Text(stringResource(R.string.camera_preview_privacy))
    Button(onClick = { onAnalyzePhoto(photo) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.vision_recognition))
    }
    Button(onClick = { onNutritionPhoto(photo) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.camera_nutrition))
    }
    OutlinedButton(
        enabled = !sharing,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                sharing = true
                shareFailed = false
                try {
                    val intent = createCameraShareIntent(context, photo)
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.gallery_share)))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    shareFailed = true
                } finally {
                    sharing = false
                }
            }
        }
    ) { Text(stringResource(if (sharing) R.string.camera_share_preparing else R.string.gallery_share)) }
    if (shareFailed) Text(stringResource(R.string.camera_share_failed), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retake)) }
}

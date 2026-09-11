package com.smartview.glassai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.R
import com.smartview.glassai.viewmodels.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslateScreen(
    wearablesViewModel: WearablesViewModel,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { LiveTranslateViewModel.factory(context) }
    val model: LiveTranslateViewModel = viewModel(factory = factory)
    val ui by model.ui.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val currentWearablesPermission by rememberUpdatedState(onRequestWearablesPermission)
    // Stable for this screen, never shared with camera/Live AI/another translation screen.
    val cameraOwner = remember { Any() }
    val visual = remember(wearablesViewModel, cameraOwner) {
        ScreenTranslationVisual(wearablesViewModel, cameraOwner) { currentWearablesPermission(it) }
    }
    val pendingPermission = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingPermission[0]
        pendingPermission[0] = null
        pending?.complete(granted)
    }
    val hasMicrophonePermission = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val requestMicrophonePermission: suspend () -> Boolean = {
        if (hasMicrophonePermission()) true else {
            val result = CompletableDeferred<Boolean>()
            pendingPermission[0]?.cancel()
            pendingPermission[0] = result
            try {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                result.await()
            } finally {
                if (pendingPermission[0] === result) pendingPermission[0] = null
            }
        }
    }
    LifecycleStartEffect(model) {
        model.onStart()
        onStopOrDispose { model.onStop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.onResume(hasMicrophonePermission()) }
    DisposableEffect(model) {
        onDispose { pendingPermission[0]?.cancel(); pendingPermission[0] = null }
    }

    val pair = if (ui.active || ui.text.text.isNotBlank()) ui.settings else settings
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.translate_title)) },
                navigationIcon = { IconButton(onClick = { model.onStop(); onBackClick() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                } },
                actions = { IconButton(onClick = { model.onStop(); onSettingsClick() }) {
                    Icon(Icons.Default.Settings, stringResource(R.string.translate_settings_title))
                } })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onClick = {
                    if (ui.active) model.stop()
                    else model.start(requestMicrophonePermission, hasMicrophonePermission, visual)
                }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Text(stringResource(if (ui.active) R.string.translate_stop else R.string.translate_start))
                }
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.translate_language_pair, pair.sourceLanguage.label, pair.targetLanguage.label),
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(when (ui.phase) {
                    TranslationUiPhase.IDLE -> R.string.translate_idle
                    TranslationUiPhase.PERMISSION -> R.string.translate_wait_permission
                    TranslationUiPhase.ROUTING -> R.string.translate_wait_microphone
                    TranslationUiPhase.CONNECTING -> R.string.translate_connecting
                    TranslationUiPhase.RECORDING -> R.string.translate_recording
                    TranslationUiPhase.ERROR -> R.string.translate_stopped_error
                }), style = MaterialTheme.typography.bodyLarge)
                if (ui.phase in listOf(TranslationUiPhase.PERMISSION, TranslationUiPhase.ROUTING, TranslationUiPhase.CONNECTING)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
            ui.input?.let { input -> item {
                Text(stringResource(if (input == TranslationInput.GLASSES) R.string.translate_input_glasses else R.string.translate_input_phone))
            } }
            if (ui.phoneFallback) item { TranslationGuidance(stringResource(R.string.translate_phone_fallback)) }
            if (ui.cameraUnavailable) item { TranslationGuidance(stringResource(R.string.translate_camera_unavailable)) }
            ui.error?.let { error -> item {
                Text(stringResource(when (error) {
                    TranslationUiError.MISSING_KEY -> R.string.translate_error_key
                    TranslationUiError.MICROPHONE_PERMISSION -> R.string.translate_error_microphone
                    TranslationUiError.MICROPHONE_ROUTE -> R.string.translate_error_microphone_route
                    TranslationUiError.CONNECTION -> R.string.translate_error_connection
                }), color = MaterialTheme.colorScheme.error)
                ui.serviceError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.translate_current_text), style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(ui.text.text.ifBlank { stringResource(R.string.translate_waiting_text) },
                                style = MaterialTheme.typography.headlineSmall)
                        }
                        if (ui.active && ui.text.text.isNotBlank() && !ui.text.isFinal) {
                            Text(stringResource(R.string.translate_interim), style = MaterialTheme.typography.labelMedium)
                        }
                        if (ui.text.originalText.isNotBlank()) {
                            HorizontalDivider()
                            Text(stringResource(R.string.translate_original), style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(ui.text.originalText) }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.translate_privacy), style = MaterialTheme.typography.bodySmall) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.translate_history), style = MaterialTheme.typography.titleMedium)
                    if (history.isNotEmpty()) TextButton(onClick = model::clearHistory) {
                        Text(stringResource(R.string.translate_clear_history))
                    }
                }
                Text(stringResource(R.string.translate_history_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (history.isEmpty()) item { Text(stringResource(R.string.translate_history_empty)) }
            items(history, key = { it.id }) { entry ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.translate_language_pair, entry.source.label, entry.target.label),
                            style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(entry.text) }
                        if (entry.originalText.isNotBlank()) SelectionContainer { Text(entry.originalText) }
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationGuidance(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

/** Both frame reads and completed encodes belong to the same parent's stream lease. */
private class ScreenTranslationVisual(
    private val wearables: WearablesViewModel,
    private val owner: Any,
    private val permission: suspend (Permission) -> PermissionStatus,
) : TranslationVisualInput {
    private var lastFrame: Bitmap? = null
    private var lease: Long? = null
    override suspend fun start(): Boolean {
        lastFrame = null
        lease = null
        if (!wearables.startStream(owner, permission)) return false
        val startedLease = wearables.streamLease(owner) ?: error("Camera unavailable")
        lease = startedLease
        val started = withTimeoutOrNull(18_000) {
            wearables.streamState.first {
                !wearables.ownsStream(owner, startedLease) ||
                    it == WearablesViewModel.StreamState.Streaming || it == WearablesViewModel.StreamState.Stopped ||
                    it is WearablesViewModel.StreamState.Error
            } == WearablesViewModel.StreamState.Streaming
        } == true
        check(wearables.ownsStream(owner, startedLease)) { "Camera unavailable" }
        return started
    }
    override suspend fun nextJpeg(): ByteArray? {
        val currentLease = lease ?: error("Camera unavailable")
        check(wearables.ownsStream(owner, currentLease)) { "Camera unavailable" }
        val stream = wearables.streamState.value
        if (stream is WearablesViewModel.StreamState.Error || stream == WearablesViewModel.StreamState.Stopped) {
            error("Camera unavailable") // Converted to localized guidance; never includes image data.
        }
        if (stream != WearablesViewModel.StreamState.Streaming) return null
        return readOwnedTranslationImage(currentLease, owns = { wearables.ownsStream(owner, it) }, frame = {
            wearables.currentFrame(owner, it)?.takeUnless { frame -> frame === lastFrame }
        }) { frame ->
            lastFrame = frame
            withContext(Dispatchers.Default) {
                if (frame.isRecycled) return@withContext null
                val scale = minOf(1f, 640f / maxOf(frame.width, frame.height))
                val resized = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    ByteArrayOutputStream().use { output ->
                        if (!resized.compress(Bitmap.CompressFormat.JPEG, 60, output)) null
                        else output.toByteArray().takeIf { it.size <= 500_000 }
                    }
                } finally { if (resized !== frame) resized.recycle() }
            }
        }
    }
    override fun stop() { lease = null; lastFrame = null; wearables.stopStream(owner) }
}

/** Called on Main around an encoder that may suspend on a worker thread. */
internal suspend fun <Frame : Any> readOwnedTranslationImage(
    lease: Long,
    owns: (Long) -> Boolean,
    frame: (Long) -> Frame?,
    encode: suspend (Frame) -> ByteArray?,
): ByteArray? {
    check(owns(lease)) { "Camera unavailable" }
    val current = frame(lease) ?: return null
    val jpeg = encode(current)
    check(owns(lease)) { "Camera unavailable" }
    return jpeg
}

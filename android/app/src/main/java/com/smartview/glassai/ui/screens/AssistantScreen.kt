package com.smartview.glassai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.R
import com.smartview.glassai.viewmodels.AssistantUiError
import com.smartview.glassai.viewmodels.AssistantViewModel
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { AssistantViewModel.factory(context) }
    val model: AssistantViewModel = viewModel(factory = factory)
    val ui by model.ui.collectAsStateWithLifecycle()
    val notifications by model.notifications.collectAsStateWithLifecycle()
    val currentCameraPermission by rememberUpdatedState(onRequestWearablesPermission)
    val requestCameraPermission: suspend () -> Boolean = {
        Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted ||
            currentCameraPermission(Permission.CAMERA) == PermissionStatus.Granted
    }
    val pendingPermission = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingPermission[0]
        pendingPermission[0] = null
        pending?.complete(granted)
    }
    val requestMicrophonePermission: suspend () -> Boolean = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
        else {
            val pending = CompletableDeferred<Boolean>()
            pendingPermission[0]?.cancel()
            pendingPermission[0] = pending
            try { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO); pending.await() }
            finally { if (pendingPermission[0] === pending) pendingPermission[0] = null }
        }
    }
    LifecycleStartEffect(model) {
        model.enterScreen()
        onStopOrDispose { model.onBackground() }
    }
    DisposableEffect(model) {
        onDispose { model.leaveScreen(); pendingPermission[0]?.cancel(); pendingPermission[0] = null }
    }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var packageMenu by remember { mutableStateOf(false) }
    val allowedPackages = notifications.aiNotificationPackages.sorted()
    LaunchedEffect(allowedPackages, notifications.aiNotificationsEnabled) {
        if (!notifications.aiNotificationsEnabled || selectedPackage !in allowedPackages) selectedPackage = null
    }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.messages.size, ui.messages.lastOrNull()) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size)
    }
    val summaryPrompt = stringResource(R.string.assistant_summary_prompt)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.assistant_title)) },
            navigationIcon = { IconButton(onClick = { model.leaveScreen(); onBackClick() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } }, actions = {
                IconButton(onClick = { model.leaveScreen(); onSettingsClick() }) {
                    Icon(Icons.Default.Settings, stringResource(R.string.assistant_settings_title))
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.assistant_intro), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodySmall)
                        if (!ui.configured) TextButton(onClick = { model.leaveScreen(); onSettingsClick() }) {
                            Text(stringResource(R.string.assistant_configure))
                        }
                        if (!ui.supportsImages) Text(stringResource(R.string.assistant_photo_disabled), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (model.dictationAvailable) R.string.assistant_dictation_hint else R.string.assistant_dictation_unavailable),
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text(stringResource(R.string.assistant_notifications_privacy), style = MaterialTheme.typography.bodySmall)
                        if (notifications.aiNotificationsEnabled && allowedPackages.isNotEmpty()) {
                            Box {
                                TextButton(onClick = { packageMenu = true }, enabled = !ui.busy && !ui.listening) {
                                    Text(selectedPackage ?: stringResource(R.string.assistant_all_allowed), modifier = Modifier.weight(1f, fill = false))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = packageMenu, onDismissRequest = { packageMenu = false }, modifier = Modifier.heightIn(max = 240.dp)) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.assistant_all_allowed)) },
                                        onClick = { selectedPackage = null; packageMenu = false })
                                    allowedPackages.forEach { pkg -> DropdownMenuItem(text = { Text(pkg) },
                                        onClick = { selectedPackage = pkg; packageMenu = false }) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { model.summarizeNotifications(summaryPrompt, selectedPackage) },
                                enabled = ui.configured && !ui.busy && !ui.listening && notifications.aiNotificationsEnabled && allowedPackages.isNotEmpty(),
                                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.assistant_summarize_notifications)) }
                            TextButton(onClick = { model.leaveScreen(); onNotificationSettingsClick() }) {
                                Text(stringResource(R.string.assistant_choose_apps))
                            }
                        }
                    }
                }
                items(ui.messages) { message ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(
                        containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(if (message.role == "user") R.string.assistant_you else R.string.assistant_title),
                                style = MaterialTheme.typography.labelMedium)
                            if (message.notificationSummary) Text(stringResource(R.string.assistant_summary_label), style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(message.text) }
                        }
                    }
                }
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.error?.let { error ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(assistantErrorString(error)), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        ui.safeDetail?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = model::dismissError) { Text(stringResource(R.string.assistant_dismiss)) }
                }
            }
            OutlinedTextField(value = ui.input, onValueChange = model::setInput,
                modifier = Modifier.fillMaxWidth(), enabled = !ui.busy && !ui.listening,
                label = { Text(stringResource(R.string.assistant_prompt)) }, minLines = 1, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.send(requestCameraPermission = requestCameraPermission) },
                    enabled = ui.configured && !ui.busy && !ui.listening && ui.input.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.assistant_send))
                }
                OutlinedButton(onClick = { model.send(withPhoto = true, requestCameraPermission = requestCameraPermission) },
                    enabled = ui.configured && ui.supportsImages && !ui.busy && !ui.listening && ui.input.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.assistant_photo_question))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.dictate(requestMicrophonePermission) },
                    enabled = model.dictationAvailable && !ui.busy && !ui.listening) {
                    Text(stringResource(if (ui.listening) R.string.assistant_listening else R.string.assistant_dictate))
                }
                TextButton(onClick = model::stop) { Text(stringResource(R.string.assistant_stop)) }
                TextButton(onClick = model::clear) { Text(stringResource(R.string.assistant_clear)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun assistantErrorString(error: AssistantUiError): Int = when (error) {
    AssistantUiError.CONFIGURATION -> R.string.assistant_invalid_config
    AssistantUiError.REQUEST -> R.string.assistant_request_error
    AssistantUiError.TIMEOUT -> R.string.assistant_timeout_error
    AssistantUiError.INTERRUPTED -> R.string.assistant_interrupted
    AssistantUiError.CAMERA_PERMISSION -> R.string.assistant_camera_permission
    AssistantUiError.IMAGES_DISABLED -> R.string.assistant_photo_disabled
    AssistantUiError.NOTIFICATIONS_DISABLED -> R.string.assistant_notifications_disabled
    AssistantUiError.DICTATION -> R.string.assistant_dictation_error
    AssistantUiError.MICROPHONE_PERMISSION -> R.string.assistant_microphone_permission
    AssistantUiError.SPEECH -> R.string.assistant_speech_error
}

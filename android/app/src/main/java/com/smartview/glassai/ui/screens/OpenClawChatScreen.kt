package com.smartview.glassai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartview.glassai.R
import com.smartview.glassai.managers.BluetoothAudioManager
import com.smartview.glassai.services.openclaw.OpenClawChatMessage
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.ui.components.openClawStatusColor
import com.smartview.glassai.ui.components.openClawStatusText
import com.smartview.glassai.ui.theme.AppRadius
import com.smartview.glassai.ui.theme.AppSpacing
import com.smartview.glassai.ui.theme.OpenClawColor
import com.smartview.glassai.ui.theme.OpenClawColorEnd
import com.smartview.glassai.viewmodels.OpenClawViewModel

/** OpenClaw chat (research §5.1): voice / Snap & Send / text, in-memory history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawChatScreen(
    viewModel: OpenClawViewModel = viewModel(),
    onBackClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val pendingResponse by viewModel.pendingResponse.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val showTextInput by viewModel.showTextInput.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val asrText by viewModel.asrText.collectAsState()
    val asrPartial by viewModel.asrPartial.collectAsState()
    val asrError by viewModel.asrError.collectAsState()
    val asrNotice by viewModel.asrNotice.collectAsState()
    val audioSource by viewModel.desiredAudioSource.collectAsState()
    val isBluetoothAvailable by viewModel.isBluetoothAvailable.collectAsState()
    val isConnected = connectionState == OpenClawConnectionState.Connected
    val listState = rememberLazyListState()

    // RECORD_AUDIO is requested lazily, exactly like Live AI: the chat screen is usable without the
    // microphone, so the prompt only appears when the user actually taps the mic.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening()
    }
    fun toggleListening() {
        if (isListening) {
            viewModel.stopListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        viewModel.enterScreen()
        onDispose { viewModel.leaveScreen() }
    }

    LaunchedEffect(messages.size, pendingResponse != null) {
        val count = messages.size + (if (pendingResponse != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.openclaw_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(openClawStatusColor(connectionState))
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (!isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF9800))
                        .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (connectionState == OpenClawConnectionState.Connecting ||
                        connectionState is OpenClawConnectionState.Reconnecting
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(AppSpacing.small))
                    }
                    Text(openClawStatusText(connectionState), color = Color.White, fontSize = 13.sp)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(AppSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                items(messages, key = { it.id }) { message -> ChatBubble(message) }
                pendingResponse?.let { pending ->
                    item(key = "pending") {
                        ChatBubble(OpenClawChatMessage(id = "pending", role = OpenClawChatMessage.ROLE_ASSISTANT, text = pending))
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(AppSpacing.medium)
                    .navigationBarsPadding()
            ) {
                // Voice transcript box + Cancel / Send (shown while listening or when text remains)
                val transcript = asrText + asrPartial
                if (isListening || transcript.isNotEmpty() || asrError != null || asrNotice != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.medium))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(AppSpacing.medium)
                    ) {
                        // A notice (the glasses-mic fallback) is not an error: it sits above the
                        // transcript instead of replacing it.
                        asrNotice?.let { notice ->
                            Text(
                                text = notice,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.extraSmall))
                        }
                        Text(
                            text = when {
                                asrError != null -> asrError!!
                                transcript.isEmpty() -> stringResource(R.string.openclaw_chat_listening)
                                else -> transcript
                            },
                            modifier = Modifier.heightIn(min = 40.dp),
                            color = if (asrError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        if (!isListening) {
                            Spacer(modifier = Modifier.height(AppSpacing.small))
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                OutlinedButton(onClick = { viewModel.cancelAsr() }) { Text(stringResource(R.string.cancel)) }
                                Button(
                                    onClick = { viewModel.sendAsrText() },
                                    enabled = transcript.isNotBlank() && isConnected,
                                    colors = ButtonDefaults.buttonColors(containerColor = OpenClawColor)
                                ) { Text(stringResource(R.string.openclaw_chat_sendvoice)) }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.small))
                }

                // Phone / glasses microphone (same semantics as Live AI)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    FilterChip(
                        selected = audioSource == BluetoothAudioManager.AudioSource.PHONE_MIC,
                        onClick = { viewModel.switchAudioSource(BluetoothAudioManager.AudioSource.PHONE_MIC) },
                        label = { Text(stringResource(R.string.audio_source_phone)) },
                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    FilterChip(
                        selected = audioSource == BluetoothAudioManager.AudioSource.BLUETOOTH_MIC,
                        onClick = { viewModel.switchAudioSource(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC) },
                        enabled = isBluetoothAvailable,
                        label = { Text(stringResource(R.string.audio_source_glasses)) },
                        leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.small))

                // Snap & Send | big mic | Text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton(
                        icon = Icons.Default.CameraAlt,
                        label = if (isSending) stringResource(R.string.openclaw_chat_sending) else stringResource(R.string.openclaw_chat_snap),
                        enabled = isConnected && !isSending,
                        onClick = { viewModel.snapAndSend() }
                    )
                    FilledIconButton(
                        onClick = { toggleListening() },
                        enabled = isConnected,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening) Color(0xFFE53935) else OpenClawColor
                        )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) stringResource(R.string.openclaw_chat_stop) else stringResource(R.string.openclaw_chat_mic),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    ActionButton(
                        icon = Icons.Default.Keyboard,
                        label = stringResource(R.string.openclaw_chat_text),
                        enabled = true,
                        onClick = { viewModel.toggleTextInput() }
                    )
                }

                if (showTextInput) {
                    Spacer(modifier = Modifier.height(AppSpacing.small))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { viewModel.onInputChanged(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.openclaw_chat_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (isConnected) viewModel.sendText() })
                        )
                        IconButton(onClick = { viewModel.sendText() }, enabled = inputText.isNotBlank() && isConnected) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.openclaw_chat_sendvoice), tint = OpenClawColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.4f))
    }
}

@Composable
private fun ChatBubble(message: OpenClawChatMessage) {
    val isUser = message.role == OpenClawChatMessage.ROLE_USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(AppRadius.large))
                .then(
                    if (isUser) Modifier.background(Brush.linearGradient(listOf(OpenClawColor, OpenClawColorEnd)))
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                )
                .padding(AppSpacing.medium)
        ) {
            message.image?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 200.dp, height = 150.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                if (message.text.isNotEmpty()) Spacer(modifier = Modifier.height(AppSpacing.small))
            }
            if (message.text.isNotEmpty()) {
                Text(
                    text = message.text,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                )
            }
        }
    }
}

package com.smartview.glassai.ui.screens

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartview.glassai.R
import com.smartview.glassai.bridge.BridgePreferences
import com.smartview.glassai.bridge.NotificationBridgeRuntime
import java.text.DateFormat
import java.util.Date

private val mediaPackageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationBridgeScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val preferences = remember(app) { BridgePreferences.getInstance(app) }
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val listenerConnected by NotificationBridgeRuntime.listenerConnected.collectAsStateWithLifecycle()
    val music by NotificationBridgeRuntime.music.collectAsStateWithLifecycle()
    val messages by NotificationBridgeRuntime.messages.collectAsStateWithLifecycle()
    val runtimeError by NotificationBridgeRuntime.error.collectAsStateWithLifecycle()
    var hasAccess by remember(app) {
        mutableStateOf(NotificationBridgeRuntime.hasNotificationAccess(app))
    }
    var accessSettingsUnavailable by remember { mutableStateOf(false) }

    // The NavHost supplies this page's lifecycle owner. STOP and disposal both release
    // the claim, including opening Android settings and configuration changes on API 31.
    // The runtime gates acquisition on musicEnabled and observes later preference changes.
    LifecycleStartEffect(app) {
        NotificationBridgeRuntime.enterMusicPage(app)
        onStopOrDispose { NotificationBridgeRuntime.leaveMusicPage() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = NotificationBridgeRuntime.hasNotificationAccess(app)
    }

    // Only the editable package list survives recreation; message/track data stays in RAM.
    var packagesDraft by rememberSaveable(settings.mediaPackages) {
        mutableStateOf(settings.mediaPackages.sorted().joinToString("\n"))
    }
    val editedPackages = packagesDraft.lineSequence().map(String::trim)
        .filter(String::isNotEmpty).toSet()
    val packagesValid = editedPackages.all { mediaPackageName.matches(it) }
    val listenerUnavailable = when {
        !hasAccess -> R.string.bridge_access_required
        !listenerConnected -> R.string.bridge_listener_waiting
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bridge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                BridgeSection(stringResource(R.string.bridge_access_title)) {
                    Text(stringResource(R.string.bridge_access_explanation))
                    Text(
                        stringResource(
                            if (hasAccess) R.string.bridge_access_granted
                            else R.string.bridge_access_not_granted
                        ),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(
                            if (hasAccess && listenerConnected) R.string.bridge_listener_connected
                            else R.string.bridge_listener_disconnected
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = {
                        accessSettingsUnavailable = false
                        try {
                            // The system settings page is opened only by this user action.
                            NotificationBridgeRuntime.openNotificationAccessSettings(context)
                        } catch (_: ActivityNotFoundException) {
                            accessSettingsUnavailable = true
                        } catch (_: SecurityException) {
                            accessSettingsUnavailable = true
                        }
                    }) {
                        Text(stringResource(R.string.bridge_open_access_settings))
                    }
                    if (accessSettingsUnavailable) {
                        Text(
                            stringResource(R.string.bridge_settings_unavailable),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            item {
                BridgeSection(stringResource(R.string.bridge_features_title)) {
                    BridgeToggle(
                        title = stringResource(R.string.bridge_wechat_enabled),
                        description = stringResource(R.string.bridge_wechat_description),
                        checked = settings.wechatEnabled,
                        onCheckedChange = preferences::setWechatEnabled
                    )
                    HorizontalDivider()
                    BridgeToggle(
                        title = stringResource(R.string.bridge_music_enabled),
                        description = stringResource(R.string.bridge_music_description),
                        checked = settings.musicEnabled,
                        onCheckedChange = preferences::setMusicEnabled
                    )
                }
            }
            runtimeError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                BridgeSection(stringResource(R.string.bridge_current_track)) {
                    val track = music
                    when {
                        !settings.musicEnabled -> Text(stringResource(R.string.bridge_music_disabled))
                        listenerUnavailable != null -> Text(stringResource(listenerUnavailable))
                        track == null -> Text(stringResource(R.string.bridge_no_track))
                        else -> {
                            Text(
                                track.title.ifBlank { stringResource(R.string.bridge_untitled_track) },
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (track.artist.isNotBlank()) Text(track.artist)
                            Text(track.packageName, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(
                                if (track.isPlaying) R.string.bridge_playing else R.string.bridge_paused
                            ))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                IconButton(
                                    onClick = NotificationBridgeRuntime::previous,
                                    enabled = track.canPrevious
                                ) {
                                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.bridge_previous))
                                }
                                IconButton(
                                    onClick = NotificationBridgeRuntime::playPause,
                                    enabled = track.canPlayPause
                                ) {
                                    Icon(
                                        if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        stringResource(if (track.isPlaying) R.string.bridge_pause else R.string.bridge_play)
                                    )
                                }
                                IconButton(
                                    onClick = NotificationBridgeRuntime::next,
                                    enabled = track.canNext
                                ) {
                                    Icon(Icons.Default.SkipNext, stringResource(R.string.bridge_next))
                                }
                            }
                            Text(
                                stringResource(R.string.bridge_controls_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            item {
                BridgeSection(stringResource(R.string.bridge_media_apps)) {
                    Text(stringResource(R.string.bridge_media_apps_description))
                    OutlinedTextField(
                        value = packagesDraft,
                        onValueChange = { packagesDraft = it },
                        label = { Text(stringResource(R.string.bridge_package_names)) },
                        supportingText = {
                            Text(stringResource(
                                if (packagesValid) R.string.bridge_packages_hint else R.string.bridge_packages_invalid
                            ))
                        },
                        isError = !packagesValid,
                        minLines = 2,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { preferences.setMediaPackages(editedPackages) },
                        enabled = packagesValid && editedPackages != settings.mediaPackages
                    ) { Text(stringResource(R.string.save)) }
                }
            }
            item {
                BridgeSection(stringResource(R.string.bridge_latest_messages)) {
                    Text(stringResource(R.string.bridge_messages_privacy), style = MaterialTheme.typography.bodySmall)
                    when {
                        !settings.wechatEnabled -> Text(stringResource(R.string.bridge_wechat_disabled))
                        listenerUnavailable != null -> Text(stringResource(listenerUnavailable))
                        messages.isEmpty() -> Text(stringResource(R.string.bridge_no_messages))
                        else -> messages.take(3).forEachIndexed { index, message ->
                            if (index > 0) HorizontalDivider()
                            Text(message.sender, style = MaterialTheme.typography.titleSmall)
                            Text(message.text)
                            Text(
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun BridgeToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

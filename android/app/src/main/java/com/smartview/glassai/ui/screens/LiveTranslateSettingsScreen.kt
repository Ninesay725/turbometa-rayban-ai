package com.smartview.glassai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartview.glassai.R
import com.smartview.glassai.translation.*

/** Preferences only: opening this page creates no service, microphone, camera or audio route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslateSettingsScreen(onBackClick: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val preferences = remember(app) { TranslatePreferences.getInstance(app) }
    val settings by preferences.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.translate_settings_title)) },
            navigationIcon = { IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text(stringResource(R.string.translate_settings_next_start)) }
            item {
                TranslationChoice(stringResource(R.string.translate_source_language), settings.sourceLanguage,
                    TranslateLanguage.entries, { it.label }) {
                    preferences.update(preferences.settings.value.copy(sourceLanguage = it))
                }
            }
            item {
                TranslationChoice(stringResource(R.string.translate_target_language), settings.targetLanguage,
                    TranslateLanguage.entries.filter { it.audioTarget }, { it.label }) {
                    preferences.update(preferences.settings.value.copy(targetLanguage = it))
                }
                val canSwap = settings.sourceLanguage.audioTarget && settings.sourceLanguage != settings.targetLanguage
                TextButton(onClick = {
                    val current = preferences.settings.value
                    if (current.sourceLanguage.audioTarget) preferences.update(current.copy(
                        sourceLanguage = current.targetLanguage, targetLanguage = current.sourceLanguage))
                }, enabled = canSwap) { Text(stringResource(R.string.translate_swap_languages)) }
                if (!settings.sourceLanguage.audioTarget) {
                    Text(stringResource(R.string.translate_swap_unavailable), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                TranslationChoice(stringResource(R.string.translate_voice), settings.voice,
                    TranslateVoice.entries, { it.label }, { it.supports(settings.targetLanguage) }) {
                    preferences.update(preferences.settings.value.copy(voice = it))
                }
                Text(stringResource(R.string.translate_voice_hint), style = MaterialTheme.typography.bodySmall)
            }
            item {
                TranslationToggle(stringResource(R.string.translate_audio_enabled),
                    stringResource(R.string.translate_audio_hint), settings.audioEnabled) {
                    preferences.update(preferences.settings.value.copy(audioEnabled = it))
                }
            }
            item {
                TranslationToggle(stringResource(R.string.translate_image_enabled),
                    stringResource(R.string.translate_image_hint), settings.imageEnabled) {
                    preferences.update(preferences.settings.value.copy(imageEnabled = it))
                }
            }
            item {
                TranslationToggle(stringResource(R.string.translate_use_phone_mic),
                    stringResource(R.string.translate_phone_mic_hint), settings.usePhoneMic) {
                    preferences.update(preferences.settings.value.copy(usePhoneMic = it))
                }
            }
            item { Text(stringResource(R.string.translate_privacy), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun <T> TranslationChoice(
    title: String, selected: T, values: List<T>, label: (T) -> String,
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(label(selected), modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp)) {
                values.forEach { value -> DropdownMenuItem(text = { Text(label(value)) },
                    enabled = enabled(value), onClick = { expanded = false; onSelect(value) }) }
            }
        }
    }
}

@Composable
private fun TranslationToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

package com.smartview.glassai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartview.glassai.R
import com.smartview.glassai.bridge.BridgePreferences
import com.smartview.glassai.services.assistant.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(onBackClick: () -> Unit, onNotificationSettingsClick: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val bridge = remember(app) { BridgePreferences.getInstance(app) }
    var notificationsEnabled by remember { mutableStateOf(bridge.settings.value.aiNotificationsEnabled) }
    var notificationPackages by remember { mutableStateOf(bridge.settings.value.aiNotificationPackages.sorted().joinToString("\n")) }
    var packagesError by remember { mutableStateOf(false) }
    // Deliberately not rememberSaveable: this draft contains a secret.
    var draft by remember { mutableStateOf(AssistantConfig()) }
    var store by remember { mutableStateOf<AssistantSettingsStore?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AssistantSettingsError?>(null) }
    LaunchedEffect(app) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { AssistantSettingsStore(app).let { it to it.load() } }
        }
        loaded.onSuccess { (storage, config) -> store = storage; draft = config }
            .onFailure { error = AssistantSettingsError.STORAGE }
        loading = false
    }
    val editable = !loading && !saving && store != null
    fun update(value: AssistantConfig) { draft = value; saved = false; error = null }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.assistant_settings_title)) },
            navigationIcon = { IconButton(onClick = onBackClick, enabled = !saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.assistant_settings_protocol), style = MaterialTheme.typography.bodyMedium) }
            if (loading || saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                OutlinedTextField(value = draft.endpoint, onValueChange = { update(draft.copy(endpoint = it)) },
                    label = { Text(stringResource(R.string.assistant_endpoint)) },
                    placeholder = { Text("https://api.example.com/v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = draft.model, onValueChange = { update(draft.copy(model = it)) },
                    label = { Text(stringResource(R.string.assistant_model)) },
                    singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = draft.apiKey, onValueChange = { update(draft.copy(apiKey = it)) },
                    label = { Text(stringResource(R.string.assistant_api_key)) },
                    supportingText = { Text(stringResource(R.string.assistant_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            }
            item {
                AssistantSettingToggle(R.string.assistant_supports_images, R.string.assistant_images_hint,
                    draft.supportsImages, editable) { update(draft.copy(supportsImages = it)) }
                AssistantSettingToggle(R.string.assistant_supports_tools, R.string.assistant_tools_hint,
                    draft.supportsTools, editable) { update(draft.copy(supportsTools = it)) }
                AssistantSettingToggle(R.string.assistant_speak_replies, R.string.assistant_speech_hint,
                    draft.speakReplies, editable) { update(draft.copy(speakReplies = it)) }
                AssistantSettingToggle(R.string.assistant_insecure_http, R.string.assistant_insecure_hint,
                    draft.allowInsecureHttp, editable) { update(draft.copy(allowInsecureHttp = it)) }
            }
            item { Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodySmall) }
            item {
                Text(stringResource(R.string.assistant_notification_settings), style = MaterialTheme.typography.titleMedium)
                AssistantSettingToggle(R.string.bridge_ai_notifications_enabled, R.string.bridge_ai_notifications_description,
                    notificationsEnabled, editable) { notificationsEnabled = it; saved = false }
                Text(stringResource(R.string.assistant_notifications_privacy), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("com.tencent.mm" to R.string.assistant_app_wechat, "com.tencent.mobileqq" to R.string.assistant_app_qq).forEach { (pkg, label) ->
                        val selected = pkg in assistantPackageTokens(notificationPackages)
                        FilterChip(selected = selected, enabled = editable, onClick = {
                            val packages = assistantPackageTokens(notificationPackages).toMutableSet()
                            if (selected) packages.remove(pkg) else packages.add(pkg)
                            notificationPackages = packages.joinToString("\n")
                            packagesError = false
                            saved = false
                        }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(value = notificationPackages, onValueChange = {
                    notificationPackages = it; packagesError = false; saved = false
                }, enabled = editable, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                    isError = packagesError,
                    label = { Text(stringResource(R.string.bridge_ai_notification_packages)) },
                    supportingText = { Text(stringResource(if (packagesError) R.string.assistant_packages_invalid else R.string.assistant_packages_hint)) })
                TextButton(onClick = onNotificationSettingsClick, enabled = !saving) {
                    Text(stringResource(R.string.assistant_notification_access))
                }
            }
            error?.let { problem -> item {
                Text(stringResource(if (problem == AssistantSettingsError.INVALID_CONFIG)
                    R.string.assistant_invalid_config else R.string.assistant_storage_error), color = MaterialTheme.colorScheme.error)
            } }
            if (saved) item { Text(stringResource(R.string.assistant_saved), color = MaterialTheme.colorScheme.primary) }
            item {
                Button(onClick = {
                    val storage = store ?: return@Button
                    val packages = parseAssistantNotificationPackages(notificationPackages)
                    if (packages == null) { packagesError = true; return@Button }
                    val enableNotifications = notificationsEnabled
                    val candidate = draft
                    saving = true
                    saved = false
                    scope.launch {
                        error = withContext(Dispatchers.IO) { storage.save(candidate) }
                        if (error == null) {
                            if (!enableNotifications && bridge.settings.value.aiNotificationsEnabled) bridge.setAiNotificationsEnabled(false)
                            if (packages != bridge.settings.value.aiNotificationPackages) bridge.setAiNotificationPackages(packages)
                            if (enableNotifications != bridge.settings.value.aiNotificationsEnabled) bridge.setAiNotificationsEnabled(enableNotifications)
                        }
                        saving = false
                        saved = error == null
                    }
                }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.assistant_save))
                }
            }
        }
    }
}

private fun assistantPackageTokens(text: String): Set<String> = text.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty).toSet()

internal fun parseAssistantNotificationPackages(text: String): Set<String>? = assistantPackageTokens(text).takeIf { packages ->
    packages.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }
}

@Composable
private fun AssistantSettingToggle(title: Int, description: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled,
        role = Role.Switch, onValueChange = onChange).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

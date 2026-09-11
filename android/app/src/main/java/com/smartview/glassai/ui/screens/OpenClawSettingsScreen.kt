package com.smartview.glassai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartview.glassai.R
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import com.smartview.glassai.services.openclaw.OpenClawProtocol
import com.smartview.glassai.ui.components.openClawStatusColor
import com.smartview.glassai.ui.components.openClawStatusText
import com.smartview.glassai.ui.theme.AppRadius
import com.smartview.glassai.ui.theme.AppSpacing
import com.smartview.glassai.ui.theme.OpenClawColor
import com.smartview.glassai.ui.theme.Warning

/** Gateway host/port/scheme/token, status with pairing hint, connect/disconnect (research §5.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val service = remember { OpenClawNodeService.getInstance(context) }
    val connectionState by service.connectionState.collectAsState()

    var host by remember { mutableStateOf(service.gatewayHost) }
    var portText by remember { mutableStateOf(service.gatewayPort.toString()) }
    var scheme by remember { mutableStateOf(service.gatewayScheme) }
    var token by remember { mutableStateOf(service.loadGatewayToken() ?: "") }
    val isConnected = connectionState == OpenClawConnectionState.Connected

    /** Persists the four fields without dialing (Task 9 D-3: "Done" used to discard edits). */
    fun save() {
        service.gatewayHost = host.trim()
        service.gatewayPort = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: OpenClawProtocol.DEFAULT_PORT
        service.gatewayScheme = scheme
        service.saveGatewayToken(token) // blank deletes
    }

    fun saveAndConnect() {
        save()
        // The explicit button dials now, even mid-backoff (auto-connect elsewhere never does).
        service.connect(force = true)
    }

    /** Both back affordances persist first; neither connects. */
    fun saveAndLeave() {
        save()
        onBackClick()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.openclaw_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { saveAndLeave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { TextButton(onClick = { saveAndLeave() }) { Text(stringResource(R.string.done)) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
        ) {
            SectionCard(title = stringResource(R.string.openclaw_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.openclaw_status_title), modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(openClawStatusColor(connectionState)))
                    Spacer(modifier = Modifier.width(AppSpacing.small))
                    Text(openClawStatusText(connectionState), color = openClawStatusColor(connectionState))
                }
                if (connectionState == OpenClawConnectionState.WaitingForPairing) {
                    Spacer(modifier = Modifier.height(AppSpacing.small))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.small))
                        Text(stringResource(R.string.openclaw_pairing_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionCard(title = stringResource(R.string.openclaw_gateway_section)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.openclaw_host)) },
                    placeholder = { Text(OpenClawProtocol.DEFAULT_HOST) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.openclaw_port)) },
                    placeholder = { Text(OpenClawProtocol.DEFAULT_PORT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(stringResource(R.string.openclaw_scheme), modifier = Modifier.weight(1f))
                    FilterChip(selected = scheme == OpenClawProtocol.SCHEME_WS, onClick = { scheme = OpenClawProtocol.SCHEME_WS }, label = { Text("ws://") })
                    FilterChip(selected = scheme == OpenClawProtocol.SCHEME_WSS, onClick = { scheme = OpenClawProtocol.SCHEME_WSS }, label = { Text("wss://") })
                }
                Spacer(modifier = Modifier.height(AppSpacing.small))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.openclaw_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Text(stringResource(R.string.openclaw_gateway_help), style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(AppSpacing.medium))
                if (isConnected) {
                    OutlinedButton(
                        onClick = { service.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.openclaw_disconnect)) }
                } else {
                    Button(
                        onClick = { saveAndConnect() },
                        enabled = host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenClawColor)
                    ) { Text(stringResource(R.string.openclaw_connect)) }
                }
            }

            SectionCard(title = stringResource(R.string.openclaw_capabilities)) {
                InfoRow(stringResource(R.string.openclaw_node_id), if (isConnected) service.nodeId else "-")
                Spacer(modifier = Modifier.height(AppSpacing.small))
                InfoRow(stringResource(R.string.openclaw_commands), OpenClawProtocol.COMMANDS.joinToString(", "))
                Spacer(modifier = Modifier.height(AppSpacing.small))
                Text(stringResource(R.string.openclaw_capabilities_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = AppSpacing.small)
        )
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AppRadius.medium)) {
            Column(modifier = Modifier.padding(AppSpacing.medium)) { content() }
        }
    }
}

/**
 * Label/value row. The value gets the larger weight (Task 9 D-1): the long "Commands" list used to
 * take its intrinsic width and squeeze the label to one character per line.
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value,
            modifier = Modifier.weight(2f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

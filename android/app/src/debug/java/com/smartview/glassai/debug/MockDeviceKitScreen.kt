package com.smartview.glassai.debug

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.smartview.glassai.R
import com.smartview.glassai.ui.theme.AppSpacing
import com.smartview.glassai.ui.theme.Error
import com.smartview.glassai.ui.theme.Primary
import com.smartview.glassai.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockDeviceKitScreen(
    onBackClick: () -> Unit,
    viewModel: MockDeviceKitViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mock_device_kit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.mock_device_kit_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (uiState.isEnabled) {
                            Text(
                                text = stringResource(R.string.mock_paired_count, uiState.pairedDevices.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Success,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.mock_device_kit_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    if (uiState.isEnabled) {
                        ActionButton(
                            text = stringResource(R.string.mock_disable),
                            onClick = { viewModel.disable() },
                            containerColor = Error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ActionButton(
                            text = stringResource(R.string.mock_pair_rayban),
                            onClick = { viewModel.pairGlasses() },
                            enabled = uiState.pairedDevices.size < MockDeviceKitViewModel.MAX_DEVICES,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ActionButton(
                            text = stringResource(R.string.mock_enable),
                            onClick = { viewModel.enable() },
                            containerColor = Success,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    uiState.lastError?.let { error ->
                        Text(text = error, color = Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (uiState.isEnabled) {
                uiState.pairedDevices.forEach { info ->
                    MockDeviceCard(info = info, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Primary,
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = Color.White),
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MockDeviceCard(info: MockDeviceInfo, viewModel: MockDeviceKitViewModel) {
    val context = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setCameraFeed(info, it) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setCapturedImage(info, it) }
    }

    var pendingFacing by remember { mutableStateOf<CameraFacing?>(null) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingFacing?.let { viewModel.setCameraFeed(info, it) }
        } else {
            showCameraPermissionDialog = true
        }
        pendingFacing = null
    }

    val usesPhoneCamera = info.cameraSource != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // D2: resolved here, not in the ViewModel, so the in-app language switch applies.
                        stringResource(R.string.mock_device_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(info.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { viewModel.unpairDevice(info) }) {
                    Text(stringResource(R.string.mock_unpair), color = Error)
                }
            }
            HorizontalDivider()

            ToggleRow(stringResource(R.string.mock_power), info.isPoweredOn) { on ->
                if (on) viewModel.powerOn(info) else viewModel.powerOff(info)
            }
            ToggleRow(stringResource(R.string.mock_donned), info.isDonned) { on ->
                if (on) viewModel.don(info) else viewModel.doff(info)
            }
            ToggleRow(stringResource(R.string.mock_unfolded), info.isUnfolded) { on ->
                if (on) viewModel.unfold(info) else viewModel.fold(info)
            }

            Text(
                text = stringResource(R.string.mock_captouch_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                ActionButton(
                    text = stringResource(R.string.mock_captouch_tap),
                    onClick = { viewModel.tap(info) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = stringResource(R.string.mock_captouch_tap_and_hold),
                    onClick = { viewModel.tapAndHold(info) },
                    modifier = Modifier.weight(1f),
                )
            }

            CameraSourceDropdown(
                info = info,
                onFrontCamera = {
                    pendingFacing = CameraFacing.FRONT
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onBackCamera = {
                    pendingFacing = CameraFacing.BACK
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onVideoFile = { videoPicker.launch("video/*") },
            )
            Text(
                text = stringResource(R.string.mock_video_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!usesPhoneCamera) {
                if (info.hasCapturedImage) {
                    Text(
                        text = stringResource(R.string.mock_has_captured_image),
                        style = MaterialTheme.typography.bodySmall,
                        color = Success,
                    )
                }
                ActionButton(
                    text = stringResource(R.string.mock_select_image),
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            title = { Text(stringResource(R.string.permission_required)) },
            text = { Text(stringResource(R.string.mock_camera_permission_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    showCameraPermissionDialog = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) {
                    Text(stringResource(R.string.mock_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CameraSourceDropdown(
    info: MockDeviceInfo,
    onFrontCamera: () -> Unit,
    onBackCamera: () -> Unit,
    onVideoFile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = when {
        info.cameraSource == CameraFacing.FRONT -> stringResource(R.string.mock_front_camera)
        info.cameraSource == CameraFacing.BACK -> stringResource(R.string.mock_back_camera)
        info.hasCameraFeed -> stringResource(R.string.mock_camera_source_video)
        else -> stringResource(R.string.mock_camera_source_none)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.mock_camera_source, currentLabel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mock_front_camera)) },
                onClick = { onFrontCamera(); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mock_back_camera)) },
                onClick = { onBackCamera(); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mock_camera_source_video)) },
                onClick = { onVideoFile(); expanded = false },
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
    }
}

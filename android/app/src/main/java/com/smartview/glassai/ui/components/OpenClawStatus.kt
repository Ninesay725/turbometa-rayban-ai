package com.smartview.glassai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.smartview.glassai.R
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawErrorReason
import com.smartview.glassai.ui.theme.Error
import com.smartview.glassai.ui.theme.Success
import com.smartview.glassai.ui.theme.TextTertiaryLight
import com.smartview.glassai.ui.theme.Warning

@Composable
fun openClawStatusText(state: OpenClawConnectionState): String = when (state) {
    OpenClawConnectionState.Connected -> stringResource(R.string.openclaw_status_connected)
    OpenClawConnectionState.Connecting -> stringResource(R.string.openclaw_status_connecting)
    is OpenClawConnectionState.Reconnecting -> stringResource(R.string.openclaw_status_reconnecting, state.attempt)
    OpenClawConnectionState.WaitingForPairing -> stringResource(R.string.openclaw_status_pairing)
    OpenClawConnectionState.Disconnected -> stringResource(R.string.openclaw_status_disconnected)
    is OpenClawConnectionState.Error -> when (val reason = state.reason) {
        is OpenClawErrorReason.MaxRetries -> stringResource(R.string.openclaw_error_max_retries, reason.attempts)
        is OpenClawErrorReason.Transport -> stringResource(R.string.openclaw_error_transport, reason.detail)
        OpenClawErrorReason.InvalidUrl -> stringResource(R.string.openclaw_error_invalid_url)
    }
}

@Composable
fun openClawStatusColor(state: OpenClawConnectionState): Color = when (state) {
    OpenClawConnectionState.Connected -> Success
    OpenClawConnectionState.Connecting -> Color(0xFFFF9800)
    is OpenClawConnectionState.Reconnecting -> Color(0xFFFF9800)
    OpenClawConnectionState.WaitingForPairing -> Warning
    OpenClawConnectionState.Disconnected -> TextTertiaryLight
    is OpenClawConnectionState.Error -> Error
}

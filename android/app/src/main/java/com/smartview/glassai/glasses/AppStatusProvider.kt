package com.smartview.glassai.glasses

import com.smartview.glassai.services.openclaw.OpenClawConnectionState

/** Resolves readiness on each send; construction does not read keys or connect OpenClaw. */
class AppStatusProvider(
    private val sessionManager: GlassesSessionManager,
    private val hasLiveAiKey: () -> Boolean,
    private val openClawState: () -> OpenClawConnectionState,
) : DisplayStatusProvider {
    override fun currentStatus(): DisplayCard.Status = DisplayCard.Status(
        deviceName = sessionManager.activeDevice.value?.name.orEmpty(),
        liveAiReady = hasLiveAiKey(),
        openClawConnected = openClawState() == OpenClawConnectionState.Connected,
    )
}

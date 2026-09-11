package com.smartview.glassai.services.openclaw

import android.app.Application
import android.util.Log
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionFrameProvider

/**
 * Installs the command router into the OpenClaw singleton once per process. Nothing here does I/O:
 * the settings store opens EncryptedSharedPreferences lazily and the Ed25519 identity loads on the
 * first connect(). It is still wrapped in runCatching so a feature-level failure can never turn
 * into a crash in Application.onCreate before the user can reach Settings.
 *
 * It does start the glasses device observer (Task 4 review finding 1): activeDevice is filled
 * asynchronously, so unless the flow is already running the first camera.list / device.status after
 * process start would report no glasses. startMonitoring() is idempotent and getInstance() already
 * calls it; the explicit call documents the dependency and survives a refactor of getInstance().
 */
object OpenClawIntegration {
    private const val TAG = "OpenClawIntegration"

    fun install(app: Application) {
        runCatching {
            GlassesSessionManager.getInstance(app).startMonitoring()
            OpenClawNodeService.getInstance(app).setCommandRouter(
                OpenClawCommandRouter(
                    frames = SessionFrameProvider.create(app),
                    deviceInfo = OpenClawDeviceInfoSource.fromBuild(),
                )
            )
        }.onFailure { Log.e(TAG, "OpenClaw unavailable: ${it.message}", it) }
    }
}

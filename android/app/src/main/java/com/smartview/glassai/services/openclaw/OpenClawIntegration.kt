package com.smartview.glassai.services.openclaw

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartview.glassai.glasses.GlassesFrameProvider
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionFrameProvider

/**
 * Installs the command router into the OpenClaw singleton once per process. Nothing here does I/O:
 * the settings store opens EncryptedSharedPreferences lazily and the Ed25519 identity loads on the
 * first connect(). It is still wrapped in runCatching so a feature-level failure can never turn
 * into a crash in Application.onCreate before the user can reach Settings.
 *
 * It also starts the glasses device observer (Task 4 review finding 1): activeDevice is filled
 * asynchronously, so unless the flow is already running the first camera.list / device.status after
 * process start would report no glasses. That only happens once BLUETOOTH_CONNECT is granted
 * (final review I2c): before the grant there cannot be a device — `camera.list` answering `[]` is
 * the correct answer — and touching the manager would create it (and its observer) at a point where
 * the SDK flows may never emit. MainActivity re-arms monitoring right after the grant through
 * WearablesViewModel.startMonitoring().
 */
object OpenClawIntegration {
    private const val TAG = "OpenClawIntegration"

    @Volatile
    private var provider: SessionFrameProvider? = null

    /**
     * The one SessionFrameProvider of this process (final review I1). Every capture borrows the
     * camera as owner "OpenClawSnap"; a second instance would duplicate that owner name, and two
     * overlapping captures would release each other's camera and session. The gateway router and
     * OpenClawViewModel therefore share this instance (the capture path is also serialised by a
     * companion-level Mutex, so the guarantee does not depend on the sharing alone).
     */
    fun frameProvider(app: Application): GlassesFrameProvider =
        provider ?: synchronized(this) {
            provider ?: SessionFrameProvider.create(app).also { provider = it }
        }

    fun install(app: Application) {
        runCatching {
            if (hasBluetoothConnect(app)) {
                GlassesSessionManager.getInstance(app).startMonitoring()
            } else {
                Log.i(TAG, "BLUETOOTH_CONNECT not granted yet; device monitoring starts after the grant")
            }
            OpenClawNodeService.getInstance(app).setCommandRouter(
                OpenClawCommandRouter(
                    frames = frameProvider(app),
                    deviceInfo = OpenClawDeviceInfoSource.fromBuild(),
                )
            )
        }.onFailure { Log.e(TAG, "OpenClaw unavailable: ${it.message}", it) }
    }

    private fun hasBluetoothConnect(app: Application): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

package com.smartview.glassai.glasses

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartview.glassai.managers.APIProviderManager
import com.smartview.glassai.services.QuickVisionService
import com.smartview.glassai.services.openclaw.OpenClawConnectionState
import com.smartview.glassai.services.openclaw.OpenClawNodeService
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process wiring. First use belongs after initialization and the Bluetooth permission grant. */
object GlassesDisplayIntegration {
    private const val TAG = "GlassesDisplayIntegration"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = MutableSharedFlow<NavigationRequest>(replay = 0, extraBufferCapacity = 4)
    val navigationRequests: SharedFlow<NavigationRequest> = navigation.asSharedFlow()

    @Volatile
    private var instance: GlassesDisplayManager? = null
    @Volatile
    private var routerInstance: GlassesActionRouter? = null
    @Volatile
    private var started = false

    internal var statusProviderFactory: (Application) -> DisplayStatusProvider = ::appStatusProvider

    fun displayManager(app: Application): GlassesDisplayManager =
        instance ?: synchronized(this) {
            instance ?: GlassesDisplayManager(
                sessionManager = GlassesSessionManager.getInstance(app),
                scope = scope,
                strings = ResourceDisplayStrings(app),
                statusProvider = statusProviderFactory(app),
                mainDispatcher = Dispatchers.Main.immediate,
                sendDispatcher = Dispatchers.IO,
                clock = SystemClock::elapsedRealtime,
            ).also { instance = it }
        }

    fun router(app: Application): GlassesActionRouter =
        routerInstance ?: synchronized(this) {
            routerInstance ?: GlassesActionRouter(
                sink = displayManager(app),
                navigation = navigation,
                startQuickVision = {
                    app.startForegroundService(Intent(app, QuickVisionService::class.java).apply {
                        action = QuickVisionService.ACTION_CAPTURE_AND_ANALYZE
                    })
                },
            ).also { routerInstance = it }
        }

    /** Safe at Application.onCreate: no eager key reads, and no DAT access before permission. */
    fun install(app: Application) {
        runCatching {
            statusProviderFactory = ::appStatusProvider
            if (hasBluetoothConnect(app)) ensureStarted(app)
        }.onFailure { Log.e(TAG, "Glasses display unavailable", it) }
    }

    /** Main thread, after the Bluetooth grant; repeated calls install neither a router nor a collector twice. */
    fun ensureStarted(app: Application) {
        if (started || !hasBluetoothConnect(app)) return
        synchronized(this) {
            if (started) return
            val manager = displayManager(app)
            val sessions = GlassesSessionManager.getInstance(app)
            manager.actionHandler = router(app)::dispatch
            scope.launch {
                OpenClawNodeService.getInstance(app).connectionState
                    .map { it == OpenClawConnectionState.Connected }
                    .distinctUntilChanged()
                    .collect {
                        if (manager.currentCard.value == null &&
                            sessions.displayState.value == GlassesDisplayState.STARTED
                        ) {
                            manager.showStatus()
                        }
                    }
            }
            started = true
        }
    }

    private fun appStatusProvider(app: Application): DisplayStatusProvider = AppStatusProvider(
        sessionManager = GlassesSessionManager.getInstance(app),
        hasLiveAiKey = {
            APIProviderManager.getInstance(app).getLiveAIAPIKey(APIKeyManager.getInstance(app)).isNotBlank()
        },
        openClawState = { OpenClawNodeService.getInstance(app).connectionState.value },
    )

    private fun hasBluetoothConnect(app: Application): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

class DeviceNameStatusProvider(private val sessionManager: GlassesSessionManager) : DisplayStatusProvider {
    override fun currentStatus(): DisplayCard.Status = DisplayCard.Status(
        deviceName = sessionManager.activeDevice.value?.name.orEmpty(),
        liveAiReady = false,
        openClawConnected = false,
    )
}

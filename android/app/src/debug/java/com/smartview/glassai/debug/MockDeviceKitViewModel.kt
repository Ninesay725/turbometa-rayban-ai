package com.smartview.glassai.debug

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MockDeviceInfo(
    val device: MockGlasses,
    /**
     * The SDK's own DeviceIdentifier — stable across re-entry (so rehydration matches) and never
     * localized. The display name is resolved with stringResource() in MockDeviceKitScreen so the
     * in-app language switch applies to it (D2).
     */
    val deviceId: String,
    val hasCameraFeed: Boolean = false,
    val hasCapturedImage: Boolean = false,
    val cameraSource: CameraFacing? = null,
    // Null for an externally paired device whose state this process has never recorded.
    val isPoweredOn: Boolean? = null,
    val isDonned: Boolean? = null,
    val isUnfolded: Boolean? = null,
)

data class MockDeviceKitUiState(
    val isEnabled: Boolean = false,
    val pairedDevices: List<MockDeviceInfo> = emptyList(),
    val lastError: String? = null,
)

/**
 * Drives MockDeviceKit (debug builds only). Mirrors the 0.9.0 CameraAccess sample:
 * enable/disable, pair up to 3 mock Ray-Ban Meta, power/don/fold, camera feeds, cap-touch.
 * MockDeviceKit.enable() registers the app automatically (MockDeviceKitConfig default), so the
 * Home screen shows "Connected" once a mock device is powered on and worn.
 */
class MockDeviceKitViewModel internal constructor(
    application: Application,
    private val mockDeviceKit: MockDeviceKitInterface,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, MockDeviceKit.getInstance(application.applicationContext))

    companion object {
        private const val TAG = "MockDeviceKitViewModel"
        const val MAX_DEVICES = 3
    }

    private val _uiState = MutableStateFlow(MockDeviceKitUiState(isEnabled = mockDeviceKit.isEnabled))
    val uiState: StateFlow<MockDeviceKitUiState> = _uiState.asStateFlow()

    init {
        // D1: the ViewModel is scoped to the NavBackStackEntry, but MockDeviceKit keeps the devices
        // paired for the whole process. Without this, leaving and re-entering the screen showed
        // "0 paired" while the SDK still held up to MAX_DEVICES, so the cards became uncontrollable
        // and re-pairing walked past the cap. Restore successful commands from the process store;
        // without a record the state stays unknown, because DAT exposes no getters for it.
        if (mockDeviceKit.isEnabled) {
            val existing = mockDeviceKit.pairedDevices.filterIsInstance<MockGlasses>().map(::infoFor)
            if (existing.isNotEmpty()) {
                _uiState.update { it.copy(pairedDevices = existing) }
                Log.d(TAG, "Rehydrated ${existing.size} already-paired mock device(s)")
            }
        } else {
            MockDeviceState.clear()
        }
    }

    private fun infoFor(device: MockGlasses): MockDeviceInfo {
        val deviceId = device.deviceIdentifier.identifier
        val flags = MockDeviceState.get(deviceId)
        return MockDeviceInfo(
            device = device,
            deviceId = deviceId,
            isPoweredOn = flags.isPoweredOn,
            isDonned = flags.isDonned,
            isUnfolded = flags.isUnfolded,
        )
    }

    fun enable() {
        mockDeviceKit.enable()
        _uiState.update { it.copy(isEnabled = true, lastError = null) }
    }

    fun disable() {
        mockDeviceKit.disable()
        MockDeviceState.clear()
        _uiState.update { it.copy(isEnabled = false, pairedDevices = emptyList(), lastError = null) }
    }

    fun pairGlasses() {
        if (_uiState.value.pairedDevices.size >= MAX_DEVICES) return
        mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).fold(
            onSuccess = { device ->
                // The pinned 0.9 MockGlasses starts powered off, unworn and folded. Only a fresh
                // pair establishes these defaults; rehydrating an existing device must not.
                MockDeviceState.update(device.deviceIdentifier.identifier) { MockDeviceFlags(false, false, false) }
                val info = infoFor(device)
                _uiState.update { it.copy(pairedDevices = it.pairedDevices + info, lastError = null) }
                Log.d(TAG, "Paired mock Ray-Ban Meta ${info.deviceId}")
            },
            onFailure = { error, _ ->
                Log.e(TAG, "pairGlasses failed: ${error.description}")
                _uiState.update { it.copy(lastError = error.description) }
            },
        )
    }

    fun unpairDevice(info: MockDeviceInfo) {
        runCatching { mockDeviceKit.unpairDevice(info.device) }
            .onSuccess {
                MockDeviceState.remove(info.deviceId)
                _uiState.update { state ->
                    state.copy(pairedDevices = state.pairedDevices.filter { it.deviceId != info.deviceId }, lastError = null)
                }
            }
            .onFailure { error ->
                Log.e(TAG, "unpairDevice failed", error)
                _uiState.update { it.copy(lastError = "unpairDevice: ${error.message}") }
            }
    }

    fun powerOn(info: MockDeviceInfo) = execute(info, "powerOn", { it.copy(isPoweredOn = true) }) { it.powerOn() }

    /** DAT 0.9 changes power only; worn and hinge states survive a power cycle. */
    fun powerOff(info: MockDeviceInfo) =
        execute(info, "powerOff", { it.copy(isPoweredOn = false) }) { it.powerOff() }

    /** don() auto-unfolds on the mock device. */
    fun don(info: MockDeviceInfo) = execute(info, "don", { it.copy(isDonned = true, isUnfolded = true) }) { it.don() }

    fun doff(info: MockDeviceInfo) = execute(info, "doff", { it.copy(isDonned = false) }) { it.doff() }

    fun fold(info: MockDeviceInfo) = execute(info, "fold", { it.copy(isUnfolded = false, isDonned = false) }) { it.fold() }

    fun unfold(info: MockDeviceInfo) = execute(info, "unfold", { it.copy(isUnfolded = true) }) { it.unfold() }

    /** Single cap-touch tap: pauses/resumes the active stream (StreamState.PAUSED). */
    fun tap(info: MockDeviceInfo) = execute(info, "captouch.tap") { it.services.captouch.tap() }

    /** Tap-and-hold: stops the active session. */
    fun tapAndHold(info: MockDeviceInfo) = execute(info, "captouch.tapAndHold") { it.services.captouch.tapAndHold() }

    /** H.265 video file streamed as the camera feed. Mutually exclusive with the phone camera. */
    fun setCameraFeed(info: MockDeviceInfo, uri: Uri) =
        execute(info, "setCameraFeed(uri)", { it.copy(hasCameraFeed = true, cameraSource = null) }) {
            it.services.camera.setCameraFeed(uri)
        }

    /** Phone camera as feed (needs android.permission.CAMERA at runtime). */
    fun setCameraFeed(info: MockDeviceInfo, facing: CameraFacing) =
        execute(info, "setCameraFeed($facing)", { it.copy(cameraSource = facing, hasCameraFeed = false) }) {
            it.services.camera.setCameraFeed(facing)
        }

    /** Image returned by Stream.capturePhoto() (rotated 90 degrees like a real device). */
    fun setCapturedImage(info: MockDeviceInfo, uri: Uri) =
        execute(info, "setCapturedImage", { it.copy(hasCapturedImage = true) }) {
            it.services.camera.setCapturedImage(uri)
        }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    private fun execute(
        info: MockDeviceInfo,
        operation: String,
        updateInfo: (MockDeviceInfo) -> MockDeviceInfo = { it },
        block: (MockGlasses) -> Unit,
    ) {
        // Callbacks can carry an older rendered info. Transform the latest state so one command
        // cannot undo a different toggle (or resurrect a device that has been unpaired).
        val current = _uiState.value.pairedDevices.firstOrNull { it.deviceId == info.deviceId } ?: return
        try {
            Log.d(TAG, "$operation on ${info.deviceId}")
            block(current.device)
            val updated = updateInfo(current)
            MockDeviceState.update(updated.deviceId) {
                MockDeviceFlags(updated.isPoweredOn, updated.isDonned, updated.isUnfolded)
            }
            _uiState.update { state ->
                state.copy(
                    pairedDevices = state.pairedDevices.map { if (it.deviceId == updated.deviceId) updated else it },
                    lastError = null,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "$operation failed on ${info.deviceId}", e)
            _uiState.update { it.copy(lastError = "$operation: ${e.message}") }
        }
    }
}

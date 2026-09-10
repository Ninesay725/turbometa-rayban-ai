package com.smartview.glassai.debug

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.smartview.glassai.R
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MockDeviceInfo(
    val device: MockGlasses,
    val deviceId: String,
    val deviceName: String,
    val hasCameraFeed: Boolean = false,
    val hasCapturedImage: Boolean = false,
    val cameraSource: CameraFacing? = null,
    val isPoweredOn: Boolean = false,
    val isDonned: Boolean = false,
    val isUnfolded: Boolean = false,
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
class MockDeviceKitViewModel(private val application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MockDeviceKitViewModel"
        const val MAX_DEVICES = 3
    }

    private val mockDeviceKit = MockDeviceKit.getInstance(application.applicationContext)

    private val _uiState = MutableStateFlow(MockDeviceKitUiState(isEnabled = mockDeviceKit.isEnabled))
    val uiState: StateFlow<MockDeviceKitUiState> = _uiState.asStateFlow()

    fun enable() {
        mockDeviceKit.enable()
        _uiState.update { it.copy(isEnabled = true, lastError = null) }
    }

    fun disable() {
        mockDeviceKit.disable()
        _uiState.update { it.copy(isEnabled = false, pairedDevices = emptyList(), lastError = null) }
    }

    fun pairGlasses() {
        if (_uiState.value.pairedDevices.size >= MAX_DEVICES) return
        mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).fold(
            onSuccess = { device ->
                val info = MockDeviceInfo(
                    device = device,
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = application.getString(R.string.mock_device_name),
                )
                _uiState.update { it.copy(pairedDevices = it.pairedDevices + info, lastError = null) }
                Log.d(TAG, "Paired mock Ray-Ban Meta ${info.deviceId}")
            },
            onFailure = { error, _ ->
                Log.e(TAG, "pairGlasses failed: $error")
                _uiState.update { it.copy(lastError = error.toString()) }
            },
        )
    }

    fun unpairDevice(info: MockDeviceInfo) {
        runCatching { mockDeviceKit.unpairDevice(info.device) }
            .onFailure { Log.e(TAG, "unpairDevice failed", it) }
        _uiState.update { state -> state.copy(pairedDevices = state.pairedDevices.filter { it.deviceId != info.deviceId }) }
    }

    fun powerOn(info: MockDeviceInfo) = execute(info, "powerOn", info.copy(isPoweredOn = true)) { it.powerOn() }

    fun powerOff(info: MockDeviceInfo) =
        execute(info, "powerOff", info.copy(isPoweredOn = false, isDonned = false, isUnfolded = false)) { it.powerOff() }

    /** don() auto-unfolds on the mock device. */
    fun don(info: MockDeviceInfo) = execute(info, "don", info.copy(isDonned = true, isUnfolded = true)) { it.don() }

    fun doff(info: MockDeviceInfo) = execute(info, "doff", info.copy(isDonned = false)) { it.doff() }

    fun fold(info: MockDeviceInfo) = execute(info, "fold", info.copy(isUnfolded = false, isDonned = false)) { it.fold() }

    fun unfold(info: MockDeviceInfo) = execute(info, "unfold", info.copy(isUnfolded = true)) { it.unfold() }

    /** Single cap-touch tap: pauses/resumes the active stream (StreamState.PAUSED). */
    fun tap(info: MockDeviceInfo) = execute(info, "captouch.tap", info) { it.services.captouch.tap() }

    /** Tap-and-hold: stops the active session. */
    fun tapAndHold(info: MockDeviceInfo) = execute(info, "captouch.tapAndHold", info) { it.services.captouch.tapAndHold() }

    /** H.265 video file streamed as the camera feed. Mutually exclusive with the phone camera. */
    fun setCameraFeed(info: MockDeviceInfo, uri: Uri) =
        execute(info, "setCameraFeed(uri)", info.copy(hasCameraFeed = true, cameraSource = null)) {
            it.services.camera.setCameraFeed(uri)
        }

    /** Phone camera as feed (needs android.permission.CAMERA at runtime). */
    fun setCameraFeed(info: MockDeviceInfo, facing: CameraFacing) =
        execute(info, "setCameraFeed($facing)", info.copy(cameraSource = facing, hasCameraFeed = false)) {
            it.services.camera.setCameraFeed(facing)
        }

    /** Image returned by Stream.capturePhoto() (rotated 90 degrees like a real device). */
    fun setCapturedImage(info: MockDeviceInfo, uri: Uri) =
        execute(info, "setCapturedImage", info.copy(hasCapturedImage = true)) {
            it.services.camera.setCapturedImage(uri)
        }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    private fun execute(
        info: MockDeviceInfo,
        operation: String,
        updated: MockDeviceInfo,
        block: (MockGlasses) -> Unit,
    ) {
        try {
            Log.d(TAG, "$operation on ${info.deviceId}")
            block(info.device)
            _uiState.update { state ->
                state.copy(pairedDevices = state.pairedDevices.map { if (it.deviceId == updated.deviceId) updated else it })
            }
        } catch (e: Exception) {
            Log.e(TAG, "$operation failed on ${info.deviceId}", e)
            _uiState.update { it.copy(lastError = "$operation: ${e.message}") }
        }
    }
}

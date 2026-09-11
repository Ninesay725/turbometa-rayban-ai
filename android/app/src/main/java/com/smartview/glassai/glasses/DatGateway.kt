package com.smartview.glassai.glasses

import android.app.Activity
import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.Flow
import com.meta.wearable.dat.display.types.DisplayError
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Thin seams over the DAT SDK statics so GlassesSessionManager can be unit-tested with fakes.
 * Real implementations live in WearablesDatAdapter.kt; fakes live in app/src/test/.../FakeDat.kt.
 */

sealed class SessionCreateResult {
    data class Success(val session: GlassesSession) : SessionCreateResult()
    data class Failure(val error: DeviceSessionError) : SessionCreateResult()
}

sealed class CameraAddResult {
    data class Success(val camera: GlassesCamera) : CameraAddResult()
    data class Failure(val error: DeviceSessionError) : CameraAddResult()
}

sealed class PhotoCaptureResult {
    data class Success(val photo: PhotoData) : PhotoCaptureResult()
    data class Failure(val error: CaptureError) : PhotoCaptureResult()
}

sealed class DisplaySendResult {
    data object Sent : DisplaySendResult()
    data class Failed(val error: DisplayError) : DisplaySendResult()
}

sealed class DisplayAddResult {
    data class Success(val display: GlassesDisplay) : DisplayAddResult()
    data class Failure(val error: DeviceSessionError) : DisplayAddResult()
}

/** One display capability. Both stop and close terminate it in DAT 0.9.0. */
interface GlassesDisplay {
    val state: StateFlow<DisplayState>
    suspend fun sendContent(block: ContentScope.() -> Unit): DisplaySendResult
    suspend fun clearDisplay(): DisplaySendResult
    fun stop()
    fun close()
}

/** One camera capability lent to exactly one owner. Wraps Camera + Camera.stream. */
interface GlassesCamera {
    val streamState: StateFlow<DatStreamState>
    val videoFrames: Flow<VideoFrame>
    val streamErrors: Flow<StreamError>
    /** Starts the stream; returns null on success or the StreamError on failure. */
    fun startStream(): StreamError?
    suspend fun capturePhoto(): PhotoCaptureResult
    /** Stops the camera and detaches it from the session (required before a later addCamera). */
    fun stop()
}

/** One DeviceSession. Wraps com.meta.wearable.dat.core.session.DeviceSession. */
interface GlassesSession {
    val state: StateFlow<DeviceSessionState>
    val errors: SharedFlow<DeviceSessionError>
    fun start()
    fun stop()
    fun addCamera(config: StreamConfiguration): CameraAddResult
    fun addDisplay(): DisplayAddResult
    fun removeDisplay(): DeviceSessionError?
}

interface DatSessionFactory {
    /** Wraps Wearables.createSession(deviceSelector). Synchronous. */
    fun createSession(): SessionCreateResult
}

interface DatDeviceObserver {
    /** Metadata of the selector's active device, null when none is connected. */
    fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?>
}

/** Result of the wearable CAMERA permission check (Wearables.checkPermissionStatus). */
sealed class CameraPermissionCheck {
    object Granted : CameraPermissionCheck()
    object Denied : CameraPermissionCheck()
    /** The SDK could not answer; [description] is the PermissionError description. */
    data class Failed(val description: String) : CameraPermissionCheck()
}

/**
 * The Wearables statics WearablesViewModel needs for registration, so the ViewModel can be built
 * with a fake on the JVM (Phase A review Important #4). Real implementation:
 * WearablesRegistrationGateway.
 */
interface DatRegistrationGateway {
    val registrationState: Flow<RegistrationState>
    val registrationErrors: Flow<RegistrationError>
    val devices: Flow<Set<DeviceIdentifier>>
    fun startRegistration(activity: Activity)
    fun startUnregistration(activity: Activity)
    /** @return null on success, else the SDK's localized description of the NavigationError. */
    fun openFirmwareUpdate(activity: Activity): String?
    /** @return null on success, else the SDK's localized description of the NavigationError. */
    fun openDATGlassesAppUpdate(activity: Activity): String?
    suspend fun checkCameraPermission(): CameraPermissionCheck
}

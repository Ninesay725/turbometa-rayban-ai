package com.smartview.glassai.glasses

import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType

/** Metadata of the device currently chosen by the AutoDeviceSelector (spec §5.7). */
data class GlassesDeviceInfo(
    val id: String,
    val name: String,
    val deviceType: DeviceType,
    val isDisplayCapable: Boolean,
    val compatibility: DeviceCompatibility,
)

/** Display capability lifecycle as seen by the app. Phase A never leaves NOT_ATTACHED. */
enum class GlassesDisplayState { NOT_ATTACHED, STARTING, STARTED, STOPPED }

/** Result of [GlassesSessionManager.ensureSessionStarted]. */
enum class SessionStartResult {
    /** The session is STARTED; capabilities may be added. */
    STARTED,
    /** Wearables.createSession refused (NO_ELIGIBLE_DEVICE, SESSION_ALREADY_EXISTS after one retry, ...). */
    CREATE_FAILED,
    /** The session was created but reached STOPPED, or did not reach STARTED within the timeout. */
    NOT_STARTED,
}

/** Why [GlassesSessionManager.addCamera] could not lend the camera. */
sealed class CameraError {
    /** Another feature owner currently holds the single camera capability. */
    data class CameraBusy(val owner: String) : CameraError()
    /** No DeviceSession exists (acquire() failed or the device stopped it). */
    object NoSession : CameraError()
    /** The session exists but is not STARTED yet; wait with awaitStarted(). */
    object SessionNotStarted : CameraError()
    /** The SDK refused addCamera (CAPABILITY_DENIED, CAPABILITY_ALREADY_ADDED, ...). */
    data class Sdk(val error: DeviceSessionError) : CameraError()
}

sealed class CameraResult {
    data class Ready(val camera: GlassesCamera) : CameraResult()
    data class Failed(val error: CameraError) : CameraResult()
}

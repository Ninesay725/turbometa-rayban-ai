package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** The underlying SDK session (null in fakes). Phase C uses it for addDisplay(). */
    val nativeSession: DeviceSession?
    fun start()
    fun stop()
    fun addCamera(config: StreamConfiguration): CameraAddResult
}

interface DatSessionFactory {
    /** Wraps Wearables.createSession(deviceSelector). Synchronous. */
    fun createSession(): SessionCreateResult
}

interface DatDeviceObserver {
    /** Metadata of the selector's active device, null when none is connected. */
    fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?>
}

/**
 * Phase C extension point. GlassesSessionManager calls maybeAttach() every time the session
 * reaches STARTED and detach() before the session stops. Phase A ships only [None].
 */
interface DisplayAttacher {
    val displayState: StateFlow<GlassesDisplayState>
    fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?)
    fun detach()

    object None : DisplayAttacher {
        override val displayState: StateFlow<GlassesDisplayState> =
            MutableStateFlow(GlassesDisplayState.NOT_ATTACHED)
        override fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?) = Unit
        override fun detach() = Unit
    }
}

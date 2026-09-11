package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeGlassesCamera : GlassesCamera {
    val stateFlow = MutableStateFlow(DatStreamState.STOPPED)
    val frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 4)
    val errors = MutableSharedFlow<StreamError>(extraBufferCapacity = 4)
    var startCalls = 0
    var stopCalls = 0
    var startError: StreamError? = null
    var captureResult: PhotoCaptureResult = PhotoCaptureResult.Failure(CaptureError.NotStreaming)

    override val streamState: StateFlow<DatStreamState> = stateFlow
    override val videoFrames: Flow<VideoFrame> = frames
    override val streamErrors: Flow<StreamError> = errors

    override fun startStream(): StreamError? {
        startCalls++
        if (startError == null) stateFlow.value = DatStreamState.STARTING
        return startError
    }

    override suspend fun capturePhoto(): PhotoCaptureResult = captureResult

    override fun stop() {
        stopCalls++
        stateFlow.value = DatStreamState.STOPPED
    }
}

/**
 * @param stopAsync when true, stop() only transitions to STOPPING like the real 0.9.0 DeviceSession;
 *   the test must call emitStoppedByDevice() to finish the stop. When false (default) stop() lands
 *   on STOPPED synchronously.
 */
class FakeGlassesSession(private val stopAsync: Boolean = false) : GlassesSession {
    val stateFlow = MutableStateFlow(DeviceSessionState.IDLE)
    val errorFlow = MutableSharedFlow<DeviceSessionError>(extraBufferCapacity = 16)
    var startCalls = 0
    var stopCalls = 0
    var addCameraCalls = 0
    var nextAddCameraFailure: DeviceSessionError? = null
    /** Applied to every camera this session hands out (null = FakeGlassesCamera default). */
    var nextCaptureResult: PhotoCaptureResult? = null
    /** Applied to every camera this session hands out: startStream() returns this error. */
    var nextStartError: StreamError? = null
    val cameras = mutableListOf<FakeGlassesCamera>()

    override val state: StateFlow<DeviceSessionState> = stateFlow
    override val errors: SharedFlow<DeviceSessionError> = errorFlow
    override val nativeSession: DeviceSession? = null

    override fun start() {
        startCalls++
        stateFlow.value = DeviceSessionState.STARTING
    }

    override fun stop() {
        stopCalls++
        stateFlow.value = if (stopAsync) DeviceSessionState.STOPPING else DeviceSessionState.STOPPED
    }

    override fun addCamera(config: StreamConfiguration): CameraAddResult {
        addCameraCalls++
        nextAddCameraFailure?.let { return CameraAddResult.Failure(it) }
        val camera = FakeGlassesCamera()
        nextCaptureResult?.let { camera.captureResult = it }
        nextStartError?.let { camera.startError = it }
        cameras += camera
        return CameraAddResult.Success(camera)
    }

    /** Simulates the SDK reaching STARTED. */
    fun emitStarted() {
        stateFlow.value = DeviceSessionState.STARTED
    }

    /** Simulates the device ending the session (fold, tap-and-hold, Bluetooth loss). */
    fun emitStoppedByDevice() {
        stateFlow.value = DeviceSessionState.STOPPING
        stateFlow.value = DeviceSessionState.STOPPED
    }
}

class FakeDatSessionFactory : DatSessionFactory {
    var createCalls = 0
    /** Permanent failure (every call). */
    var failure: DeviceSessionError? = null
    /** One-shot failures consumed in order before [failure] is consulted. */
    val scriptedFailures = ArrayDeque<DeviceSessionError>()
    /** Sessions created from now on stop asynchronously (see FakeGlassesSession.stopAsync). */
    var stopAsync = false
    /** Capture result for cameras of sessions created from now on. */
    var nextCaptureResult: PhotoCaptureResult? = null
    val sessions = mutableListOf<FakeGlassesSession>()

    override fun createSession(): SessionCreateResult {
        createCalls++
        scriptedFailures.removeFirstOrNull()?.let { return SessionCreateResult.Failure(it) }
        failure?.let { return SessionCreateResult.Failure(it) }
        val session = FakeGlassesSession(stopAsync).also { it.nextCaptureResult = nextCaptureResult }
        sessions += session
        return SessionCreateResult.Success(session)
    }

    val last: FakeGlassesSession
        get() = sessions.last()
}

class FakeDatDeviceObserver : DatDeviceObserver {
    val device = MutableStateFlow<GlassesDeviceInfo?>(null)
    /** When true, activeDeviceInfoFlow() throws synchronously instead of returning a flow. */
    var throwOnFlow = false

    override fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> {
        if (throwOnFlow) error("activeDeviceInfoFlow() boom (throwOnFlow)")
        return device
    }
}

class RecordingDisplayAttacher : DisplayAttacher {
    val attachCalls = mutableListOf<GlassesDeviceInfo?>()
    var detachCalls = 0
    override val displayState = MutableStateFlow(GlassesDisplayState.NOT_ATTACHED)
    override fun maybeAttach(session: GlassesSession, device: GlassesDeviceInfo?) {
        attachCalls += device
    }
    override fun detach() {
        detachCalls++
    }
}

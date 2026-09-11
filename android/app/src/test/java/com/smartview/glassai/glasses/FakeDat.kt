package com.smartview.glassai.glasses

import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.display.types.DisplayError
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

class FakeGlassesCamera : GlassesCamera {
    val stateFlow = MutableStateFlow(DatStreamState.STOPPED)
    val frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 4)
    val errors = MutableSharedFlow<StreamError>(extraBufferCapacity = 4)
    var startCalls = 0
    var stopCalls = 0
    var startError: StreamError? = null
    var captureResult: PhotoCaptureResult = PhotoCaptureResult.Failure(CaptureError.NotStreaming)
    var onCapture: (suspend () -> PhotoCaptureResult)? = null

    override val streamState: StateFlow<DatStreamState> = stateFlow
    override val videoFrames: Flow<VideoFrame> = frames
    override val streamErrors: Flow<StreamError> = errors

    override fun startStream(): StreamError? {
        startCalls++
        if (startError == null) stateFlow.value = DatStreamState.STARTING
        return startError
    }

    override suspend fun capturePhoto(): PhotoCaptureResult = onCapture?.invoke() ?: captureResult

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
    val lifecycleCalls = mutableListOf<String>()
    var addDisplayCalls = 0
    var removeDisplayCalls = 0
    var nextAddDisplayFailure: DeviceSessionError? = null
    var nextRemoveDisplayFailure: DeviceSessionError? = null
    val displays = mutableListOf<FakeGlassesDisplay>()
    val display get() = displays.last()

    override val state: StateFlow<DeviceSessionState> = stateFlow
    override val errors: SharedFlow<DeviceSessionError> = errorFlow

    override fun start() {
        startCalls++
        stateFlow.value = DeviceSessionState.STARTING
    }

    override fun stop() {
        lifecycleCalls += "stop"
        stopCalls++
        stateFlow.value = if (stopAsync) DeviceSessionState.STOPPING else DeviceSessionState.STOPPED
    }

    override fun addDisplay(): DisplayAddResult {
        addDisplayCalls++
        nextAddDisplayFailure?.let {
            nextAddDisplayFailure = null
            return DisplayAddResult.Failure(it)
        }
        return DisplayAddResult.Success(FakeGlassesDisplay().also { displays += it })
    }

    override fun removeDisplay(): DeviceSessionError? {
        lifecycleCalls += "removeDisplay"
        removeDisplayCalls++
        val error = nextRemoveDisplayFailure
        nextRemoveDisplayFailure = null
        if (error == null) displays.lastOrNull()?.stateFlow?.value = DisplayState.CLOSED
        return error
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

    /**
     * When true the returned flow throws on *collection*. GlassesSessionManager's `.catch {}`
     * swallows that, which **completes** the flow: the collector coroutine finishes normally and
     * the manager is left with a dead observer (final review I2a).
     */
    var failOnCollect = false

    override fun activeDeviceInfoFlow(): Flow<GlassesDeviceInfo?> {
        if (throwOnFlow) error("activeDeviceInfoFlow() boom (throwOnFlow)")
        if (failOnCollect) return flow { error("activeDeviceInfoFlow() boom (failOnCollect)") }
        return device
    }
}

class FakeGlassesDisplay : GlassesDisplay {
    val stateFlow = MutableStateFlow(DisplayState.STARTING)
    override val state: StateFlow<DisplayState> = stateFlow
    var sendCalls = 0
    val sentBlocks = mutableListOf<ContentScope.() -> Unit>()
    val scriptedSendFailures = ArrayDeque<DisplayError>()
    var clearCalls = 0
    var stopCalls = 0
    var closeCalls = 0
    override suspend fun sendContent(block: ContentScope.() -> Unit): DisplaySendResult {
        sendCalls++
        sentBlocks += block
        return scriptedSendFailures.removeFirstOrNull()?.let { DisplaySendResult.Failed(it) }
            ?: DisplaySendResult.Sent
    }
    override suspend fun clearDisplay(): DisplaySendResult {
        clearCalls++
        return DisplaySendResult.Sent
    }
    override fun stop() { stopCalls++; stateFlow.value = DisplayState.CLOSED }
    override fun close() { closeCalls++; stateFlow.value = DisplayState.CLOSED }
    fun emitStarted() { stateFlow.value = DisplayState.STARTED }
    fun emitStopped() {
        stateFlow.value = DisplayState.STOPPING
        stateFlow.value = DisplayState.STOPPED
    }
}

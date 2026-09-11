package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesErrorMessages
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.services.RTMPStreamingService
import com.smartview.glassai.utils.APIKeyManager
import com.smartview.glassai.utils.RtmpUrlSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTMPStreamingViewModel - Manages RTMP streaming from glasses camera
 *
 * Borrows the camera from the shared GlassesSessionManager (DAT 0.9.0) and feeds raw I420 frames
 * to RTMPStreamingService for live broadcasting. 2.0: the server URL and the stream key are two
 * persisted fields (the key is never rendered), the bitrate is persisted, and a first-frame
 * timeout stops "Connecting" from lasting forever when the glasses never deliver video.
 */
class RTMPStreamingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RTMPStreamingVM"
        private const val OWNER = "RTMPStreamingViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        const val DEFAULT_RTMP_URL = "rtmp://localhost/live"
    }

    // States
    sealed class UIState {
        object Idle : UIState()
        object Connecting : UIState()
        object Streaming : UIState()
        data class Error(val message: String) : UIState()
    }

    private val _uiState = MutableStateFlow<UIState>(UIState.Idle)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    /** Server URL only (`rtmp://host/app`); the key is appended when connecting. */
    private val _rtmpUrl = MutableStateFlow(DEFAULT_RTMP_URL)
    val rtmpUrl: StateFlow<String> = _rtmpUrl.asStateFlow()

    private val _streamKey = MutableStateFlow("")
    val streamKey: StateFlow<String> = _streamKey.asStateFlow()

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame.asStateFlow()

    private val _streamStats = MutableStateFlow(RTMPStreamingService.StreamingStats())
    val streamStats: StateFlow<RTMPStreamingService.StreamingStats> = _streamStats.asStateFlow()

    private val _cameraState = MutableStateFlow<DatStreamState?>(null)
    val cameraState: StateFlow<DatStreamState?> = _cameraState.asStateFlow()

    private val _bitrate = MutableStateFlow(APIKeyManager.DEFAULT_RTMP_BITRATE)
    val bitrate: StateFlow<Int> = _bitrate.asStateFlow()

    // Services
    private val rtmpService = RTMPStreamingService(application)
    private val apiKeyManager = APIKeyManager.getInstance(application)
    private val sessionManager: GlassesSessionManager by lazy {
        GlassesSessionManager.getInstance(application)
    }

    // Borrowed camera + jobs
    private var camera: GlassesCamera? = null

    // Single-threaded worker for frame handling (never the main thread), like the 0.9.0 sample
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

    // RTMP drop policy (spec §5.8): a frame arriving while the previous one is still being copied,
    // encoded and previewed is skipped. The encoder still sees every accepted frame in order.
    private val isProcessingFrame = AtomicBoolean(false)

    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var statsJob: Job? = null
    private var firstFrameJob: Job? = null

    // Video parameters (set when stream starts).
    // @Volatile: written by the frame worker on frameDispatcher, read and reset on Main.
    @Volatile
    private var videoWidth = 0
    @Volatile
    private var videoHeight = 0
    @Volatile
    private var frameTimestampBase = 0L

    /**
     * "A frame arrived in THIS attempt" (Task 9 D-6). videoWidth used to double as this flag, but
     * it survived a failed attempt, so on the next Start handleVideoFrame() never re-entered the
     * first-frame branch: connectRtmp() was never called and armFirstFrameTimeout() was a no-op,
     * leaving the UI in "Connecting" forever. Reset in startStreaming() and teardownCamera().
     */
    @Volatile
    private var firstFrameSeen = false

    init {
        apiKeyManager.getRtmpUrl()?.takeIf { it.isNotBlank() }?.let { _rtmpUrl.value = it }
        apiKeyManager.getRtmpStreamKey()?.let { _streamKey.value = it }
        _bitrate.value = apiKeyManager.getRtmpBitrate()

        // Observe RTMP service state
        viewModelScope.launch {
            rtmpService.state.collect { state ->
                when (state) {
                    is RTMPStreamingService.StreamingState.Idle -> {
                        // Never downgrade a visible error: onConnectionFailedRtmp() sets Error and
                        // then calls stopStreaming(), so Idle follows an Error within milliseconds
                        // on the RTMP thread. startStreaming() moves the state on to Connecting and
                        // the Stop button calls clearError() first, so Error is still recoverable.
                        // A user Stop therefore ends here: service Idle -> UI Idle, no card.
                        if (_uiState.value != UIState.Idle && _uiState.value !is UIState.Error) {
                            _uiState.value = UIState.Idle
                        }
                    }
                    is RTMPStreamingService.StreamingState.Connecting -> {
                        _uiState.value = UIState.Connecting
                    }
                    is RTMPStreamingService.StreamingState.Streaming -> {
                        firstFrameJob?.cancel()
                        _uiState.value = UIState.Streaming
                    }
                    is RTMPStreamingService.StreamingState.Error -> {
                        _uiState.value = UIState.Error(state.message)
                        // An RTMP failure ends the attempt (Task 9 D-6/D-7). The borrowed camera
                        // must not keep streaming behind the error card: it burns ~50 % CPU, holds
                        // the session claim and answers every other feature with CameraBusy.
                        // teardownCamera() never writes _uiState, so the error card stays up.
                        teardownCamera()
                    }
                    is RTMPStreamingService.StreamingState.Disconnected -> {
                        // Unreachable with rtmp 2.2.6: the library never calls onDisconnectRtmp()
                        // for a live drop, so neither this branch nor R.string.rtmp_disconnected
                        // fires today. Both are kept deliberately — a library upgrade that starts
                        // reporting drops makes them live without another change (final review
                        // Minor 5).
                        // The service only emits this while isStreaming (an unexpected drop of a
                        // live stream); the VM double-checks so a late callback after a user Stop
                        // (already Idle) or after a failure (already Error) can never overwrite them.
                        if (_uiState.value == UIState.Streaming) {
                            _uiState.value = UIState.Error(getApplication<Application>().getString(R.string.rtmp_disconnected))
                        }
                    }
                }
            }
        }

        // Observe stats
        statsJob = viewModelScope.launch {
            rtmpService.stats.collect { stats ->
                _streamStats.value = stats
            }
        }
    }

    /** Saves the server URL (without the key). */
    fun updateRtmpUrl(url: String) {
        val trimmed = url.trim()
        _rtmpUrl.value = trimmed
        apiKeyManager.saveRtmpUrl(trimmed)
    }

    /** Saves the stream key into encrypted storage; blank deletes it. */
    fun updateStreamKey(key: String) {
        val trimmed = key.trim()
        _streamKey.value = trimmed
        apiKeyManager.saveRtmpStreamKey(trimmed)
    }

    fun updateBitrate(newBitrate: Int) {
        _bitrate.value = newBitrate
        apiKeyManager.saveRtmpBitrate(newBitrate)
    }

    /** The URL actually pushed to: server + "/" + key. Never log or render this. */
    private fun fullRtmpUrl(): String = RtmpUrlSplitter.join(_rtmpUrl.value, _streamKey.value)

    /**
     * Start RTMP streaming
     * 1. Borrows the glasses camera from the shared session
     * 2. Connects to the RTMP server after the first frame (dimensions known)
     * 3. Encodes and streams
     */
    fun startStreaming() {
        if (_uiState.value == UIState.Streaming || _uiState.value == UIState.Connecting) {
            Log.w(TAG, "Already streaming or connecting")
            return
        }

        Log.d(TAG, "Starting streaming to: ${_rtmpUrl.value}")
        // Per-attempt state, always reset here (Task 9 D-6): a previous attempt that saw frames
        // would otherwise leave videoWidth != 0 and skip the connectRtmp() / first-frame-timeout
        // branch for the rest of the screen's life.
        videoWidth = 0
        videoHeight = 0
        frameTimestampBase = 0L
        firstFrameSeen = false
        // A previous UIState.Error is cleared here (it is now kept until the user acts on it).
        _uiState.value = UIState.Connecting

        // Start DAT SDK camera stream first
        startCameraStream()
    }

    private fun startCameraStream() {
        cancelCameraJobs()
        camera = null
        // Defensive only: every path that ends an attempt (stopStreaming / failCamera / the
        // service Error branch) now runs teardownCamera(), so we must not hold the camera here.
        // A same-session hand-off is what produced the Stop ANR in Task 9 D-7.
        if (sessionManager.currentCameraOwner == OWNER) {
            Log.w(TAG, "start: the camera was still borrowed from a previous attempt")
        }
        sessionManager.stopCamera(OWNER)

        val videoQuality = WearablesViewModel.videoQualityFromSetting(apiKeyManager.getVideoQuality())
        Log.d(TAG, "Starting camera stream with quality: $videoQuality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER, forCamera = true)
            when (sessionManager.ensureSessionStarted(SESSION_START_TIMEOUT_MS)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> {
                    failCamera(getApplication<Application>().getString(R.string.glasses_session_failed))
                    return@launch
                }
                SessionStartResult.NOT_STARTED -> {
                    failCamera(getApplication<Application>().getString(R.string.glasses_session_timeout))
                    return@launch
                }
            }
            val config = StreamConfiguration(videoQuality = videoQuality, frameRate = 24)
            when (val result = sessionManager.addCamera(OWNER, config)) {
                is CameraResult.Ready -> attachCamera(result.camera)
                is CameraResult.Failed -> failCamera(cameraErrorMessage(result.error))
            }
        }
    }

    private fun attachCamera(borrowed: GlassesCamera) {
        camera = borrowed

        // Subscribe BEFORE start()
        stateJob = viewModelScope.launch {
            var hasBeenActive = false
            borrowed.streamState.collect { state ->
                Log.d(TAG, "Camera state: $state")
                _cameraState.value = state

                when (state) {
                    DatStreamState.STREAMING -> {
                        hasBeenActive = true
                        // Camera is ready, RTMP will connect after first frame arrives
                        Log.d(TAG, "Camera streaming, waiting for first frame...")
                        armFirstFrameTimeout()
                    }
                    DatStreamState.STARTING,
                    DatStreamState.STARTED,
                    DatStreamState.STOPPING,
                    DatStreamState.PAUSED -> {
                        hasBeenActive = true
                    }
                    DatStreamState.STOPPED,
                    DatStreamState.CLOSED -> {
                        if (hasBeenActive) {
                            hasBeenActive = false
                            Log.d(TAG, "Camera stream ended; stopping RTMP")
                            stopStreaming()
                        }
                    }
                }
            }
        }

        streamErrorJob = viewModelScope.launch {
            borrowed.streamErrors.collect { error ->
                Log.e(TAG, "Stream error: ${error.description}")
                _uiState.value = UIState.Error(GlassesErrorMessages.of(getApplication(), error))
            }
        }

        // No conflate(): the SDK buffer is only valid inside collect {}, and handleVideoFrame copies
        // it first (Task 5). Frames are handled on the single-threaded worker.
        videoJob = viewModelScope.launch(frameDispatcher) {
            frameTimestampBase = 0L
            borrowed.videoFrames.collect { videoFrame ->
                if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect
                if (!isProcessingFrame.compareAndSet(false, true)) return@collect
                try {
                    handleVideoFrame(videoFrame)
                } finally {
                    isProcessingFrame.set(false)
                }
            }
        }

        val startError = borrowed.startStream()
        if (startError != null) {
            failCamera(GlassesErrorMessages.of(getApplication(), startError))
        }
    }

    /**
     * The glasses report STREAMING but may never deliver a decodable frame (ledger T5: RTMP could
     * stick in Connecting with no stream budget). Fail after FIRST_FRAME_TIMEOUT_MS unless a frame
     * arrived in this attempt ([firstFrameSeen]) or the RTMP service already moved on.
     */
    private fun armFirstFrameTimeout() {
        firstFrameJob?.cancel()
        firstFrameJob = viewModelScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (!firstFrameSeen && _uiState.value == UIState.Connecting) {
                Log.e(TAG, "no video frame within ${FIRST_FRAME_TIMEOUT_MS}ms")
                rtmpService.stopStreaming()
                failCamera(getApplication<Application>().getString(R.string.rtmp_first_frame_timeout))
            }
        }
    }

    private fun failCamera(message: String) {
        Log.e(TAG, "Camera failure: $message")
        teardownCamera()
        _uiState.value = UIState.Error(message)
    }

    private fun cameraErrorMessage(error: CameraError): String =
        GlassesErrorMessages.of(getApplication(), error)

    private fun connectRtmp() {
        viewModelScope.launch {
            val success = rtmpService.startStreaming(
                rtmpUrl = fullRtmpUrl(),
                width = videoWidth,
                height = videoHeight,
                bitrate = _bitrate.value
            )

            if (!success) {
                Log.e(TAG, "Failed to connect RTMP")
                _uiState.value = UIState.Error(getApplication<Application>().getString(R.string.rtmp_connect_failed))
            }
        }
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        // Copy the SDK buffer FIRST: VideoFrame.buffer is only guaranteed valid inside collect {}
        // (spec §5.8). Everything below works on our own copy.
        val i420 = FrameConversions.copyI420(videoFrame) ?: return
        val width = videoFrame.width
        val height = videoFrame.height

        // Set video dimensions on the first frame OF THIS ATTEMPT and connect RTMP
        if (!firstFrameSeen) {
            firstFrameSeen = true
            // Use original dimensions - modern MediaCodec handles alignment internally
            videoWidth = width
            videoHeight = height
            firstFrameJob?.cancel()
            Log.d(TAG, "Video dimensions: ${videoWidth}x${videoHeight}")

            // Now connect RTMP with proper dimensions
            if (_uiState.value == UIState.Connecting && !rtmpService.isStreaming()) {
                connectRtmp()
            }
        }

        // Calculate timestamp
        val timestampUs = if (frameTimestampBase == 0L) {
            frameTimestampBase = System.nanoTime() / 1000
            0L
        } else {
            System.nanoTime() / 1000 - frameTimestampBase
        }

        // Feed the copied I420 frame to the RTMP encoder (ByteBuffer overload: timestamp smoothing)
        rtmpService.feedFrame(
            buffer = ByteBuffer.wrap(i420),
            width = width,
            height = height,
            timestampUs = timestampUs
        )

        // Also update preview (convert to bitmap for display) from the same copy, and publish it as
        // the manager's latestFrame: RTMP is a long-lived camera owner, and an OpenClaw camera.snap
        // during a broadcast must return the live frame instead of NO_FRAME (publishFrame is the
        // documented off-main exception in GlassesSessionManager).
        val bitmap = FrameConversions.i420ToBitmap(i420, width, height, FrameConversions.PREVIEW_JPEG_QUALITY)
        if (bitmap != null) {
            _previewFrame.value = bitmap
            sessionManager.publishFrame(OWNER, bitmap)
        }
    }

    private fun cancelCameraJobs() {
        startJob?.cancel()
        startJob = null
        videoJob?.cancel()
        videoJob = null
        stateJob?.cancel()
        stateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        firstFrameJob?.cancel()
        firstFrameJob = null
    }

    /**
     * Ends the current camera attempt: cancels the jobs, gives the camera and the session claim
     * back and resets the per-attempt video state. Never writes [_uiState] — the caller owns that,
     * so the RTMP error card survives the teardown (final review C2).
     */
    private fun teardownCamera() {
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)
        videoWidth = 0
        videoHeight = 0
        frameTimestampBase = 0L
        firstFrameSeen = false
        _previewFrame.value = null
        _cameraState.value = null
    }

    /**
     * Stop streaming
     */
    fun stopStreaming() {
        Log.d(TAG, "Stopping streaming")

        // Stop RTMP service, then give the camera and the session claim back
        rtmpService.stopStreaming()
        teardownCamera()

        // A stream error must stay visible: the STOPPED transition that normally follows a stream
        // error (attachCamera's stateJob, hasBeenActive branch) must not blink the error away by
        // falling back to Idle here. An explicit user-initiated stop clears the error first (see
        // RTMPStreamingScreen's Stop button), so that path still reaches Idle.
        if (_uiState.value !is UIState.Error) {
            _uiState.value = UIState.Idle
        }
    }

    fun clearError() {
        if (_uiState.value is UIState.Error) {
            _uiState.value = UIState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        rtmpService.release()
        statsJob?.cancel()
    }
}

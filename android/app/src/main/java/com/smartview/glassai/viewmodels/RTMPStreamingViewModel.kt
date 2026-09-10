package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesErrorMessages
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.services.RTMPStreamingService
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTMPStreamingViewModel - Manages RTMP streaming from glasses camera
 *
 * Borrows the camera from the shared GlassesSessionManager (DAT 0.9.0) and feeds raw I420 frames
 * to RTMPStreamingService for live broadcasting.
 */
class RTMPStreamingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RTMPStreamingVM"
        private const val OWNER = "RTMPStreamingViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        const val DEFAULT_RTMP_URL = "rtmp://localhost/live/stream"
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

    private val _rtmpUrl = MutableStateFlow(DEFAULT_RTMP_URL)
    val rtmpUrl: StateFlow<String> = _rtmpUrl.asStateFlow()

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame.asStateFlow()

    private val _streamStats = MutableStateFlow(RTMPStreamingService.StreamingStats())
    val streamStats: StateFlow<RTMPStreamingService.StreamingStats> = _streamStats.asStateFlow()

    private val _cameraState = MutableStateFlow<DatStreamState?>(null)
    val cameraState: StateFlow<DatStreamState?> = _cameraState.asStateFlow()

    private val _bitrate = MutableStateFlow(2_000_000) // 2 Mbps default
    val bitrate: StateFlow<Int> = _bitrate.asStateFlow()

    // Services
    private val rtmpService = RTMPStreamingService(application)
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

    // Video parameters (set when stream starts)
    private var videoWidth = 0
    private var videoHeight = 0
    private var frameTimestampBase = 0L

    init {
        // Load saved RTMP URL
        val apiKeyManager = APIKeyManager.getInstance(application)
        apiKeyManager.getRtmpUrl()?.let { savedUrl ->
            if (savedUrl.isNotEmpty()) {
                _rtmpUrl.value = savedUrl
            }
        }

        // Observe RTMP service state
        viewModelScope.launch {
            rtmpService.state.collect { state ->
                when (state) {
                    is RTMPStreamingService.StreamingState.Idle -> {
                        if (_uiState.value != UIState.Idle) {
                            _uiState.value = UIState.Idle
                        }
                    }
                    is RTMPStreamingService.StreamingState.Connecting -> {
                        _uiState.value = UIState.Connecting
                    }
                    is RTMPStreamingService.StreamingState.Streaming -> {
                        _uiState.value = UIState.Streaming
                    }
                    is RTMPStreamingService.StreamingState.Error -> {
                        _uiState.value = UIState.Error(state.message)
                    }
                    is RTMPStreamingService.StreamingState.Disconnected -> {
                        _uiState.value = UIState.Error("Disconnected from server")
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

    fun updateRtmpUrl(url: String) {
        _rtmpUrl.value = url
        // Save URL
        val apiKeyManager = APIKeyManager.getInstance(getApplication())
        apiKeyManager.saveRtmpUrl(url)
    }

    fun updateBitrate(newBitrate: Int) {
        _bitrate.value = newBitrate
    }

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
        _uiState.value = UIState.Connecting

        // Start DAT SDK camera stream first
        startCameraStream()
    }

    private fun startCameraStream() {
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)

        // Get video quality setting
        val apiKeyManager = APIKeyManager.getInstance(getApplication())
        val savedQuality = apiKeyManager.getVideoQuality()
        val videoQuality = when (savedQuality) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            else -> VideoQuality.MEDIUM
        }

        Log.d(TAG, "Starting camera stream with quality: $savedQuality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER)
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

    private fun failCamera(message: String) {
        Log.e(TAG, "Camera failure: $message")
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)
        _cameraState.value = null
        _uiState.value = UIState.Error(message)
    }

    private fun cameraErrorMessage(error: CameraError): String =
        GlassesErrorMessages.of(getApplication(), error)

    private fun connectRtmp() {
        viewModelScope.launch {
            val success = rtmpService.startStreaming(
                rtmpUrl = _rtmpUrl.value,
                width = videoWidth,
                height = videoHeight,
                bitrate = _bitrate.value
            )

            if (!success) {
                Log.e(TAG, "Failed to connect RTMP")
                _uiState.value = UIState.Error("Failed to connect to RTMP server")
            }
        }
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        // Copy the SDK buffer FIRST: VideoFrame.buffer is only guaranteed valid inside collect {}
        // (spec §5.8). Everything below works on our own copy.
        val i420 = copyFrame(videoFrame) ?: return
        val width = videoFrame.width
        val height = videoFrame.height

        // Set video dimensions on first frame and connect RTMP
        if (videoWidth == 0 || videoHeight == 0) {
            // Use original dimensions - modern MediaCodec handles alignment internally
            videoWidth = width
            videoHeight = height
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

        // Also update preview (convert to bitmap for display) from the same copy
        updatePreview(i420, width, height)
    }

    /** Defensive copy of the SDK frame; null if the buffer could not be read. */
    private fun copyFrame(videoFrame: VideoFrame): ByteArray? = try {
        val buffer = videoFrame.buffer
        val originalPosition = buffer.position()
        val copy = ByteArray(buffer.remaining())
        buffer.get(copy)
        buffer.position(originalPosition)
        copy
    } catch (e: Exception) {
        Log.e(TAG, "Error copying video frame: ${e.message}")
        null
    }

    private fun updatePreview(i420: ByteArray, width: Int, height: Int) {
        try {
            // Convert I420 to NV21 for preview
            val nv21 = convertI420toNV21(i420, width, height)
            val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)

            val jpegBytes = ByteArrayOutputStream().use { stream ->
                image.compressToJpeg(Rect(0, 0, width, height), 50, stream)
                stream.toByteArray()
            }

            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            _previewFrame.value = bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error updating preview: ${e.message}")
        }
    }

    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4

        input.copyInto(output, 0, 0, size) // Y is the same

        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n] // V first
            output[size + n * 2 + 1] = input[size + n] // U second
        }
        return output
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
    }

    /**
     * Stop streaming
     */
    fun stopStreaming() {
        Log.d(TAG, "Stopping streaming")

        // Stop RTMP service
        rtmpService.stopStreaming()

        // Give the camera and the session claim back
        cancelCameraJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)

        // Reset
        videoWidth = 0
        videoHeight = 0
        frameTimestampBase = 0L
        _previewFrame.value = null
        _cameraState.value = null
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

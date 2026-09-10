package com.smartview.glassai.viewmodels

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureResult
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * WearablesViewModel - UI-facing DAT SDK façade (DAT 0.9.0).
 *
 * - Registration / unregistration (needs a real Activity: the 0.4.0 code passed the Application
 *   and crashed with ClassCastException).
 * - Device discovery + active-device metadata via GlassesSessionManager.
 * - Camera streaming borrowed from the shared GlassesSessionManager (one DeviceSession per device).
 *
 * The public contract (StreamState sealed class, currentFrame, startStream/stopStream/takePhoto,
 * capturedPhoto, hasActiveDevice, connectionState, isRegistered) is unchanged for the screens.
 */
class WearablesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "WearablesViewModel"
        private const val OWNER = "WearablesViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        private const val FRAME_RATE = 24
    }

    // Connection states
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Searching : ConnectionState()
        object Connecting : ConnectionState()
        data class Registered(val deviceName: String) : ConnectionState() // Device registered but may not be actively connected
        data class Connected(val deviceName: String) : ConnectionState() // Device is actively connected and ready
        data class Error(val message: String) : ConnectionState()
    }

    // Streaming status (app-level; the SDK's StreamState is imported as DatStreamState)
    sealed class StreamState {
        object Stopped : StreamState()
        object Waiting : StreamState()  // starting, stopping
        object Streaming : StreamState()
        object Paused : StreamState()   // paused by a cap-touch tap on the glasses
        data class Error(val message: String) : StreamState()
    }

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _registrationState = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Stopped)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    private val _capturedPhoto = MutableStateFlow<Bitmap?>(null)
    val capturedPhoto: StateFlow<Bitmap?> = _capturedPhoto.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceIdentifier>>(emptyList())
    val devices: StateFlow<List<DeviceIdentifier>> = _devices.asStateFlow()

    private val _hasActiveDevice = MutableStateFlow(false)
    val hasActiveDevice: StateFlow<Boolean> = _hasActiveDevice.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // Shared session owner (spec §4)
    private val sessionManager: GlassesSessionManager by lazy {
        GlassesSessionManager.getInstance(getApplication())
    }

    /** Active device metadata: name, type, display capability, compatibility (spec §5.7). */
    val activeDevice: StateFlow<GlassesDeviceInfo?>
        get() = sessionManager.activeDevice

    /** Device.compatibility == DEVICE_UPDATE_REQUIRED -> show "Update firmware". */
    val isFirmwareUpdateRequired: StateFlow<Boolean>
        get() = sessionManager.isFirmwareUpdateRequired

    /** DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED seen -> show "Update glasses app". */
    val isDatAppUpdateRequired: StateFlow<Boolean>
        get() = sessionManager.isDatAppUpdateRequired

    // Borrowed camera (null when not streaming)
    private var camera: GlassesCamera? = null

    // Coroutine jobs for stream management
    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var deviceSelectorJob: Job? = null
    private var monitoringStarted = false

    // Callbacks for external use
    var onFrameReceived: ((Bitmap) -> Unit)? = null
    var onPhotoTaken: ((Bitmap) -> Unit)? = null

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        Log.d(TAG, "Starting monitoring")

        // 1. Registration errors FIRST: registrationErrorStream is hot with no replay, so it must be
        //    collected before the user can tap Connect.
        viewModelScope.launch {
            Wearables.registrationErrorStream.collect { error ->
                Log.e(TAG, "Registration error: ${error.description}")
                setError(error.getLocalizedDescription(getApplication()))
                if (_connectionState.value is ConnectionState.Searching ||
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // 2. Registration state (plain enum in 0.9.0)
        viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "Registration state changed: $state")
                _registrationState.value = state
                when (state) {
                    RegistrationState.REGISTERED -> Log.d(TAG, "Device registered")
                    RegistrationState.UNAVAILABLE -> {
                        Log.d(TAG, "Registration unavailable")
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    RegistrationState.AVAILABLE -> {
                        Log.d(TAG, "Registration available")
                        if (_connectionState.value is ConnectionState.Connecting) {
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }
                    RegistrationState.REGISTERING -> {
                        Log.d(TAG, "Registering...")
                        _connectionState.value = ConnectionState.Connecting
                    }
                    RegistrationState.UNREGISTERING -> Log.d(TAG, "Unregistering...")
                }
            }
        }

        // 3. Available devices
        viewModelScope.launch {
            Wearables.devices.collect { deviceSet ->
                Log.d(TAG, "Devices changed: ${deviceSet.size} devices")
                _devices.value = deviceSet.toList()
            }
        }

        // 4. Active device (AutoDeviceSelector + devicesMetadata, via the session manager)
        deviceSelectorJob = viewModelScope.launch {
            sessionManager.activeDevice.collect { info ->
                Log.d(TAG, "Active device: ${info?.name ?: "none"} (${info?.deviceType})")
                _hasActiveDevice.value = info != null

                if (info != null) {
                    if (_connectionState.value !is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Registered(info.name)
                    }
                } else if (_connectionState.value is ConnectionState.Connected ||
                    _connectionState.value is ConnectionState.Registered
                ) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // 5. Session errors (createSession failures + DeviceSession.errors)
        viewModelScope.launch {
            sessionManager.sessionError.collect { error ->
                setError(error.getLocalizedDescription(getApplication()))
            }
        }
    }

    fun startDeviceSearch(activity: Activity) {
        Log.d(TAG, "Starting device search")
        _connectionState.value = ConnectionState.Searching
        startRegistration(activity)
    }

    fun stopDeviceSearch() {
        if (_connectionState.value is ConnectionState.Searching) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun startRegistration(activity: Activity) {
        Log.d(TAG, "Starting registration")
        Wearables.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        Log.d(TAG, "Starting unregistration")
        Wearables.startUnregistration(activity)
    }

    fun disconnect(activity: Activity) {
        viewModelScope.launch {
            stopStream()
            sessionManager.stopSession()
            startUnregistration(activity)
            _connectionState.value = ConnectionState.Disconnected
            _batteryLevel.value = null
        }
    }

    /** Opens the Meta AI app's firmware update flow (Device.compatibility == DEVICE_UPDATE_REQUIRED). */
    fun openFirmwareUpdate(activity: Activity) {
        Wearables.openFirmwareUpdate(activity).onFailure { error, _ ->
            // NavigationError is not part of GlassesErrorMessages (spec §5.9 covers Stream/Session errors);
            // the SDK's own localized text is used so the message still follows the device language.
            setError(error.getLocalizedDescription(getApplication()))
        }
    }

    /** Opens the Meta AI app's DAT-glasses-app update flow (DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED). */
    fun openDATGlassesAppUpdate(activity: Activity) {
        Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
            setError(error.getLocalizedDescription(getApplication()))
        }
    }

    // Navigate to streaming (check permission first)
    fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
        viewModelScope.launch {
            val permission = Permission.CAMERA
            val result = Wearables.checkPermissionStatus(permission)

            result.onFailure { error, _ ->
                setError("Permission check error: ${error.description}")
                return@launch
            }

            val permissionStatus = result.getOrNull()
            if (permissionStatus == PermissionStatus.Granted) {
                _isStreaming.value = true
                return@launch
            }

            // Request permission
            when (onRequestWearablesPermission(permission)) {
                PermissionStatus.Denied -> setError("Permission denied")
                PermissionStatus.Granted -> _isStreaming.value = true
            }
        }
    }

    fun navigateToDeviceSelection() {
        _isStreaming.value = false
    }

    // Streaming
    suspend fun checkCameraPermission(): Boolean {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        return result.getOrNull() == PermissionStatus.Granted
    }

    /**
     * Start streaming from the wearable device camera through the shared session:
     * acquire -> ensureSessionStarted (waits for a previous session's STOPPED, creates, waits STARTED)
     * -> addCamera -> subscribe -> stream.start().
     */
    fun startStream() {
        Log.d(TAG, "startStream START")

        cancelStreamJobs()
        camera = null
        sessionManager.stopCamera(OWNER)

        // Reset state
        _currentFrame.value = null
        _streamState.value = StreamState.Waiting

        // Get saved video quality setting
        val savedQuality = APIKeyManager.getInstance(getApplication()).getVideoQuality()
        val videoQuality = when (savedQuality) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            else -> VideoQuality.MEDIUM
        }
        Log.d(TAG, "Using video quality: $savedQuality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER)
            // Leave-and-re-enter: the previous session may still be STOPPING in the SDK, so the
            // create happens inside ensureSessionStarted() once STOPPED has been observed.
            when (sessionManager.ensureSessionStarted(SESSION_START_TIMEOUT_MS)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> {
                    Log.e(TAG, "createSession failed")
                    val message = getApplication<Application>().getString(R.string.glasses_session_failed)
                    setError(message)
                    _streamState.value = StreamState.Error(message)
                    sessionManager.release(OWNER)
                    return@launch
                }
                SessionStartResult.NOT_STARTED -> {
                    Log.e(TAG, "session did not reach STARTED")
                    val message = getApplication<Application>().getString(R.string.glasses_session_timeout)
                    setError(message)
                    _streamState.value = StreamState.Error(message)
                    sessionManager.release(OWNER)
                    return@launch
                }
            }
            val config = StreamConfiguration(videoQuality = videoQuality, frameRate = FRAME_RATE)
            when (val result = sessionManager.addCamera(OWNER, config)) {
                is CameraResult.Ready -> attachCamera(result.camera)
                is CameraResult.Failed -> {
                    Log.e(TAG, "addCamera failed: ${result.error}")
                    val message = cameraErrorMessage(result.error)
                    setError(message)
                    _streamState.value = StreamState.Error(message)
                    sessionManager.release(OWNER)
                }
            }
        }

        Log.d(TAG, "startStream END")
    }

    private fun attachCamera(borrowed: GlassesCamera) {
        camera = borrowed

        // Subscribe BEFORE start(): streamState is a StateFlow that replays STOPPED.
        videoJob = viewModelScope.launch {
            Log.d(TAG, "Starting video frame collection")
            borrowed.videoFrames.collect { videoFrame ->
                if (videoFrame.isCompressed || videoFrame.isCodecConfig) return@collect
                handleVideoFrame(videoFrame)
            }
        }

        streamStateJob = viewModelScope.launch {
            var hasBeenActive = false
            borrowed.streamState.collect { currentState ->
                Log.d(TAG, "Stream state: $currentState")
                when (currentState) {
                    DatStreamState.STREAMING -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Streaming
                        // Upgrade connection state to Connected when streaming confirmed
                        val currentConnection = _connectionState.value
                        if (currentConnection is ConnectionState.Registered) {
                            _connectionState.value = ConnectionState.Connected(currentConnection.deviceName)
                            Log.d(TAG, "Upgraded to Connected (streaming confirmed)")
                        }
                    }
                    DatStreamState.STARTING,
                    DatStreamState.STARTED,
                    DatStreamState.STOPPING,
                    DatStreamState.PAUSED -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Waiting
                    }
                    DatStreamState.STOPPED,
                    DatStreamState.CLOSED -> {
                        if (hasBeenActive) {
                            hasBeenActive = false
                            Log.d(TAG, "Stream terminated, calling stopStream()")
                            stopStream()
                        }
                    }
                }
            }
        }

        streamErrorJob = viewModelScope.launch {
            borrowed.streamErrors.collect { error ->
                Log.e(TAG, "Stream error: ${error.description}")
                setError(error.getLocalizedDescription(getApplication()))
            }
        }

        val startError = borrowed.startStream()
        if (startError != null) {
            Log.e(TAG, "stream.start failed: ${startError.description}")
            val message = startError.getLocalizedDescription(getApplication())
            setError(message)
            stopStream()
            _streamState.value = StreamState.Error(message)
        }
    }

    /**
     * Stop streaming and give the camera + session claim back to the manager.
     */
    fun stopStream() {
        Log.d(TAG, "stopStream START")

        cancelStreamJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)

        // Clear frame (let GC handle bitmap)
        _currentFrame.value = null
        _streamState.value = StreamState.Stopped

        // Downgrade connection state
        val currentConnection = _connectionState.value
        if (currentConnection is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Registered(currentConnection.deviceName)
            Log.d(TAG, "Downgraded to Registered (stream stopped)")
        }

        Log.d(TAG, "stopStream END")
    }

    private fun cancelStreamJobs() {
        startJob?.cancel()
        startJob = null
        videoJob?.cancel()
        videoJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
    }

    private fun cameraErrorMessage(error: CameraError): String {
        val app = getApplication<Application>()
        return when (error) {
            is CameraError.CameraBusy -> app.getString(R.string.glasses_camera_busy)
            CameraError.NoSession -> app.getString(R.string.glasses_no_session)
            CameraError.SessionNotStarted -> app.getString(R.string.glasses_session_timeout)
            is CameraError.Sdk -> error.error.getLocalizedDescription(app)
        }
    }

    /**
     * Capture a photo from the stream (DatResult<PhotoData, CaptureError> in 0.9.0).
     * Returns the previously captured photo synchronously; the new one lands in [capturedPhoto].
     */
    fun takePhoto(): Bitmap? {
        val activeCamera = camera
        if (activeCamera == null || _streamState.value != StreamState.Streaming) {
            Log.w(TAG, "Cannot take photo: not streaming")
            return null
        }

        viewModelScope.launch {
            Log.d(TAG, "Capturing photo...")
            when (val result = activeCamera.capturePhoto()) {
                is PhotoCaptureResult.Success -> {
                    val bitmap = withContext(Dispatchers.Default) { decodePhoto(result.photo) }
                    if (bitmap == null) {
                        Log.e(TAG, "Photo decode failed")
                        _errorMessage.value = getApplication<Application>().getString(R.string.photo_capture_failed)
                    } else {
                        Log.d(TAG, "Photo captured: ${bitmap.width}x${bitmap.height}")
                        _capturedPhoto.value = bitmap
                        onPhotoTaken?.invoke(bitmap)
                    }
                }
                is PhotoCaptureResult.Failure -> {
                    Log.e(TAG, "Photo capture failed: ${result.error.description}")
                    _errorMessage.value = result.error.getLocalizedDescription(getApplication())
                }
            }
        }
        return _capturedPhoto.value
    }

    private fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate().apply { rewind() }
            val byteArray = ByteArray(buffer.remaining())
            buffer.get(byteArray)
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        }
    }

    /**
     * Handle incoming (uncompressed YUV, treated as I420) video frames.
     */
    private fun handleVideoFrame(videoFrame: VideoFrame) {
        try {
            val buffer = videoFrame.buffer
            val dataSize = buffer.remaining()
            val byteArray = ByteArray(dataSize)

            // Save current position
            val originalPosition = buffer.position()
            buffer.get(byteArray)
            // Restore position
            buffer.position(originalPosition)

            // Convert I420 to NV21 format
            val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
            val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)

            val jpegBytes = ByteArrayOutputStream().use { stream ->
                image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
                stream.toByteArray()
            }

            val newBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

            _currentFrame.value = newBitmap
            onFrameReceived?.invoke(newBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling video frame: ${e.message}")
        }
    }

    // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
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

    fun clearCapturedPhoto() {
        _capturedPhoto.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setError(message: String) {
        _errorMessage.value = message
    }

    // Check if registered with Meta AI app (UNREGISTERING still counts as registered, like the sample)
    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.REGISTERED ||
            _registrationState.value == RegistrationState.UNREGISTERING

    override fun onCleared() {
        Log.d(TAG, "onCleared START - cleaning up all resources")
        super.onCleared()

        stopStream()

        deviceSelectorJob?.cancel()
        deviceSelectorJob = null
        monitoringStarted = false

        Log.d(TAG, "onCleared END - cleanup complete")
    }
}

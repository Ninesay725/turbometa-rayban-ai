package com.smartview.glassai.viewmodels

import android.app.Activity
import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.smartview.glassai.R
import com.smartview.glassai.glasses.CameraError
import com.smartview.glassai.glasses.CameraPermissionCheck
import com.smartview.glassai.glasses.CameraResult
import com.smartview.glassai.glasses.DatRegistrationGateway
import com.smartview.glassai.glasses.FrameConversions
import com.smartview.glassai.glasses.GlassesCamera
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesDisplayState
import com.smartview.glassai.glasses.GlassesErrorMessages
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureResult
import com.smartview.glassai.glasses.SessionStartResult
import com.smartview.glassai.glasses.WearablesRegistrationGateway
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WearablesViewModel - UI-facing DAT SDK façade (DAT 0.9.0).
 *
 * - Registration / unregistration (needs a real Activity) through [DatRegistrationGateway].
 * - Device discovery + active-device metadata via GlassesSessionManager.
 * - Camera streaming borrowed from the shared GlassesSessionManager (one DeviceSession per device);
 *   every decoded frame is also published to GlassesSessionManager.latestFrame (OpenClaw snap source).
 *
 * The public contract (StreamState sealed class, currentFrame, startStream/stopStream/takePhoto,
 * capturedPhoto, hasActiveDevice, connectionState, isRegistered, errorMessage) is unchanged for the
 * screens. [errorEvents] is the one-shot channel for the toast; [errorMessage] stays as state.
 *
 * The internal constructor exists so JVM tests can inject fakes (Phase A review Important #4);
 * the Application-only constructor is the one `by viewModels()` uses.
 */
class WearablesViewModel internal constructor(
    application: Application,
    private val sessionManager: GlassesSessionManager,
    private val registration: DatRegistrationGateway,
    private val strings: (Int) -> String,
    private val videoQuality: () -> VideoQuality,
    private val frameDispatcher: CoroutineDispatcher,
    private val bluetoothGranted: () -> Boolean = { true },
    private val decodePhoto: (PhotoData) -> Bitmap? = FrameConversions::decodePhoto,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        sessionManager = GlassesSessionManager.getInstance(application),
        registration = WearablesRegistrationGateway(application),
        strings = { id -> application.getString(id) },
        videoQuality = { videoQualityFromSetting(APIKeyManager.getInstance(application).getVideoQuality()) },
        // Single-threaded worker for frame decoding (never the main thread), like the 0.9.0 sample
        frameDispatcher = Dispatchers.Default.limitedParallelism(1),
        bluetoothGranted = { ContextCompat.checkSelfPermission(application,
            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED },
    )

    companion object {
        private const val TAG = "WearablesViewModel"
        const val OWNER = "WearablesViewModel"
        private const val SESSION_START_TIMEOUT_MS = 12_000L
        private const val FRAME_RATE = 24

        fun videoQualityFromSetting(setting: String): VideoQuality = when (setting) {
            "LOW" -> VideoQuality.LOW
            "HIGH" -> VideoQuality.HIGH
            else -> VideoQuality.MEDIUM
        }
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

    /** Latest error as state (inline consumers). Cleared by [clearError] and at every startStream(). */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** One-shot error events for the single toast above the NavHost (Phase A review Minor #13). */
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** Active device metadata: name, type, display capability, compatibility (spec §5.7). */
    val activeDevice: StateFlow<GlassesDeviceInfo?>
        get() = sessionManager.activeDevice

    /** Device.compatibility == DEVICE_UPDATE_REQUIRED -> show "Update firmware". */
    val isFirmwareUpdateRequired: StateFlow<Boolean>
        get() = sessionManager.isFirmwareUpdateRequired

    /** DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED seen -> show "Update glasses app". */
    val isDatAppUpdateRequired: StateFlow<Boolean>
        get() = sessionManager.isDatAppUpdateRequired

    val displayState: StateFlow<GlassesDisplayState>
        get() = sessionManager.displayState

    val isDisplayCapable: StateFlow<Boolean> = activeDevice
        .map { it?.isDisplayCapable == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isDisplayAvailable: StateFlow<Boolean>
        get() = sessionManager.isDisplayAvailable

    // Borrowed camera (null when not streaming)
    private var streamOwner: Any? = null
    private var streamGeneration = 0L
    private var photoJob: Job? = null
    private var camera: GlassesCamera? = null

    // Drop frames while the previous one is still being converted (spec §5.8)
    private val isProcessingFrame = AtomicBoolean(false)

    // Coroutine jobs for stream management
    private var startJob: Job? = null
    private var videoJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var deviceSelectorJob: Job? = null
    private var monitoringStarted = false

    private fun str(@StringRes id: Int): String = strings(id)

    fun startMonitoring() {
        // Always re-arm the shared device observer first (final review I2b): MainActivity calls
        // this once the Bluetooth runtime permissions are granted, and the manager's observer may
        // have been started before the grant (and died) or never started at all. startMonitoring()
        // is idempotent while the collector is alive and restarts it when it is not.
        sessionManager.startMonitoring()
        if (monitoringStarted) return
        monitoringStarted = true

        Log.d(TAG, "Starting monitoring")

        // 1. Registration errors FIRST: registrationErrorStream is hot with no replay, so it must be
        //    collected before the user can tap Connect.
        viewModelScope.launch {
            registration.registrationErrors.collect { error ->
                Log.e(TAG, "Registration error: ${error.description}")
                setError(str(GlassesErrorMessages.resId(error)))
                if (_connectionState.value is ConnectionState.Searching ||
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // 2. Registration state (plain enum in 0.9.0)
        viewModelScope.launch {
            registration.registrationState.collect { state ->
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
            registration.devices.collect { deviceSet ->
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

        // 5. Session errors (createSession failures + DeviceSession.errors). Deduplicated against
        //    failStart(), which may already have shown the same reason via lastSessionError.
        viewModelScope.launch {
            sessionManager.sessionError.collect { error ->
                val message = str(GlassesErrorMessages.resId(error))
                if (_errorMessage.value != message) setError(message)
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
        registration.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        Log.d(TAG, "Starting unregistration")
        registration.startUnregistration(activity)
    }

    /**
     * Stops the stream, stops the shared session unconditionally (we are about to unregister from
     * Meta AI, so no session could survive anyway) and unregisters.
     */
    fun disconnect(activity: Activity) {
        viewModelScope.launch {
            stopStreamInternal()
            sessionManager.stopSession()
            startUnregistration(activity)
            _connectionState.value = ConnectionState.Disconnected
            _batteryLevel.value = null
        }
    }

    /** Opens the Meta AI app's firmware update flow (Device.compatibility == DEVICE_UPDATE_REQUIRED). */
    fun openFirmwareUpdate(activity: Activity) {
        // NavigationError is not part of GlassesErrorMessages (spec §5.9 covers Stream/Session errors);
        // the SDK's own localized text is used so the message still follows the device language.
        registration.openFirmwareUpdate(activity)?.let { setError(it) }
    }

    /** Opens the Meta AI app's DAT-glasses-app update flow (DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED). */
    fun openDATGlassesAppUpdate(activity: Activity) {
        registration.openDATGlassesAppUpdate(activity)?.let { setError(it) }
    }

    // Navigate to streaming (check permission first)
    fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
        viewModelScope.launch {
            when (val check = registration.checkCameraPermission()) {
                is CameraPermissionCheck.Failed -> {
                    setError(str(R.string.glasses_permission_check_failed).format(check.description))
                    return@launch
                }
                CameraPermissionCheck.Granted -> {
                    _isStreaming.value = true
                    return@launch
                }
                CameraPermissionCheck.Denied -> Unit
            }

            // Request permission
            when (onRequestWearablesPermission(Permission.CAMERA)) {
                PermissionStatus.Denied -> setError(str(R.string.camera_permission_denied))
                PermissionStatus.Granted -> _isStreaming.value = true
            }
        }
    }

    fun navigateToDeviceSelection() {
        _isStreaming.value = false
    }

    // Streaming
    suspend fun checkCameraPermission(): Boolean =
        registration.checkCameraPermission() == CameraPermissionCheck.Granted

    /** Main-thread screen lease. A retired permission dialog cannot acquire a successor's camera. */
    suspend fun startStream(
        owner: Any,
        onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    ): Boolean {
        stopStreamInternal()
        streamOwner = owner
        val generation = streamGeneration
        var started = false
        try {
            if (!bluetoothGranted()) {
                setError(str(R.string.permission_all_required))
                return false
            }
            val permission = registration.checkCameraPermission()
            currentCoroutineContext().ensureActive()
            if (streamOwner !== owner || generation != streamGeneration) return false
            val granted = when (permission) {
                CameraPermissionCheck.Granted -> true
                CameraPermissionCheck.Denied ->
                    onRequestWearablesPermission(Permission.CAMERA) == PermissionStatus.Granted
                is CameraPermissionCheck.Failed -> {
                    if (streamOwner === owner && generation == streamGeneration) {
                        setError(str(R.string.glasses_permission_check_failed).format(permission.description))
                    }
                    return false
                }
            }
            currentCoroutineContext().ensureActive()
            if (streamOwner !== owner || streamGeneration != generation) return false
            if (!granted) {
                setError(str(R.string.camera_permission_denied))
                return false
            }
            startStreamInternal()
            started = true
            return true
        } finally {
            if (!started && streamOwner === owner && streamGeneration == generation) stopStreamInternal()
        }
    }

    fun stopStream(owner: Any) {
        if (streamOwner === owner) stopStreamInternal()
    }

    /** Main-thread read lease for visual uploads; recheck after off-thread image encoding. */
    fun streamLease(owner: Any): Long? = streamGeneration.takeIf { streamOwner === owner }

    fun ownsStream(owner: Any, lease: Long): Boolean =
        streamOwner === owner && streamGeneration == lease

    fun currentFrame(owner: Any, lease: Long): Bitmap? =
        _currentFrame.value.takeIf { ownsStream(owner, lease) && _streamState.value == StreamState.Streaming }

    /** Fresh result, scoped to the caller and this screen's current camera generation. */
    suspend fun capturePhoto(owner: Any): Bitmap? {
        if (streamOwner !== owner) return null
        return captureCurrentPhoto(streamGeneration)
    }

    /**
     * Start streaming from the wearable device camera through the shared session:
     * acquire -> ensureSessionStarted (waits for a previous session's STOPPED, creates, waits STARTED)
     * -> addCamera -> subscribe -> stream.start().
     */
    fun startStream() {
        // Legacy screens retain their API; their delayed cleanup cannot stop an owned screen.
        streamOwner = null
        streamGeneration++
        startStreamInternal()
    }

    private fun startStreamInternal() {
        Log.d(TAG, "startStream START")

        cancelStreamJobs()
        camera = null
        sessionManager.stopCamera(OWNER)

        // Reset state. clearError() runs before acquire() so a stale message from an earlier
        // failed attempt cannot survive a later successful start; each failure path below sets
        // its own message afterwards.
        clearError()
        _currentFrame.value = null
        _streamState.value = StreamState.Waiting

        val quality = videoQuality()
        Log.d(TAG, "Using video quality: $quality")

        startJob = viewModelScope.launch {
            sessionManager.acquire(OWNER, forCamera = true)
            // Leave-and-re-enter: the previous session may still be STOPPING in the SDK, so the
            // create happens inside ensureSessionStarted() once STOPPED has been observed.
            when (sessionManager.ensureSessionStarted(SESSION_START_TIMEOUT_MS)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> {
                    Log.e(TAG, "createSession failed")
                    // Show the specific DAT reason (e.g. NO_ELIGIBLE_DEVICE) read synchronously
                    // from the manager; the SharedFlow collector may run before or after this
                    // point depending on how the coroutine was resumed (Phase A review Minor #5).
                    val specific = sessionManager.lastSessionError.value?.let { str(GlassesErrorMessages.resId(it)) }
                    failStart(specific ?: str(R.string.glasses_session_failed))
                    return@launch
                }
                SessionStartResult.NOT_STARTED -> {
                    Log.e(TAG, "session did not reach STARTED")
                    failStart(_errorMessage.value ?: str(R.string.glasses_session_timeout))
                    return@launch
                }
            }
            val config = StreamConfiguration(videoQuality = quality, frameRate = FRAME_RATE)
            when (val result = sessionManager.addCamera(OWNER, config)) {
                is CameraResult.Ready -> attachCamera(result.camera)
                is CameraResult.Failed -> {
                    Log.e(TAG, "addCamera failed: ${result.error}")
                    failStart(cameraErrorMessage(result.error))
                }
            }
        }

        Log.d(TAG, "startStream END")
    }

    /** Shared failure path for startStream(): message as state + event, Error state, claim released. */
    private fun failStart(message: String) {
        if (_errorMessage.value != message) setError(message)
        _streamState.value = StreamState.Error(message)
        sessionManager.release(OWNER)
    }

    private fun attachCamera(borrowed: GlassesCamera) {
        camera = borrowed

        // Subscribe before start(): streamState is a StateFlow that replays STOPPED. The frame
        // collector is launched on the worker, so it attaches a few ms after start(); that is fine
        // for a preview. No conflate(): VideoFrame.buffer is only guaranteed valid inside collect {},
        // so FrameConversions copies the bytes as its first step. The AtomicBoolean is the drop
        // policy from spec §5.8.
        videoJob = viewModelScope.launch(frameDispatcher) {
            Log.d(TAG, "Starting video frame collection")
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
                    DatStreamState.STOPPING -> {
                        hasBeenActive = true
                        _streamState.value = StreamState.Waiting
                    }
                    DatStreamState.PAUSED -> {
                        // Paused by a cap-touch tap on the glasses; resumes on the next tap.
                        // Do NOT restart the stream or the session here (spec §5.8).
                        hasBeenActive = true
                        _streamState.value = StreamState.Paused
                    }
                    DatStreamState.STOPPED,
                    DatStreamState.CLOSED -> {
                        if (hasBeenActive) {
                            hasBeenActive = false
                            Log.d(TAG, "Stream terminated, calling stopStream()")
                            stopStreamInternal()
                        }
                    }
                }
            }
        }

        streamErrorJob = viewModelScope.launch {
            borrowed.streamErrors.collect { error ->
                Log.e(TAG, "Stream error: ${error.description}")
                setError(str(GlassesErrorMessages.resId(error)))
            }
        }

        val startError = borrowed.startStream()
        if (startError != null) {
            Log.e(TAG, "stream.start failed: ${startError.description}")
            val message = str(GlassesErrorMessages.resId(startError))
            setError(message)
            stopStreamInternal()
            _streamState.value = StreamState.Error(message)
        }
    }

    /**
     * Stop streaming and give the camera + session claim back to the manager.
     */
    fun stopStream() {
        if (streamOwner == null) stopStreamInternal()
    }

    private fun stopStreamInternal() {
        streamOwner = null
        streamGeneration++
        Log.d(TAG, "stopStream START")

        cancelStreamJobs()
        camera = null
        sessionManager.stopCamera(OWNER)
        sessionManager.release(OWNER)

        // Clear frame (let GC handle bitmap)
        _currentFrame.value = null
        _capturedPhoto.value = null
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
        photoJob?.cancel()
        photoJob = null
        startJob?.cancel()
        startJob = null
        videoJob?.cancel()
        videoJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
    }

    private fun cameraErrorMessage(error: CameraError): String =
        str(GlassesErrorMessages.resId(error))

    /**
     * Capture a photo from the stream (DatResult<PhotoData, CaptureError> in 0.9.0).
     * Returns the previously captured photo synchronously; the new one lands in [capturedPhoto].
     */
    fun takePhoto(): Bitmap? {
        val generation = streamGeneration
        viewModelScope.launch { captureCurrentPhoto(generation) }
        return _capturedPhoto.value
    }

    private suspend fun captureCurrentPhoto(generation: Long): Bitmap? = coroutineScope {
        val activeCamera = camera ?: return@coroutineScope null
        if (generation != streamGeneration || _streamState.value != StreamState.Streaming || photoJob?.isActive == true) {
            return@coroutineScope null
        }
        val job = currentCoroutineContext()[Job]
        photoJob = job
        try {
            when (val result = activeCamera.capturePhoto()) {
                is PhotoCaptureResult.Success -> {
                    val bitmap = withContext(frameDispatcher) { decodePhoto(result.photo) }
                    currentCoroutineContext().ensureActive()
                    if (generation != streamGeneration || camera !== activeCamera) return@coroutineScope null
                    if (bitmap == null) {
                        Log.e(TAG, "Photo decode failed")
                        setError(str(R.string.photo_capture_failed))
                    } else {
                        _capturedPhoto.value = bitmap
                    }
                    bitmap
                }
                is PhotoCaptureResult.Failure -> {
                    currentCoroutineContext().ensureActive()
                    if (generation != streamGeneration || camera !== activeCamera) return@coroutineScope null
                    setError(str(GlassesErrorMessages.resId(result.error)))
                    null
                }
            }
        } finally {
            if (photoJob === job) photoJob = null
        }
    }

    /**
     * Handle incoming (uncompressed YUV, treated as I420) video frames on the frame worker.
     * Runs inside the videoJob coroutine: the isActive check closes the ghost-frame window
     * (a frame mid-conversion when stopStream() cancels the job must not repopulate the flows).
     *
     * Known, accepted trade-off: the published bitmap is the preview decode (JPEG quality 50), and
     * an OpenClaw camera.snap taken while Live AI streams re-encodes it at its own quality, so such
     * a snap is double-lossy compared with iOS's raw preview frame. Encoding a second, higher-quality
     * copy of every frame only for the rare snap would double the per-frame work; snaps taken while
     * nobody streams go through GlassesPhotoCapturer at CAPTURE_JPEG_QUALITY instead.
     */
    private fun CoroutineScope.handleVideoFrame(videoFrame: VideoFrame) {
        val bitmap = FrameConversions.frameToBitmap(videoFrame, FrameConversions.PREVIEW_JPEG_QUALITY) ?: return
        if (!isActive) return
        _currentFrame.value = bitmap
        sessionManager.publishFrame(OWNER, bitmap)
    }

    fun clearCapturedPhoto() {
        _capturedPhoto.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** Sets [errorMessage] (state) and emits the same text once on [errorEvents]. */
    fun setError(message: String) {
        _errorMessage.value = message
        _errorEvents.tryEmit(message)
    }

    // Check if registered with Meta AI app (UNREGISTERING still counts as registered, like the sample)
    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.REGISTERED ||
            _registrationState.value == RegistrationState.UNREGISTERING

    override fun onCleared() {
        Log.d(TAG, "onCleared START - cleaning up all resources")
        super.onCleared()

        stopStreamInternal()

        deviceSelectorJob?.cancel()
        deviceSelectorJob = null
        monitoringStarted = false

        Log.d(TAG, "onCleared END - cleanup complete")
    }
}

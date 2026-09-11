package com.smartview.glassai.glasses

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.smartview.glassai.BuildConfig
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single owner of the one DeviceSession the SDK allows per device (spec §4).
 *
 * - Reference counted: feature owners call [acquire]/[release]; the session stops when the last
 *   owner releases.
 * - Keeps the outgoing session observed until the SDK reports STOPPED and makes
 *   [ensureSessionStarted] wait for it before Wearables.createSession() (0.9.0: stop() only
 *   transitions to STOPPING; creating earlier fails with SESSION_ALREADY_EXISTS).
 * - Lends the single camera capability to one owner at a time ([addCamera] / [stopCamera]).
 * - Exposes session/display/device state as StateFlows and session errors as a SharedFlow.
 * - Attaches one Display on capable devices after STARTED; removes it before stopping the session.
 *
 * Threading: every public function must be called on the main thread. The production scope is
 * Dispatchers.Main.immediate; unit tests inject a TestScope.
 */
class GlassesSessionManager internal constructor(
    private val sessionFactory: DatSessionFactory,
    private val deviceObserver: DatDeviceObserver,
    private val scope: CoroutineScope,
    private val displayEnabled: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "GlassesSessionManager"

        /** How long [ensureSessionStarted] waits for the previous session to report STOPPED. */
        private const val PREVIOUS_STOP_TIMEOUT_MS = 5_000L

        /** Pause before the single retry when createSession() answers SESSION_ALREADY_EXISTS. */
        private const val ALREADY_EXISTS_RETRY_DELAY_MS = 1_000L

        @Volatile
        private var instance: GlassesSessionManager? = null

        /** Process singleton. Requires Wearables.initialize() (done in TurboMetaApplication). */
        fun getInstance(context: Context): GlassesSessionManager =
            instance ?: synchronized(this) {
                instance ?: run {
                    val adapter = WearablesDatAdapter()
                    GlassesSessionManager(
                        sessionFactory = adapter,
                        deviceObserver = adapter,
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                        displayEnabled = {
                            APIKeyManager.getInstance(context.applicationContext).isGlassesDisplayEnabled()
                        },
                    ).also { created ->
                        created.startMonitoring()
                        instance = created
                    }
                }
            }
    }

    private var session: GlassesSession? = null
    /** The session we last stopped, kept until the SDK reports STOPPED (or the wait times out). */
    private var stoppingSession: GlassesSession? = null
    private var stoppingJob: Job? = null
    private var camera: GlassesCamera? = null
    // @Volatile: read by publishFrame() on the frame worker, written on the main thread.
    @Volatile
    private var cameraOwner: String? = null

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    /**
     * The last decoded frame of whichever owner currently borrows the camera (Phase B: the
     * OpenClaw camera.snap source). Cleared whenever the camera is stopped or the session ends.
     */
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    private val _lastSessionError = MutableStateFlow<DeviceSessionError?>(null)
    /**
     * The most recent error also emitted on [sessionError], readable synchronously. Callers that
     * get SessionStartResult.CREATE_FAILED read it to show the specific DAT reason regardless of
     * whether the SharedFlow collector already ran (Phase A review Minor #5).
     */
    val lastSessionError: StateFlow<DeviceSessionError?> = _lastSessionError.asStateFlow()
    private val owners = LinkedHashSet<String>()
    /**
     * Owners that declared a *camera* intent in [acquire] (final review C1). Maintained on the main
     * thread together with [owners]; the off-main readers use [cameraClaimCount] instead.
     */
    private val cameraIntents = LinkedHashSet<String>()
    // @Volatile: read by SessionFrameProvider off the main thread (hasCameraClaim), written here.
    @Volatile
    private var cameraClaimCount = 0

    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var deviceJob: Job? = null

    private val _sessionState = MutableStateFlow(DeviceSessionState.STOPPED)
    /**
     * STOPPED while no session exists; IDLE immediately after a successful createSession; STOPPING
     * from stopSession() until the SDK reports STOPPED for the outgoing session.
     */
    val sessionState: StateFlow<DeviceSessionState> = _sessionState.asStateFlow()

    private var display: GlassesDisplay? = null
    private var displayStateJob: Job? = null
    private var enabledSetting: Boolean? = null
    private val _displayState = MutableStateFlow(GlassesDisplayState.NOT_ATTACHED)
    val displayState: StateFlow<GlassesDisplayState> = _displayState.asStateFlow()
    private val _privateDisplayEpoch = MutableStateFlow(0L)
    /** Invalidates private content on observed readiness loss, even if consumers miss pause/resume. */
    val privateDisplayEpoch: StateFlow<Long> = _privateDisplayEpoch.asStateFlow()
    private val _isDisplayAvailable = MutableStateFlow(false)
    val isDisplayAvailable: StateFlow<Boolean> = _isDisplayAvailable.asStateFlow()
    @VisibleForTesting
    internal var displayAttachAttempts = 0
        private set

    private val _sessionError = MutableSharedFlow<DeviceSessionError>(extraBufferCapacity = 16)
    /** createSession failures and DeviceSession.errors, in order. */
    val sessionError: SharedFlow<DeviceSessionError> = _sessionError.asSharedFlow()

    private val _activeDevice = MutableStateFlow<GlassesDeviceInfo?>(null)
    /** Metadata of the AutoDeviceSelector's active device; null when none is connected. */
    val activeDevice: StateFlow<GlassesDeviceInfo?> = _activeDevice.asStateFlow()

    /** Device.compatibility == DEVICE_UPDATE_REQUIRED → offer Wearables.openFirmwareUpdate(). */
    val isFirmwareUpdateRequired: StateFlow<Boolean> =
        _activeDevice
            .map { it?.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _isDatAppUpdateRequired = MutableStateFlow(false)
    /** Set once DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED was seen → offer openDATGlassesAppUpdate(). */
    val isDatAppUpdateRequired: StateFlow<Boolean> = _isDatAppUpdateRequired.asStateFlow()

    val hasSession: Boolean
        get() = session != null

    /** True between stopSession() and the SDK's STOPPED for the outgoing session. */
    val isStoppingPreviousSession: Boolean
        get() = stoppingSession != null

    val ownerCount: Int
        get() = owners.size

    val currentCameraOwner: String?
        get() = cameraOwner

    /**
     * True while somebody intends to use the camera: an owner that acquired with
     * `forCamera = true` (it may still be between acquire() and addCamera()), or the current
     * camera borrower. A session-only owner — the OpenClaw chat, and in Phase C the Display-card
     * owners — does NOT set this, so camera.snap can borrow the camera while they hold the session
     * (final review C1 / Task 9 D-4).
     *
     * Volatile/immutable reads only: this is the one bookkeeping flag SessionFrameProvider reads
     * off the main thread.
     */
    val hasCameraClaim: Boolean
        get() = cameraClaimCount > 0 || cameraOwner != null

    /**
     * Starts observing the active device. Idempotent.
     *
     * OpenClawIntegration.install() calls this from Application.onCreate(), before Bluetooth
     * permissions are granted (Task 4 fix round 1). The `.catch {}` below only guards the flow's
     * *collection*; it does nothing for a `deviceObserver.activeDeviceInfoFlow()` call that throws
     * synchronously while constructing the flow (Task 4 fix round 2, re-review hardening note).
     * That construction + the collection are both wrapped in `runCatching` so such a throw is
     * logged instead of escaping the launched coroutine, and `deviceJob` is nulled out on failure so
     * a later `startMonitoring()` call retries instead of silently no-op'ing forever.
     *
     * Started LAZY and only `.start()`-ed after `deviceJob` is assigned: with an immediate/unconfined
     * dispatcher (Dispatchers.Main.immediate in production, UnconfinedTestDispatcher in tests) a
     * throw-before-first-suspension body runs to completion inside the `launch {}` call itself, so
     * `deviceJob = scope.launch { ... }` would overwrite the `deviceJob = null` the failure handler
     * just set — leaving `deviceJob` non-null and every later `startMonitoring()` a permanent no-op.
     * Assigning first, then starting, makes the null-out stick.
     */
    fun startMonitoring() {
        checkMain("startMonitoring")
        if (deviceJob != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                deviceObserver.activeDeviceInfoFlow()
                    // Without this the SupervisorJob swallows the failure and activeDevice would
                    // stay null forever with nothing in the log.
                    .catch { Log.e(TAG, "device flow failed", it) }
                    .collect { info ->
                        _activeDevice.value = info
                        updateDisplayAvailability()
                        maybeAttachDisplay()
                    }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "device monitoring failed to start", error)
                deviceJob = null
            }
        }
        deviceJob = job
        // The `.catch {}` above turns a collection failure into a *normal completion*, so
        // runCatching sees success and the old code left deviceJob pointing at a finished Job —
        // every later startMonitoring() was a permanent no-op (final review I2a). Whatever ends
        // this collector, the slot is freed so MainActivity can re-arm it after the Bluetooth
        // grant.
        job.invokeOnCompletion { if (deviceJob === job) deviceJob = null }
        job.start()
    }

    /**
     * Registers [owner]. Fast path: when no session exists and no previous session is still
     * stopping, a session is created and started synchronously. Failures are NOT reported here —
     * owners that need the session call [ensureSessionStarted], which waits for the outgoing
     * session, retries once on SESSION_ALREADY_EXISTS and reports the final error.
     *
     * @param forCamera true for owners that will borrow the camera ([addCamera]); they raise
     *   [hasCameraClaim] from the moment they acquire, so an OpenClaw camera.snap waits for their
     *   first frame instead of racing them for the camera. Leave it false for session-only owners
     *   (the OpenClaw chat holds the session so a snap does not pay the 12 s session start).
     */
    fun acquire(owner: String, forCamera: Boolean = false) {
        checkMain("acquire")
        owners.add(owner)
        if (forCamera && cameraIntents.add(owner)) cameraClaimCount = cameraIntents.size
        Log.d(TAG, "acquire($owner, forCamera=$forCamera) owners=$owners cameraIntents=$cameraIntents " +
            "stoppingPrevious=${stoppingSession != null}")
        if (session == null && stoppingSession == null) {
            val error = createSessionIfNeeded()
            if (error != null) {
                Log.w(TAG, "acquire($owner): createSession failed (${error.description}); ensureSessionStarted() will retry")
            }
        }
    }

    /** Drops [owner]'s claim (and its camera); stops the session when nobody is left. */
    fun release(owner: String) {
        checkMain("release")
        if (!owners.remove(owner)) return
        if (cameraIntents.remove(owner)) cameraClaimCount = cameraIntents.size
        Log.d(TAG, "release($owner) owners=$owners cameraIntents=$cameraIntents")
        if (cameraOwner == owner) stopCamera(owner)
        if (owners.isEmpty()) stopSession()
    }

    /**
     * Synchronous create + start if no session exists.
     *
     * Refuses while the previous session is still STOPPING (returns false, emits nothing): the SDK
     * would answer SESSION_ALREADY_EXISTS. Callers that need a session after a stop must use
     * [ensureSessionStarted], which waits for STOPPED and retries.
     *
     * @return false if the previous session is still stopping, or if the SDK refused — in the
     *   latter case the error is emitted on [sessionError].
     */
    fun ensureSession(): Boolean {
        checkMain("ensureSession")
        if (stoppingSession != null) return false
        val error = createSessionIfNeeded() ?: return true
        _sessionState.value = DeviceSessionState.STOPPED
        _lastSessionError.value = error
        _sessionError.tryEmit(error)
        return false
    }

    /**
     * The call every camera owner makes after [acquire]:
     * 1. waits (≤ 5 s) for the previous session to report STOPPED,
     * 2. creates + starts a session if none exists, retrying once after 1 s on SESSION_ALREADY_EXISTS,
     * 3. waits up to [timeoutMs] for STARTED.
     * Only the final createSession failure is emitted on [sessionError].
     */
    suspend fun ensureSessionStarted(timeoutMs: Long): SessionStartResult {
        checkMain("ensureSessionStarted")
        return ensureSessionStartedFor(null, timeoutMs)
    }

    /** Keeps a feature's session alive without reserving the camera. */
    suspend fun acquireAndStart(owner: String, timeoutMs: Long): SessionStartResult {
        checkMain("acquireAndStart")
        acquire(owner)
        return ensureSessionStartedFor(owner, timeoutMs)
    }

    private suspend fun ensureSessionStartedFor(owner: String?, timeoutMs: Long): SessionStartResult {
        awaitPreviousSessionStopped()
        if (owner != null && owner !in owners) return SessionStartResult.NOT_STARTED
        var error = createSessionIfNeeded()
        if (error == DeviceSessionError.SESSION_ALREADY_EXISTS) {
            Log.w(TAG, "SESSION_ALREADY_EXISTS: SDK still holds the previous session; retrying once")
            delay(ALREADY_EXISTS_RETRY_DELAY_MS)
            if (owner != null && owner !in owners) return SessionStartResult.NOT_STARTED
            error = createSessionIfNeeded()
        }
        if (error != null) {
            Log.e(TAG, "ensureSessionStarted: createSession failed: ${error.description}")
            _sessionState.value = DeviceSessionState.STOPPED
            _lastSessionError.value = error
            _sessionError.tryEmit(error)
            return SessionStartResult.CREATE_FAILED
        }
        return if (awaitStarted(timeoutMs)) SessionStartResult.STARTED else SessionStartResult.NOT_STARTED
    }

    /**
     * Suspends until the session is STARTED (true) or STOPPED / timed out / absent (false).
     * Safe to call right after [acquire]: the state is already IDLE or STARTING by then.
     */
    suspend fun awaitStarted(timeoutMs: Long): Boolean {
        if (session == null) return false
        val terminal = withTimeoutOrNull(timeoutMs) {
            sessionState.first {
                it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
            }
        }
        return terminal == DeviceSessionState.STARTED
    }

    /** @return null when a session exists afterwards, else the SDK error. Never emits. */
    private fun createSessionIfNeeded(): DeviceSessionError? {
        if (session != null) return null
        return when (val result = sessionFactory.createSession()) {
            is SessionCreateResult.Success -> {
                val created = result.session
                session = created
                _sessionState.value = DeviceSessionState.IDLE
                // Subscribe before start() so no transition is missed.
                sessionStateJob = scope.launch {
                    created.state.collect { state -> onSessionState(created, state) }
                }
                sessionErrorJob = scope.launch {
                    created.errors.collect { error -> onSessionError(error) }
                }
                created.start()
                null
            }
            is SessionCreateResult.Failure -> {
                Log.e(TAG, "createSession failed: ${result.error.description}")
                result.error
            }
        }
    }

    /** Waits (bounded) for the outgoing session's STOPPED, then forgets it either way. */
    private suspend fun awaitPreviousSessionStopped() {
        val outgoing = stoppingSession ?: return
        val stopped = withTimeoutOrNull(PREVIOUS_STOP_TIMEOUT_MS) {
            outgoing.state.first { it == DeviceSessionState.STOPPED }
        } != null
        if (!stopped) {
            Log.w(TAG, "previous session did not report STOPPED within ${PREVIOUS_STOP_TIMEOUT_MS}ms; creating anyway")
        }
        clearStopping(outgoing)
    }

    /**
     * @param fromJob the stoppingJob itself when called from inside it; that job must finish on its
     *   own instead of cancelling itself (Phase C adds work after this call).
     */
    private fun clearStopping(outgoing: GlassesSession, fromJob: Job? = null) {
        if (stoppingSession !== outgoing) return
        stoppingSession = null
        val job = stoppingJob
        stoppingJob = null
        if (job != null && job !== fromJob) job.cancel()
        if (session == null) _sessionState.value = DeviceSessionState.STOPPED
    }

    /** Lends the camera to [owner]. Must be called after the session is STARTED. */
    fun addCamera(owner: String, config: StreamConfiguration): CameraResult {
        checkMain("addCamera")
        val current = session ?: return CameraResult.Failed(CameraError.NoSession)
        if (_sessionState.value != DeviceSessionState.STARTED) {
            return CameraResult.Failed(CameraError.SessionNotStarted)
        }
        val holder = cameraOwner
        if (holder != null && holder != owner) {
            Log.w(TAG, "addCamera($owner) refused: camera held by $holder")
            return CameraResult.Failed(CameraError.CameraBusy(holder))
        }
        camera?.let { existing -> return CameraResult.Ready(existing) }
        return when (val result = current.addCamera(config)) {
            is CameraAddResult.Success -> {
                camera = result.camera
                cameraOwner = owner
                Log.d(TAG, "addCamera($owner) ok")
                CameraResult.Ready(result.camera)
            }
            is CameraAddResult.Failure -> {
                Log.e(TAG, "addCamera($owner) failed: ${result.error.description}")
                CameraResult.Failed(CameraError.Sdk(result.error))
            }
        }
    }

    /** Stops and detaches the camera if [owner] holds it; ignored otherwise. */
    fun stopCamera(owner: String) {
        checkMain("stopCamera")
        if (cameraOwner != owner) {
            if (cameraOwner != null) Log.w(TAG, "stopCamera($owner) ignored: held by $cameraOwner")
            return
        }
        Log.d(TAG, "stopCamera($owner)")
        timedStop("camera") { camera?.stop() }
        camera = null
        cameraOwner = null
        _latestFrame.value = null
    }

    /**
     * Called by the current camera owner from its frame worker after decoding a frame. This is the
     * only manager method that may run off the main thread: it touches nothing but a StateFlow and
     * the volatile owner name. Frames from anyone but the current owner are ignored.
     */
    fun publishFrame(owner: String, frame: Bitmap) {
        if (cameraOwner != owner) return
        _latestFrame.value = frame
    }

    /**
     * Test hook (Task 9 parked item 2): forgets every owner, stops the session, drops the parked
     * outgoing session and the latest frame so the next test starts from a clean singleton.
     * Main thread only.
     */
    @VisibleForTesting
    internal fun resetForTests() {
        checkMain("resetForTests")
        detachDisplay()
        deviceJob?.cancel()
        deviceJob = null
        owners.clear()
        cameraIntents.clear()
        cameraClaimCount = 0
        stopSession()
        stoppingJob?.cancel()
        stoppingJob = null
        stoppingSession = null
        _latestFrame.value = null
        _isDatAppUpdateRequired.value = false
        _sessionState.value = DeviceSessionState.STOPPED
        _activeDevice.value = null
        _lastSessionError.value = null
        enabledSetting = null
        _isDisplayAvailable.value = false
        displayAttachAttempts = 0
    }

    /**
     * Stops the session and all capabilities regardless of owners. Idempotent.
     * The outgoing session stays observed until the SDK reports STOPPED (see [ensureSessionStarted]).
     */
    fun stopSession() {
        checkMain("stopSession")
        val current = session ?: return
        Log.d(TAG, "stopSession")
        detachDisplay()
        timedStop("camera") { camera?.stop() }
        camera = null
        cameraOwner = null
        _latestFrame.value = null
        cancelSessionJobs()
        session = null
        _sessionState.value = DeviceSessionState.STOPPING
        stoppingJob?.cancel()
        stoppingSession = current
        // Subscribe before stop(): a synchronous STOPPED must not be missed.
        stoppingJob = scope.launch {
            current.state.first { it == DeviceSessionState.STOPPED }
            Log.d(TAG, "previous session reported STOPPED")
            clearStopping(current, fromJob = coroutineContext[Job])
        }
        timedStop("session") { current.stop() }
    }

    private fun onSessionState(source: GlassesSession, state: DeviceSessionState) {
        if (source !== session) return
        Log.d(TAG, "session state: $state")
        if (state != DeviceSessionState.STARTED) invalidatePrivateDisplayIfReady()
        _sessionState.value = state
        when (state) {
            DeviceSessionState.STARTED -> maybeAttachDisplay()
            DeviceSessionState.STOPPED -> teardownAfterDeviceStop()
            else -> Unit
        }
    }

    private fun onSessionError(error: DeviceSessionError) {
        Log.e(TAG, "session error: ${error.description}")
        if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
            _isDatAppUpdateRequired.value = true
        }
        _lastSessionError.value = error
        _sessionError.tryEmit(error)
    }

    /**
     * The device ended the session. Owners keep their claims, but the lent Camera must still be
     * stopped here: the borrower's stopCamera() is a no-op once cameraOwner is null, so the SDK
     * Camera (and its MediaCodec decoder in the non-compressed path) would only be freed by GC.
     * stop() on an already-stopped capability is expected to be harmless; it is guarded anyway.
     */
    private fun teardownAfterDeviceStop() {
        detachDisplay()
        runCatching { timedStop("camera") { camera?.stop() } }
            .onFailure { Log.w(TAG, "camera.stop() after device stop failed", it) }
        camera = null
        cameraOwner = null
        _latestFrame.value = null
        cancelSessionJobs()
        session = null
        _sessionState.value = DeviceSessionState.STOPPED
    }

    private fun cancelSessionJobs() {
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
    }

    fun currentDisplay(): GlassesDisplay? {
        checkMain("currentDisplay")
        return display
    }

    fun setDisplayEnabled(enabled: Boolean) {
        checkMain("setDisplayEnabled")
        enabledSetting = enabled
        updateDisplayAvailability()
        if (enabled) maybeAttachDisplay() else detachDisplay()
    }

    /** Explicit recovery only; a terminal display state never restarts the user's experience. */
    fun reattachDisplay() {
        checkMain("reattachDisplay")
        detachDisplay()
        maybeAttachDisplay()
    }

    private fun updateDisplayAvailability() {
        val capable = _activeDevice.value?.isDisplayCapable == true
        if (capable && enabledSetting == null) enabledSetting = displayEnabled()
        _isDisplayAvailable.value = capable && enabledSetting == true
    }

    private fun maybeAttachDisplay() {
        val current = session ?: return
        if (_sessionState.value != DeviceSessionState.STARTED || display != null ||
            !_isDisplayAvailable.value) return
        displayAttachAttempts++
        when (val result = current.addDisplay()) {
            is DisplayAddResult.Failure -> onSessionError(result.error)
            is DisplayAddResult.Success -> {
                val attached = result.display
                display = attached
                displayStateJob = scope.launch {
                    attached.state.collect { state ->
                        if (display === attached) {
                            val next = GlassesDisplayState.valueOf(state.name)
                            if (next != GlassesDisplayState.STARTED) invalidatePrivateDisplayIfReady()
                            _displayState.value = next
                        }
                    }
                }
            }
        }
    }

    private fun detachDisplay() {
        val attached = display ?: return
        invalidatePrivateDisplayIfReady()
        displayStateJob?.cancel()
        displayStateJob = null
        display = null
        val error = session?.removeDisplay()
        if (error != null || session == null) {
            if (error == DeviceSessionError.SESSION_ALREADY_STOPPED ||
                error == DeviceSessionError.CAPABILITY_NOT_FOUND) {
                Log.d(TAG, "display already removed: $error")
            } else if (error != null) {
                Log.e(TAG, "removeDisplay failed: ${error.description}")
            }
            runCatching { attached.close() }.onFailure { Log.w(TAG, "display.close failed", it) }
        }
        _displayState.value = GlassesDisplayState.NOT_ATTACHED
    }

    private fun invalidatePrivateDisplayIfReady() {
        if (_sessionState.value == DeviceSessionState.STARTED &&
            _displayState.value == GlassesDisplayState.STARTED) {
            _privateDisplayEpoch.value++
        }
    }

    private inline fun timedStop(capability: String, stop: () -> Unit) {
        val started = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        try { stop() } finally {
            if (BuildConfig.DEBUG) Log.d(TAG, "$capability.stop() took ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    private fun checkMain(function: String) {
        if (BuildConfig.DEBUG) {
            val main = Looper.getMainLooper()
            if (main != null) check(Looper.myLooper() === main) {
                "GlassesSessionManager: $function must be called on the main thread"
            }
        }
    }
}

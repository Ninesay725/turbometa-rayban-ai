package com.smartview.glassai.glasses

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.smartview.glassai.TurboMetaApplication
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A JPEG-encoded glasses frame ready for base64. */
data class FrameSnapshot(val jpeg: ByteArray, val width: Int, val height: Int)

sealed class SnapshotResult {
    data class Ok(val frame: FrameSnapshot) : SnapshotResult()
    /** No glasses connected, app in background, permission check failed, … — see [detail]. */
    data class NotReady(val detail: String) : SnapshotResult()
    /** The camera stream could not be started; see [detail]. */
    data class StreamFailed(val detail: String) : SnapshotResult()
    /** A stream exists but no decodable frame arrived within the budget. */
    object NoFrame : SnapshotResult()
    /** The wearable CAMERA permission was denied; only an Activity can request it. */
    object PermissionRequired : SnapshotResult()
    object EncodeFailed : SnapshotResult()
}

/**
 * What OpenClawCommandRouter needs from the glasses (spec §6 B1). Implemented by
 * [SessionFrameProvider] in the app and by fakes in tests.
 */
interface GlassesFrameProvider {
    val hasActiveDevice: Boolean
    /** iOS parity: true while "streaming" or "waiting". */
    val isStreaming: Boolean
    /** "streaming" | "waiting" | "stopped". */
    val streamStatus: String
    val hasFrame: Boolean

    /** Scales to [maxWidth] (if wider), JPEG at [quality] (0.1..1.0), within [timeoutMs]. */
    suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult
}

/**
 * Frame source for OpenClaw (Phase A final review, Recommendation 2, design (b)):
 * 1. If a feature currently borrows the camera, the last frame it published to
 *    GlassesSessionManager.latestFrame is used (a snap during Live AI returns the live frame).
 * 2. If it borrows the camera but has not published yet, wait up to [timeoutMs] for a frame.
 * 3. If nobody holds the camera, borrow it briefly through GlassesPhotoCapturer (owner
 *    "OpenClawSnap"), which stops the camera and releases the claim before returning.
 * Never runs when no Activity is started (spec §1: no background camera.snap).
 *
 * Known limit (documented in android/README.md): QuickVisionService borrows the camera for a few
 * seconds per wake-word capture and never publishes to latestFrame. A camera.snap that lands in
 * that window gets CameraBusy from the capturer, waits [timeoutMs] on latestFrame, and answers
 * NO_FRAME; the gateway simply retries. Live AI / Live Stream / RTMP do publish, so snaps during
 * those return the live frame.
 *
 * [sessionManager] is a provider so installing this at app start does not create the manager
 * before the Bluetooth runtime permissions are granted.
 */
class SessionFrameProvider(
    private val sessionManager: () -> GlassesSessionManager,
    private val isForeground: () -> Boolean,
    private val checkPermission: suspend () -> CameraPermissionCheck,
    private val encode: (Bitmap, Int, Double) -> FrameSnapshot?,
    private val capture: suspend (GlassesSessionManager) -> PhotoCaptureOutcome<Bitmap>,
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GlassesFrameProvider {

    companion object {
        private const val TAG = "SessionFrameProvider"
        const val OWNER = "OpenClawSnap"

        fun create(app: Application): SessionFrameProvider {
            val registration = WearablesRegistrationGateway(app)
            return SessionFrameProvider(
                sessionManager = { GlassesSessionManager.getInstance(app) },
                isForeground = { (app as? TurboMetaApplication)?.isInForeground ?: true },
                checkPermission = { registration.checkCameraPermission() },
                encode = ::encodeBitmap,
                capture = { manager ->
                    val capturer = GlassesPhotoCapturer(
                        sessionManager = manager,
                        owner = OWNER,
                        config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
                        decodePhoto = FrameConversions::decodePhoto,
                        decodeFrame = { FrameConversions.frameToBitmap(it, FrameConversions.CAPTURE_JPEG_QUALITY) },
                    )
                    // GlassesSessionManager contract: capture() must run on the main thread.
                    withContext(Dispatchers.Main.immediate) { capturer.capture() }
                },
            )
        }

        /** Downscale to [maxWidth] if wider, JPEG at quality*100. Never call on the main thread. */
        fun encodeBitmap(bitmap: Bitmap, maxWidth: Int, quality: Double): FrameSnapshot? = try {
            val scaled = if (maxWidth > 0 && bitmap.width > maxWidth) {
                val height = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
            } else {
                bitmap
            }
            val jpeg = ByteArrayOutputStream().use { stream ->
                val ok = scaled.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(10, 100), stream)
                if (!ok) return null
                stream.toByteArray()
            }
            FrameSnapshot(jpeg, scaled.width, scaled.height)
        } catch (e: Exception) {
            Log.e(TAG, "encode failed: ${e.message}")
            null
        }
    }

    override val hasActiveDevice: Boolean
        get() = sessionManager().activeDevice.value != null

    override val hasFrame: Boolean
        get() = sessionManager().liveFrame() != null

    override val streamStatus: String
        get() {
            val manager = sessionManager()
            return when {
                manager.liveFrame() != null -> "streaming"
                manager.currentCameraOwner != null -> "waiting"
                else -> "stopped"
            }
        }

    override val isStreaming: Boolean
        get() = streamStatus != "stopped"

    override suspend fun snapshot(maxWidth: Int, quality: Double, timeoutMs: Long): SnapshotResult {
        if (!isForeground()) return SnapshotResult.NotReady("App is in background")
        val manager = sessionManager()

        manager.liveFrame()?.let { return encodeOrFail(it, maxWidth, quality) }

        if (manager.currentCameraOwner != null) {
            // Someone streams but has not decoded a frame yet: wait for the first one.
            val frame = manager.awaitLiveFrame(timeoutMs)
            return if (frame != null) encodeOrFail(frame, maxWidth, quality) else SnapshotResult.NoFrame
        }

        when (val permission = checkPermission()) {
            CameraPermissionCheck.Denied -> return SnapshotResult.PermissionRequired
            is CameraPermissionCheck.Failed -> return SnapshotResult.NotReady(permission.description)
            CameraPermissionCheck.Granted -> Unit
        }

        return when (val outcome = capture(manager)) {
            is PhotoCaptureOutcome.Captured -> encodeOrFail(outcome.image, maxWidth, quality)
            PhotoCaptureOutcome.NoDevice -> SnapshotResult.NotReady("No glasses connected")
            PhotoCaptureOutcome.SessionFailed -> SnapshotResult.StreamFailed("Could not start the glasses session")
            PhotoCaptureOutcome.SessionTimeout -> SnapshotResult.StreamFailed("Glasses session did not start in time")
            is PhotoCaptureOutcome.CameraUnavailable -> {
                if (outcome.error is CameraError.CameraBusy) {
                    // Another owner grabbed the camera while we waited: use its next frame.
                    val frame = manager.awaitLiveFrame(timeoutMs)
                    if (frame != null) encodeOrFail(frame, maxWidth, quality) else SnapshotResult.NoFrame
                } else {
                    SnapshotResult.StreamFailed("Camera unavailable: ${outcome.error}")
                }
            }
            is PhotoCaptureOutcome.StreamStartFailed -> SnapshotResult.StreamFailed(outcome.error.description)
            PhotoCaptureOutcome.StreamTimeout -> SnapshotResult.StreamFailed("Stream did not start in time")
            PhotoCaptureOutcome.NoImage -> SnapshotResult.NoFrame
        }
    }

    private suspend fun encodeOrFail(bitmap: Bitmap, maxWidth: Int, quality: Double): SnapshotResult {
        val snapshot = withContext(encodeDispatcher) { encode(bitmap, maxWidth, quality) }
        return if (snapshot != null) SnapshotResult.Ok(snapshot) else SnapshotResult.EncodeFailed
    }
}

/**
 * The published frame, but only while the manager still reports a live camera (Task 4 review
 * pointer 1). GlassesSessionManager.publishFrame() checks the owner and only then writes
 * latestFrame, so a frame decoded on a borrower worker can land *after* stopCamera() cleared the
 * flow on the main thread: latestFrame alone is not proof that the camera is live. camera.snap
 * must not hand a stale frame to the gateway — with no live owner it falls through to the
 * capturer, which takes a fresh photo.
 *
 * Reading this off the main thread is safe and is the only manager access SessionFrameProvider
 * makes outside Dispatchers.Main: currentCameraOwner is @Volatile, the rest are StateFlow values.
 */
private fun GlassesSessionManager.liveFrame(): Bitmap? =
    if (currentCameraOwner != null && sessionState.value == DeviceSessionState.STARTED) {
        latestFrame.value
    } else {
        null
    }

/** Suspends until [liveFrame] is available, at most [timeoutMs]; null on timeout. */
private suspend fun GlassesSessionManager.awaitLiveFrame(timeoutMs: Long): Bitmap? =
    withTimeoutOrNull(timeoutMs) { latestFrame.first { liveFrame() != null } }

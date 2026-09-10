package com.smartview.glassai.glasses

import android.util.Log
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one [GlassesPhotoCapturer.capture] attempt. */
sealed class PhotoCaptureOutcome<out T> {
    /** [image] came from Stream.capturePhoto() (fromVideoFrame = false) or from the first decoded frame. */
    data class Captured<T>(val image: T, val fromVideoFrame: Boolean) : PhotoCaptureOutcome<T>()
    /** No active device within the device wait budget. */
    object NoDevice : PhotoCaptureOutcome<Nothing>()
    /** Wearables.createSession refused (the error was emitted on GlassesSessionManager.sessionError). */
    object SessionFailed : PhotoCaptureOutcome<Nothing>()
    /** The session did not reach STARTED in time, or stopped first. */
    object SessionTimeout : PhotoCaptureOutcome<Nothing>()
    /** The manager refused to lend the camera (CameraBusy, NoSession, SessionNotStarted, Sdk). */
    data class CameraUnavailable(val error: CameraError) : PhotoCaptureOutcome<Nothing>()
    /** Stream.start() failed. */
    data class StreamStartFailed(val error: StreamError) : PhotoCaptureOutcome<Nothing>()
    /** The stream never reached STREAMING within the budget. */
    object StreamTimeout : PhotoCaptureOutcome<Nothing>()
    /** capturePhoto() failed and no decodable video frame arrived within the fallback budget. */
    object NoImage : PhotoCaptureOutcome<Nothing>()
}

/**
 * The wake-word capture path (spec §5.5) as one testable unit:
 * wait for a device -> acquire -> ensureSessionStarted -> addCamera -> stream.start() ->
 * wait STREAMING -> capturePhoto() -> (fallback) first decoded video frame -> stopCamera + release.
 *
 * [T] is the decoded image type (Bitmap in the app, any type in JVM tests). Frames are decoded on
 * [frameDispatcher] (Dispatchers.Default in the app) so the main thread never converts pixels
 * (spec §5.8). capture() must be called on the main thread (GlassesSessionManager contract) and
 * always gives the camera and the owner claim back before returning, also when cancelled.
 */
class GlassesPhotoCapturer<T : Any>(
    private val sessionManager: GlassesSessionManager,
    private val owner: String,
    private val config: StreamConfiguration,
    private val decodePhoto: (PhotoData) -> T?,
    private val decodeFrame: (VideoFrame) -> T?,
    private val frameDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceWaitMs: Long = DEFAULT_DEVICE_WAIT_MS,
    private val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
    private val streamTimeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS,
    private val fallbackFrameTimeoutMs: Long = DEFAULT_FALLBACK_FRAME_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "GlassesPhotoCapturer"

        /** activeDevice is a StateFlow that starts null; its first emission is asynchronous. */
        const val DEFAULT_DEVICE_WAIT_MS = 3_000L
        /** spec §5.5: session budget relaxed to 12 s. */
        const val DEFAULT_SESSION_TIMEOUT_MS = 12_000L
        const val DEFAULT_STREAM_TIMEOUT_MS = 12_000L
        const val DEFAULT_FALLBACK_FRAME_TIMEOUT_MS = 2_000L

        /** ensureSessionStarted + addCamera attempts; see [borrowCameraAndCapture]. */
        private const val BORROW_ATTEMPTS = 2
    }

    suspend fun capture(): PhotoCaptureOutcome<T> {
        // Never read activeDevice.value here: when the wake-word service is the first
        // getInstance() caller the StateFlow is still null although glasses are connected.
        val hasDevice = withTimeoutOrNull(deviceWaitMs) {
            sessionManager.activeDevice.first { it != null }
        } != null
        if (!hasDevice) {
            Log.w(TAG, "no active device within ${deviceWaitMs}ms")
            return PhotoCaptureOutcome.NoDevice
        }
        sessionManager.acquire(owner)
        try {
            return borrowCameraAndCapture()
        } finally {
            sessionManager.stopCamera(owner)
            sessionManager.release(owner)
        }
    }

    private suspend fun borrowCameraAndCapture(): PhotoCaptureOutcome<T> {
        // Two attempts: another owner (WearablesViewModel.disconnect()) can stop the shared session
        // while this capture is in flight, which leaves our fresh claim pointing at a session that
        // is gone. NoSession / SessionNotStarted are therefore re-started once before giving up.
        var borrowed: GlassesCamera? = null
        for (attempt in 1..BORROW_ATTEMPTS) {
            when (sessionManager.ensureSessionStarted(sessionTimeoutMs)) {
                SessionStartResult.STARTED -> Unit
                SessionStartResult.CREATE_FAILED -> return PhotoCaptureOutcome.SessionFailed
                SessionStartResult.NOT_STARTED -> return PhotoCaptureOutcome.SessionTimeout
            }
            when (val result = sessionManager.addCamera(owner, config)) {
                is CameraResult.Ready -> {
                    borrowed = result.camera
                    break
                }
                is CameraResult.Failed -> {
                    val sessionGone = result.error is CameraError.NoSession ||
                        result.error is CameraError.SessionNotStarted
                    if (!sessionGone || attempt == BORROW_ATTEMPTS) {
                        Log.e(TAG, "addCamera refused: ${result.error}")
                        return PhotoCaptureOutcome.CameraUnavailable(result.error)
                    }
                    Log.w(TAG, "addCamera refused (${result.error}); session went away, retrying once")
                }
            }
        }
        val camera = borrowed ?: return PhotoCaptureOutcome.CameraUnavailable(CameraError.NoSession)
        return coroutineScope {
            val fallbackFrame = CompletableDeferred<T>()
            // Subscribe BEFORE start(); frames are decoded off the main thread.
            val frameJob = launch(frameDispatcher) {
                camera.videoFrames.collect { frame ->
                    if (frame.isCompressed || frame.isCodecConfig) return@collect
                    if (!fallbackFrame.isCompleted) {
                        decodeFrame(frame)?.let { fallbackFrame.complete(it) }
                    }
                }
            }
            try {
                captureFrom(camera, fallbackFrame)
            } finally {
                frameJob.cancel()
            }
        }
    }

    private suspend fun captureFrom(
        camera: GlassesCamera,
        fallbackFrame: CompletableDeferred<T>,
    ): PhotoCaptureOutcome<T> {
        camera.startStream()?.let { error ->
            Log.e(TAG, "stream.start failed: ${error.description}")
            return PhotoCaptureOutcome.StreamStartFailed(error)
        }
        val streaming = withTimeoutOrNull(streamTimeoutMs) {
            camera.streamState.first { it == DatStreamState.STREAMING }
        } != null
        if (!streaming) {
            Log.e(TAG, "stream did not reach STREAMING within ${streamTimeoutMs}ms")
            return PhotoCaptureOutcome.StreamTimeout
        }
        when (val result = camera.capturePhoto()) {
            is PhotoCaptureResult.Success -> {
                val image = decodePhoto(result.photo)
                if (image != null) {
                    Log.d(TAG, "capturePhoto ok")
                    return PhotoCaptureOutcome.Captured(image, fromVideoFrame = false)
                }
                Log.w(TAG, "capturePhoto returned undecodable data; using a video frame")
            }
            is PhotoCaptureResult.Failure ->
                Log.w(TAG, "capturePhoto failed: ${result.error.description}; using a video frame")
        }
        val frame = withTimeoutOrNull(fallbackFrameTimeoutMs) { fallbackFrame.await() }
        return if (frame != null) {
            PhotoCaptureOutcome.Captured(frame, fromVideoFrame = true)
        } else {
            PhotoCaptureOutcome.NoImage
        }
    }
}

package com.smartview.glassai.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class CameraStopReason { TIMER, USER, LIFECYCLE, START_DENIED, CAPTURE_FAILED, STREAM_FAILED }

internal data class CameraPageState<T>(
    val photo: T? = null,
    val minutes: Int = 5,
    val remainingSeconds: Int = 0,
    val starting: Boolean = false,
    val active: Boolean = false,
    val capturing: Boolean = false,
    val stopReason: CameraStopReason? = null,
)

/** Main-confined page policy. Photos are references in RAM, never saved state or recycled here. */
internal class CameraSessionController<T : Any>(
    private val scope: CoroutineScope,
    private val stopStream: (Any) -> Unit,
    private val capturePhoto: suspend (Any) -> T?,
    private val nowMillis: () -> Long,
) {
    private val mutable = MutableStateFlow(CameraPageState<T>())
    val state = mutable.asStateFlow()
    private var visibleOwner: Any? = null
    private var startAction: (suspend () -> Boolean)? = null
    private class Run(val owner: Any)
    private var run: Run? = null
    private var startJob: Job? = null
    private var captureJob: Job? = null
    private var timerJob: Job? = null

    fun enter(owner: Any, start: suspend () -> Boolean) {
        if (visibleOwner !== owner && run != null) finishLifecycle()
        visibleOwner = owner
        startAction = start
        if (state.value.photo == null && state.value.stopReason in listOf(null, CameraStopReason.LIFECYCLE)) {
            restart()
        }
    }

    fun leave(owner: Any) {
        if (visibleOwner !== owner) return
        visibleOwner = null
        startAction = null
        finishLifecycle()
    }

    fun restart() {
        val owner = visibleOwner ?: return
        val start = startAction ?: return
        if (run != null || state.value.photo != null) return
        val current = Run(owner)
        run = current
        mutable.value = state.value.copy(starting = true, stopReason = null)
        startJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val launched = start()
                currentCoroutineContext().ensureActive()
                if (run !== current) return@launch
                if (!launched) {
                    finish(CameraStopReason.START_DENIED)
                } else {
                    mutable.value = state.value.copy(starting = false, active = true)
                    armTimer(current)
                }
            } catch (cancelled: CancellationException) {
                if (run === current) finish(CameraStopReason.STREAM_FAILED)
                throw cancelled
            } catch (_: Exception) {
                if (run === current) finish(CameraStopReason.STREAM_FAILED)
            }
        }
    }

    fun selectMinutes(minutes: Int) {
        require(minutes in listOf(1, 5, 10, 15))
        mutable.value = state.value.copy(minutes = minutes)
        // Changing the selection starts a fresh stop budget without restarting DAT.
        run?.takeIf { state.value.active }?.let(::armTimer)
    }

    private fun armTimer(current: Run) {
        timerJob?.cancel()
        val deadline = nowMillis() + state.value.minutes * 60_000L
        timerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (run === current) {
                val remaining = (deadline - nowMillis()).coerceAtLeast(0)
                mutable.value = state.value.copy(remainingSeconds = ((remaining + 999) / 1000).toInt())
                if (remaining == 0L) {
                    finish(CameraStopReason.TIMER)
                    return@launch
                }
                delay(minOf(remaining, 1_000))
            }
        }
    }

    fun capture() {
        val current = run ?: return
        if (!state.value.active || state.value.capturing) return
        mutable.value = state.value.copy(capturing = true)
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val photo = withTimeoutOrNull(15_000) { capturePhoto(current.owner) }
                currentCoroutineContext().ensureActive()
                if (run !== current) return@launch
                if (photo == null) {
                    finish(CameraStopReason.CAPTURE_FAILED)
                } else {
                    // Retain the fresh bitmap before releasing the stream and navigating away.
                    mutable.value = state.value.copy(photo = photo)
                    finish(null)
                }
            } catch (cancelled: CancellationException) {
                if (run === current) finish(CameraStopReason.CAPTURE_FAILED)
                throw cancelled
            } catch (_: Exception) {
                if (run === current) finish(CameraStopReason.CAPTURE_FAILED)
            }
        }
    }

    fun retake() {
        mutable.value = state.value.copy(photo = null, stopReason = null)
        restart()
    }

    fun stop() = finish(CameraStopReason.USER)
    fun streamFailed() = finish(CameraStopReason.STREAM_FAILED)

    private fun finishLifecycle() {
        // A permission Activity may itself cause STOP. Returning must not reopen that dialog
        // automatically after the suspended request was cancelled.
        finish(if (state.value.starting) CameraStopReason.START_DENIED else CameraStopReason.LIFECYCLE)
    }

    private fun finish(reason: CameraStopReason?) {
        val current = run ?: return
        run = null
        startJob?.cancel()
        startJob = null
        captureJob?.cancel()
        captureJob = null
        timerJob?.cancel()
        timerJob = null
        mutable.value = state.value.copy(
            starting = false, active = false, capturing = false, remainingSeconds = 0, stopReason = reason,
        )
        stopStream(current.owner)
    }

    fun close() {
        visibleOwner = null
        startAction = null
        finishLifecycle()
    }
}

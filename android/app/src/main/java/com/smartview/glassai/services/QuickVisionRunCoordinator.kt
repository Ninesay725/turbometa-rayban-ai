package com.smartview.glassai.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread run ordering; a result may be replaced only after its old run fully releases. */
internal class QuickVisionRunCoordinator(
    scope: CoroutineScope,
    private val cleanup: () -> Unit,
    private val onFinished: () -> Unit,
) {
    // Includes both the old run and its waiting replacement, so stop also cancels an old run
    // when the replacement has not started executing cancelAndJoin yet.
    private val runScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var job: Job? = null
    private var rerunAllowed = false
    private var stopped = false

    fun request(run: suspend () -> Unit): Boolean {
        if (stopped || (job?.isActive == true && !rerunAllowed)) return false
        val previous = job
        rerunAllowed = false
        val next = runScope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
            ensureActive()
            try {
                run()
            } finally {
                cleanup()
            }
            // An accepted Again invalidates the old run's completion even if the old run
            // finishes naturally before the replacement gets a chance to cancel it.
            if (!stopped && job === coroutineContext[Job]) onFinished()
        }
        job = next
        next.start()
        return true
    }

    fun allowRerun() { rerunAllowed = true }

    fun stop() {
        stopped = true
        runScope.cancel()
    }
}

internal enum class QuickVisionSpeechOutcome { DONE, ERROR, STOPPED, REJECTED, TIMED_OUT }

/**
 * Thread-safe completion for one utterance. Queue rejection is immediate; accepted speech gets
 * at most 60 seconds. Cancellation propagates, and cleanup always stops/detaches that utterance.
 */
internal suspend fun awaitQuickVisionSpeech(
    start: ((QuickVisionSpeechOutcome) -> Unit) -> Boolean,
    stop: () -> Unit,
    timeoutMs: Long = 60_000L,
): QuickVisionSpeechOutcome {
    val completion = CompletableDeferred<QuickVisionSpeechOutcome>()
    try {
        if (!start { completion.complete(it) }) return QuickVisionSpeechOutcome.REJECTED
        return withTimeoutOrNull(timeoutMs) { completion.await() } ?: QuickVisionSpeechOutcome.TIMED_OUT
    } finally {
        stop()
    }
}

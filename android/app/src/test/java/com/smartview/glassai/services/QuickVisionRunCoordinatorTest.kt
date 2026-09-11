package com.smartview.glassai.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickVisionRunCoordinatorTest {
    @Test
    fun duplicateRequestsDuringCaptureDoNotStartAnotherRun() = runTest(UnconfinedTestDispatcher()) {
        var captures = 0
        var cleanups = 0
        val runs = QuickVisionRunCoordinator(backgroundScope, { cleanups++ }, {})
        assertTrue(runs.request { captures++; awaitCancellation() })
        assertFalse(runs.request { captures++ })
        assertEquals(1, captures)
        runs.stop()
        runCurrent()
        assertEquals(1, cleanups)
    }

    @Test
    fun againJoinsTheOldFinalizerBeforeReusingTheCaptureOwner() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val runs = QuickVisionRunCoordinator(backgroundScope, { events += "release" }, { events += "finished" })
        runs.request {
            events += "capture 1"
            runs.allowRerun()
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    events += "old finally"
                    delay(50)
                    events += "old finally done"
                }
            }
        }
        assertTrue(runs.request { events += "capture 2" })
        runCurrent()
        assertEquals(listOf("capture 1", "old finally"), events)
        assertFalse(runs.request { events += "unwanted capture" })
        advanceTimeBy(49)
        runCurrent()
        assertEquals(2, events.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("capture 1", "old finally", "old finally done", "release",
            "capture 2", "release", "finished"), events)
    }

    @Test
    fun stopDuringReplacementPreventsTheQueuedCapture() = runTest(UnconfinedTestDispatcher()) {
        var captures = 0
        var releases = 0
        var finishes = 0
        val runs = QuickVisionRunCoordinator(backgroundScope, { releases++ }, { finishes++ })
        runs.request {
            captures++
            runs.allowRerun()
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(50) } }
        }
        runs.request { captures++ }
        runs.stop()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(1, captures)
        assertEquals(1, releases)
        assertEquals(0, finishes)
        assertFalse(runs.request { captures++ })
    }

    @Test
    fun stopBeforeReplacementStartsAlsoCancelsTheOldRun() = runTest(StandardTestDispatcher()) {
        var captures = 0
        var releases = 0
        val runs = QuickVisionRunCoordinator(backgroundScope, { releases++ }, {})
        runs.request {
            captures++
            runs.allowRerun()
            awaitCancellation()
        }
        runCurrent()
        runs.request { captures++ }
        runs.stop() // The replacement has not executed its cancelAndJoin yet.
        runCurrent()
        assertEquals(1, captures)
        assertEquals(1, releases)
    }

    @Test
    fun oldNaturalCompletionCannotStopTheServiceAfterAgainWasAccepted() = runTest(StandardTestDispatcher()) {
        val firstDone = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val runs = QuickVisionRunCoordinator(backgroundScope, { events += "release" }, { events += "finished" })
        runs.request {
            events += "capture 1"
            runs.allowRerun()
            firstDone.await()
        }
        runCurrent()
        firstDone.complete(Unit) // Queue the old completion ahead of the replacement coroutine.
        assertTrue(runs.request { events += "capture 2" })
        runCurrent()
        assertEquals(listOf("capture 1", "release", "capture 2", "release", "finished"), events)
    }

    @Test
    fun againDuringSpeechStopsSpeechAndReleasesBeforeNextCapture() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val runs = QuickVisionRunCoordinator(backgroundScope, { events += "release" }, { events += "finished" })
        runs.request {
            events += "capture 1"
            runs.allowRerun()
            awaitQuickVisionSpeech(start = { events += "speak"; true }, stop = { events += "stop speech" })
            events += "stale dwell"
        }
        runs.request { events += "capture 2" }
        runCurrent()
        assertEquals(listOf("capture 1", "speak", "stop speech", "release", "capture 2", "release", "finished"), events)
    }

    @Test
    fun againDuringDwellCancelsTheOldTimer() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val runs = QuickVisionRunCoordinator(backgroundScope, { events += "release" }, { events += "finished" })
        runs.request {
            runs.allowRerun()
            delay(15_000)
            events += "old dwell completed"
        }
        runs.request { events += "new capture" }
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf("release", "new capture", "release", "finished"), events)
    }

    @Test
    fun synchronousSpeechRejectionDoesNotWaitForACallback() = runTest {
        var stops = 0
        val outcome = awaitQuickVisionSpeech(start = { false }, stop = { stops++ })
        assertEquals(QuickVisionSpeechOutcome.REJECTED, outcome)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, stops)
    }

    @Test
    fun rejectedSpeechStillLetsTheRunDwellAndReleaseItsClaim() = runTest(UnconfinedTestDispatcher()) {
        var claimHeld = false
        var finishes = 0
        val runs = QuickVisionRunCoordinator(backgroundScope, { claimHeld = false }, { finishes++ })
        runs.request {
            claimHeld = true
            val outcome = awaitQuickVisionSpeech(start = { false }, stop = {})
            assertEquals(QuickVisionSpeechOutcome.REJECTED, outcome)
            delay(15_000)
        }
        assertTrue(claimHeld)
        advanceTimeBy(15_000)
        runCurrent()
        assertFalse(claimHeld)
        assertEquals(1, finishes)
    }

    @Test
    fun synchronousCallbackBeforeQueueReturnsIsNotLost() = runTest {
        val outcome = awaitQuickVisionSpeech(
            start = { complete -> complete(QuickVisionSpeechOutcome.DONE); true },
            stop = {},
        )
        assertEquals(QuickVisionSpeechOutcome.DONE, outcome)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun speechCallbackDoneErrorAndStopAllCompleteTheWait() = runTest(UnconfinedTestDispatcher()) {
        for (expected in listOf(QuickVisionSpeechOutcome.DONE, QuickVisionSpeechOutcome.ERROR, QuickVisionSpeechOutcome.STOPPED)) {
            var callback: ((QuickVisionSpeechOutcome) -> Unit)? = null
            var stops = 0
            val speech = async {
                awaitQuickVisionSpeech(start = { callback = it; true }, stop = { stops++ })
            }
            assertFalse(speech.isCompleted)
            callback!!(expected)
            assertEquals(expected, speech.await())
            assertEquals(1, stops)
        }
    }

    @Test
    fun duplicateAndLateCallbacksCannotCompleteAnotherUtterance() = runTest(UnconfinedTestDispatcher()) {
        var firstCallback: ((QuickVisionSpeechOutcome) -> Unit)? = null
        var secondCallback: ((QuickVisionSpeechOutcome) -> Unit)? = null
        val first = async { awaitQuickVisionSpeech(start = { firstCallback = it; true }, stop = {}) }
        firstCallback!!(QuickVisionSpeechOutcome.DONE)
        firstCallback!!(QuickVisionSpeechOutcome.ERROR)
        assertEquals(QuickVisionSpeechOutcome.DONE, first.await())
        val second = async { awaitQuickVisionSpeech(start = { secondCallback = it; true }, stop = {}) }
        firstCallback!!(QuickVisionSpeechOutcome.STOPPED)
        assertFalse(second.isCompleted)
        secondCallback!!(QuickVisionSpeechOutcome.DONE)
        assertEquals(QuickVisionSpeechOutcome.DONE, second.await())
    }

    @Test
    fun speechWithoutCallbackTimesOutAndStopsTheEngine() = runTest(UnconfinedTestDispatcher()) {
        var stops = 0
        val speech = async { awaitQuickVisionSpeech(start = { true }, stop = { stops++ }) }
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(speech.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(QuickVisionSpeechOutcome.TIMED_OUT, speech.await())
        assertEquals(1, stops)
    }

    @Test
    fun cancelledSpeechStopsAndPropagatesCancellation() = runTest(UnconfinedTestDispatcher()) {
        var callback: ((QuickVisionSpeechOutcome) -> Unit)? = null
        var stops = 0
        val speech = async { awaitQuickVisionSpeech(start = { callback = it; true }, stop = { stops++ }) }
        speech.cancel()
        speech.join()
        assertTrue(speech.isCancelled)
        assertEquals(1, stops)
        callback!!(QuickVisionSpeechOutcome.DONE) // Harmless after cancellation and cleanup.
        assertTrue(speech.isCancelled)
    }

    @Test
    fun callbackFromAnEngineThreadResumesOnTheCallingDispatcher() = runTest(StandardTestDispatcher()) {
        var callback: ((QuickVisionSpeechOutcome) -> Unit)? = null
        var resumedOn: Thread? = null
        val speech = async {
            val result = awaitQuickVisionSpeech(start = { callback = it; true }, stop = {})
            resumedOn = Thread.currentThread()
            result
        }
        runCurrent()
        val engineThread = Thread { callback!!(QuickVisionSpeechOutcome.DONE) }
        engineThread.start()
        engineThread.join()
        assertFalse(speech.isCompleted)
        runCurrent()
        assertEquals(QuickVisionSpeechOutcome.DONE, speech.await())
        assertEquals(Thread.currentThread(), resumedOn)
    }
}

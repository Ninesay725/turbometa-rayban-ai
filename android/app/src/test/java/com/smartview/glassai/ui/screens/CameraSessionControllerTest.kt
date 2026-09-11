package com.smartview.glassai.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraSessionControllerTest {
    private class Fixture(scope: TestScope) {
        val starts = mutableListOf<Any>()
        val stops = mutableListOf<Any>()
        val captures = mutableListOf<Any>()
        var onStart: suspend () -> Boolean = { true }
        var onCapture: suspend () -> Any? = { CompletableDeferred<Any?>().await() }
        val controller = CameraSessionController(
            scope = scope.backgroundScope,
            stopStream = { stops.add(it) },
            capturePhoto = { captures.add(it); onCapture() },
            nowMillis = { scope.testScheduler.currentTime },
        )
        fun enter(owner: Any = Any()): Any {
            controller.enter(owner) { starts.add(owner); onStart() }
            return owner
        }
    }

    @Test fun allFourBudgetsReleaseAtTheirDeadlineAndNeedExplicitRestart() = runTest {
        for (minutes in listOf(1, 5, 10, 15)) {
            val f = Fixture(this)
            f.controller.selectMinutes(minutes)
            val visit = f.enter()
            runCurrent()
            advanceTimeBy(minutes * 60_000L - 1)
            assertTrue(f.controller.state.value.active)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(CameraStopReason.TIMER, f.controller.state.value.stopReason)
            assertEquals(listOf(visit), f.stops)
            f.controller.leave(visit)
            f.enter()
            assertEquals(1, f.starts.size)
            f.controller.restart()
            assertEquals(2, f.starts.size)
            f.controller.close()
        }
    }

    @Test fun stopCancelsOldTimerAndResumeGetsANewOwner() = runTest {
        val f = Fixture(this)
        f.controller.selectMinutes(1)
        val firstVisit = f.enter()
        advanceTimeBy(30_000)
        f.controller.leave(firstVisit)
        val secondVisit = f.enter()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(f.controller.state.value.active)
        assertEquals(listOf(firstVisit), f.stops)
        f.controller.leave(secondVisit)
        f.controller.leave(secondVisit)
        assertEquals(f.starts, f.stops)
    }

    @Test fun retiredLifecycleCleanupCannotStopAReplacementVisit() = runTest {
        val f = Fixture(this)
        val oldVisit = f.enter()
        val newVisit = f.enter()
        val releasedBeforeOldCleanup = f.stops.toList()
        f.controller.leave(oldVisit)
        assertTrue(f.controller.state.value.active)
        assertEquals(releasedBeforeOldCleanup, f.stops)
        f.controller.leave(newVisit)
        assertEquals(newVisit, f.stops.last())
    }

    @Test fun deniedStartRequiresExplicitRetryAndDoesNotStartTimer() = runTest {
        val f = Fixture(this)
        f.onStart = { false }
        val visit = f.enter()
        advanceTimeBy(60_000)
        assertEquals(CameraStopReason.START_DENIED, f.controller.state.value.stopReason)
        assertFalse(f.controller.state.value.active)
        f.controller.leave(visit)
        f.enter()
        assertEquals(1, f.starts.size)
        f.onStart = { true }
        f.controller.restart()
        assertTrue(f.controller.state.value.active)
    }

    @Test fun leavingDuringPermissionRejectsLateApproval() = runTest {
        val f = Fixture(this)
        val approval = CompletableDeferred<Boolean>()
        f.onStart = { withContext(NonCancellable) { approval.await() } }
        val visit = f.enter()
        assertTrue(f.controller.state.value.starting)
        f.controller.leave(visit)
        approval.complete(true)
        runCurrent()
        assertFalse(f.controller.state.value.active)
        assertFalse(f.controller.state.value.starting)
        assertEquals(CameraStopReason.START_DENIED, f.controller.state.value.stopReason)
        f.enter()
        assertEquals(1, f.starts.size)
    }

    @Test fun freshCancellableHelperResultIsTheOnlyPreview() = runTest {
        val f = Fixture(this)
        val photo = Any()
        val capture = CompletableDeferred<Any?>()
        f.onCapture = { capture.await() }
        val owner = f.enter()
        f.controller.capture()
        assertTrue(f.controller.state.value.capturing)
        assertNull(f.controller.state.value.photo)
        capture.complete(photo)
        runCurrent()
        assertSame(photo, f.controller.state.value.photo)
        assertFalse(f.controller.state.value.active)
        assertEquals(listOf(owner), f.captures)
        assertEquals(listOf(owner), f.stops)
    }

    @Test fun immediatePhotoResultIsNotLost() = runTest {
        val f = Fixture(this)
        val photo = Any()
        f.onCapture = { photo }
        f.enter()
        f.controller.capture()
        assertSame(photo, f.controller.state.value.photo)
        assertFalse(f.controller.state.value.capturing)
    }

    @Test fun duplicateTapCannotStartASecondCapture() = runTest {
        val f = Fixture(this)
        f.enter()
        f.controller.capture()
        f.controller.capture()
        assertEquals(1, f.captures.size)
    }

    @Test fun captureTimeoutReleasesAndRequiresExplicitRestart() = runTest {
        val f = Fixture(this)
        f.enter()
        f.controller.capture()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(CameraStopReason.CAPTURE_FAILED, f.controller.state.value.stopReason)
        assertEquals(f.starts, f.stops)
        assertNull(f.controller.state.value.photo)
        f.enter()
        assertEquals(1, f.starts.size)
    }

    @Test fun stopDuringCaptureRejectsLateResultWithoutAffectingNextCapture() = runTest {
        val f = Fixture(this)
        val late = CompletableDeferred<Any?>()
        f.onCapture = { withContext(NonCancellable) { late.await() } }
        val visit = f.enter()
        f.controller.capture()
        f.controller.leave(visit)
        val fresh = CompletableDeferred<Any?>()
        f.onCapture = { fresh.await() }
        f.enter()
        f.controller.capture()
        late.complete(Any())
        runCurrent()
        assertNull(f.controller.state.value.photo)
        assertTrue(f.controller.state.value.capturing)
        val photo = Any()
        fresh.complete(photo)
        runCurrent()
        assertSame(photo, f.controller.state.value.photo)
    }

    @Test fun timerExpiryCancelsPendingCapture() = runTest {
        val f = Fixture(this)
        f.controller.selectMinutes(1)
        f.enter()
        advanceTimeBy(59_000)
        f.controller.capture()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(f.controller.state.value.photo)
        assertFalse(f.controller.state.value.capturing)
        assertEquals(CameraStopReason.TIMER, f.controller.state.value.stopReason)
        assertEquals(1, f.stops.size)
    }

    @Test fun previewSurvivesHandoffAndReturnWithoutRestartUntilRetake() = runTest {
        val f = Fixture(this)
        val photo = Any()
        val visit = f.enter()
        f.onCapture = { photo }
        f.controller.capture()
        f.controller.leave(visit)
        f.enter()
        assertSame(photo, f.controller.state.value.photo)
        assertEquals(1, f.starts.size)
        f.controller.retake()
        assertNull(f.controller.state.value.photo)
        assertEquals(2, f.starts.size)
    }

    @Test fun changingBudgetResetsDeadlineWithoutRestartingTheStream() = runTest {
        val f = Fixture(this)
        f.enter()
        advanceTimeBy(20_000)
        f.controller.selectMinutes(1)
        advanceTimeBy(59_999)
        assertTrue(f.controller.state.value.active)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CameraStopReason.TIMER, f.controller.state.value.stopReason)
        assertEquals(1, f.starts.size)
    }

    @Test fun manualStopIsNotUndoneByLifecycleResume() = runTest {
        val f = Fixture(this)
        val visit = f.enter()
        f.controller.stop()
        f.controller.leave(visit)
        f.enter()
        assertEquals(CameraStopReason.USER, f.controller.state.value.stopReason)
        assertEquals(1, f.starts.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedBudgetIsRejected() = runTest {
        Fixture(this).controller.selectMinutes(0)
    }
}

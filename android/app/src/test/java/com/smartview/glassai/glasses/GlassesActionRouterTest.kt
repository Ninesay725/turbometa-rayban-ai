package com.smartview.glassai.glasses

import android.app.ForegroundServiceStartNotAllowedException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesActionRouterTest {
    private val sink = RecordingDisplaySink()
    private val navigation = MutableSharedFlow<NavigationRequest>(replay = 0, extraBufferCapacity = 4)
    private val requests = mutableListOf<NavigationRequest>()
    private var quickVisionStarts = 0
    private val router = GlassesActionRouter(sink, navigation) { quickVisionStarts++ }

    private fun TestScope.collectNavigation(): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigation.collect { requests += it }
        }

    // Equal values are still distinct controllers: unregister must compare identity.
    private data class LiveAi(val name: String = "live") : LiveAiController {
        var ends = 0
        override fun end() { ends++ }
    }

    private data class OpenClaw(val name: String = "openclaw") : OpenClawController {
        var snaps = 0
        override fun snapAndSend() { snaps++ }
    }

    @Test fun startLiveAiEmitsTheLiveAiRequest() = runTest {
        collectNavigation()
        router.dispatch(DisplayAction.StartLiveAI)
        assertEquals(listOf(NavigationRequest.LiveAI), requests)
    }

    @Test fun startLeanEatEmitsTheLeanEatRequest() = runTest {
        collectNavigation()
        router.dispatch(DisplayAction.StartLeanEat)
        assertEquals(listOf(NavigationRequest.LeanEat), requests)
    }

    @Test fun startQuickVisionStartsTheService() = runTest {
        collectNavigation()
        router.dispatch(DisplayAction.StartQuickVision)
        assertEquals(1, quickVisionStarts)
        assertTrue(requests.isEmpty())
    }

    @Test fun quickVisionBackgroundStartRejectionIsDroppedWithoutNavigation() = runTest {
        collectNavigation()
        val restrictedRouter = GlassesActionRouter(sink, navigation) {
            throw ForegroundServiceStartNotAllowedException("Background start denied")
        }
        restrictedRouter.dispatch(DisplayAction.StartQuickVision)
        assertNoSideEffects()
        restrictedRouter.dispatch(DisplayAction.BackToMenu)
        assertEquals(1, sink.statusCalls)
    }

    @Test fun quickVisionSecurityFailureIsDroppedWithoutNavigation() = runTest {
        collectNavigation()
        val restrictedRouter = GlassesActionRouter(sink, navigation) {
            throw SecurityException("Microphone permission denied")
        }
        restrictedRouter.dispatch(DisplayAction.StartQuickVision)
        assertNoSideEffects()
        restrictedRouter.dispatch(DisplayAction.BackToMenu)
        assertEquals(1, sink.statusCalls)
    }

    @Test fun endLiveAiCallsTheRegisteredController() {
        val controller = LiveAi()
        router.registerLiveAi(controller)
        router.dispatch(DisplayAction.EndLiveAI)
        assertEquals(1, controller.ends)
    }

    @Test fun endLiveAiWithoutControllerIsANoOp() = runTest {
        collectNavigation()
        router.dispatch(DisplayAction.EndLiveAI)
        assertNoSideEffects()
    }

    @Test fun unregisteringAnotherControllerKeepsTheCurrentOne() {
        val controller = LiveAi()
        router.registerLiveAi(controller)
        router.unregisterLiveAi(LiveAi())
        router.dispatch(DisplayAction.EndLiveAI)
        assertEquals(1, controller.ends)
    }

    @Test fun unregisteringTheLiveAiControllerClearsIt() {
        val controller = LiveAi()
        router.registerLiveAi(controller)
        router.unregisterLiveAi(controller)
        router.dispatch(DisplayAction.EndLiveAI)
        assertEquals(0, controller.ends)
    }

    @Test fun registeringLiveAiReplacesThePreviousController() {
        val previous = LiveAi()
        val current = LiveAi()
        router.registerLiveAi(previous)
        router.registerLiveAi(current)
        router.unregisterLiveAi(previous)
        router.dispatch(DisplayAction.EndLiveAI)
        assertEquals(0, previous.ends)
        assertEquals(1, current.ends)
    }

    @Test fun pageShowsTheCarriedCard() {
        val card = DisplayCard.QuickVision("Describe", "A long reply", page = 2)
        router.dispatch(DisplayAction.Page(card))
        assertEquals(1, sink.shown.size)
        assertSame(card, sink.last)
    }

    @Test fun backToMenuShowsStatus() {
        router.dispatch(DisplayAction.BackToMenu)
        assertEquals(1, sink.statusCalls)
        assertEquals(0, sink.clearCalls)
    }

    @Test fun openClawSnapPrefersTheController() = runTest {
        collectNavigation()
        val controller = OpenClaw()
        router.registerOpenClaw(controller)
        router.dispatch(DisplayAction.OpenClawSnap)
        assertEquals(1, controller.snaps)
        assertTrue(requests.isEmpty())
    }

    @Test fun openClawSnapWithoutControllerNavigates() = runTest {
        collectNavigation()
        router.dispatch(DisplayAction.OpenClawSnap)
        assertEquals(listOf(NavigationRequest.OpenClaw), requests)
    }

    @Test fun unregisteringAnotherOpenClawControllerKeepsTheCurrentOne() {
        val controller = OpenClaw()
        router.registerOpenClaw(controller)
        router.unregisterOpenClaw(OpenClaw())
        router.dispatch(DisplayAction.OpenClawSnap)
        assertEquals(1, controller.snaps)
    }

    @Test fun unregisteringTheOpenClawControllerRestoresNavigation() = runTest {
        collectNavigation()
        val controller = OpenClaw()
        router.registerOpenClaw(controller)
        router.unregisterOpenClaw(controller)
        router.dispatch(DisplayAction.OpenClawSnap)
        assertEquals(0, controller.snaps)
        assertEquals(listOf(NavigationRequest.OpenClaw), requests)
    }

    @Test fun registeringOpenClawReplacesThePreviousController() {
        val previous = OpenClaw()
        val current = OpenClaw()
        router.registerOpenClaw(previous)
        router.registerOpenClaw(current)
        router.unregisterOpenClaw(previous)
        router.dispatch(DisplayAction.OpenClawSnap)
        assertEquals(0, previous.snaps)
        assertEquals(1, current.snaps)
    }

    @Test fun musicActionsAreLoggedOnly() = runTest {
        collectNavigation()
        router.dispatch(DisplayAction.MusicPlayPause)
        router.dispatch(DisplayAction.MusicNext)
        router.dispatch(DisplayAction.MusicPrev)
        assertNoSideEffects()
    }

    @Test fun navigationWithoutCollectorIsDropped() = runTest {
        router.dispatch(DisplayAction.StartLiveAI)
        router.dispatch(DisplayAction.StartLeanEat)
        router.dispatch(DisplayAction.OpenClawSnap)
        collectNavigation()
        assertTrue(requests.isEmpty())
        assertTrue(navigation.replayCache.isEmpty())
    }

    @Test fun restartingCollectionDoesNotReplayBackgroundTaps() = runTest {
        val firstCollector = collectNavigation()
        router.dispatch(DisplayAction.StartLiveAI)
        firstCollector.cancel()
        firstCollector.join()
        assertEquals(0, navigation.subscriptionCount.value)
        router.dispatch(DisplayAction.StartLeanEat)
        router.dispatch(DisplayAction.OpenClawSnap)
        collectNavigation()
        router.dispatch(DisplayAction.StartLiveAI)
        assertEquals(listOf(NavigationRequest.LiveAI, NavigationRequest.LiveAI), requests)
    }

    @Test fun startedLifecycleCollectionDropsStoppedTaps() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        try {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            backgroundScope.launch(dispatcher) {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    navigation.collect { requests += it }
                }
            }
            assertEquals(0, navigation.subscriptionCount.value)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertEquals(1, navigation.subscriptionCount.value)
            router.dispatch(DisplayAction.StartLiveAI)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            assertEquals(0, navigation.subscriptionCount.value)
            router.dispatch(DisplayAction.StartLeanEat)
            router.dispatch(DisplayAction.OpenClawSnap)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertEquals(listOf(NavigationRequest.LiveAI), requests)
            router.dispatch(DisplayAction.StartLeanEat)
            assertEquals(listOf(NavigationRequest.LiveAI, NavigationRequest.LeanEat), requests)
        } finally {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            Dispatchers.resetMain()
        }
    }

    private fun assertNoSideEffects() {
        assertTrue(requests.isEmpty())
        assertTrue(sink.shown.isEmpty())
        assertEquals(0, sink.statusCalls)
        assertEquals(0, sink.clearCalls)
        assertEquals(0, quickVisionStarts)
    }
}

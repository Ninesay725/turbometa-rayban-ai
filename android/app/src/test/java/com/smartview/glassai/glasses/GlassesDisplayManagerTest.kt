package com.smartview.glassai.glasses

import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.display.types.DisplayError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesDisplayManagerTest {
    private val feature = DisplayCard.QuickVision("Vision", "First result")
    private val newer = DisplayCard.QuickVision("Vision", "New result")
    private val status = DisplayCard.Status("Display glasses", false, false)

    @Test
    fun retiringAnOldFeatureCannotClearTheNewFeaturesCard() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        val first = f.manager.ownedSink(Any())
        val second = f.manager.ownedSink(Any())
        first.show(feature)
        f.manager.showStatus() // Done, then a different feature starts.
        second.show(newer)
        first.showStatus()
        first.clear()
        assertEquals(newer, f.manager.currentCard.value)
        second.showStatus()
        assertNull(f.manager.currentCard.value)
    }

    @Test
    fun pagingPreservesTheFeatureThatMayDismissTheCard() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        val owner = f.manager.ownedSink(Any())
        owner.show(feature)
        f.manager.showPage(feature.copy(page = 1))
        owner.showStatus()
        assertNull(f.manager.currentCard.value)
    }

    @Test
    fun queuedButtonsFromReplacedOrDetachedContentCannotRestoreOldCards() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        runCurrent()
        f.start()
        runCurrent()
        f.manager.actionHandler = { action ->
            when (action) {
                is DisplayAction.Page -> f.manager.showPage(action.card)
                DisplayAction.BackToMenu -> f.manager.showStatus()
                else -> Unit
            }
        }
        f.manager.show(feature)
        runCurrent()
        val oldTap = f.manager.actionCallback()
        oldTap(DisplayAction.Page(feature.copy(page = 1)))
        f.manager.show(newer)
        runCurrent()
        assertEquals(newer, f.manager.currentCard.value)
        val beforeDisable = f.manager.actionCallback()
        beforeDisable(DisplayAction.BackToMenu)
        f.sessions.setDisplayEnabled(false)
        runCurrent()
        assertEquals(newer, f.manager.currentCard.value)
    }

    private fun streaming(text: String, isFinal: Boolean = false) =
        DisplayCard.LiveAI(LiveAIPhase.SPEAKING, null, text, isFinal)

    private inner class Fixture(
        testScope: TestScope,
        mainDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScope.testScheduler),
        sendDispatcher: CoroutineDispatcher = mainDispatcher,
        strings: DisplayStrings = FixedDisplayStrings(),
    ) {
        val factory = FakeDatSessionFactory()
        val observer = FakeDatDeviceObserver()
        val sessions = GlassesSessionManager(factory, observer, testScope.backgroundScope)
        val manager: GlassesDisplayManager
        val display: FakeGlassesDisplay get() = factory.last.display

        init {
            sessions.startMonitoring()
            observer.device.value = GlassesDeviceInfo(
                "display-1", "Display glasses", DeviceType.META_RAYBAN_DISPLAY, true,
                DeviceCompatibility.COMPATIBLE,
            )
            sessions.acquire("A")
            factory.last.emitStarted()
            manager = GlassesDisplayManager(
                sessions, testScope.backgroundScope, strings,
                DisplayStatusProvider { status }, mainDispatcher, sendDispatcher,
                clock = { testScope.testScheduler.currentTime },
            )
        }

        fun start() = display.emitStarted()
    }

    /** Pauses only the IO dispatch, without executing the SDK's JSON-building content block. */
    private class QueuedSendDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pendingCount: Int get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun finishOne() { tasks.removeFirst().run() }
    }

    @Test
    fun nothingIsSentBeforeTheDisplayIsStarted() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(feature)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, f.display.sendCalls)
        assertNull(f.manager.lastSent.value)
        assertEquals(feature, f.manager.currentCard.value)
    }

    @Test
    fun statusCardIsSentWhenTheDisplayStartsAndNoCardIsActive() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.start()
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        assertEquals(status, f.manager.lastSent.value)
        assertNull(f.manager.currentCard.value)
    }

    @Test
    fun showSendsTheCardAndSetsCurrentCard() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(feature)
        f.start()
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        assertEquals(feature, f.manager.currentCard.value)
        assertEquals(feature, f.manager.lastSent.value)
    }

    @Test
    fun intermediateCardsAreDroppedBySerialSending() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this, mainDispatcher = StandardTestDispatcher(testScheduler))
        f.start()
        runCurrent()
        val baseline = f.display.sendCalls
        f.manager.show(feature)
        f.manager.show(DisplayCard.Notice("Working", "Intermediate"))
        f.manager.show(newer)
        runCurrent()
        assertEquals(baseline + 1, f.display.sendCalls)
        assertEquals(newer, f.manager.lastSent.value)
    }

    @Test
    fun liveAiStreamingCardsAreCoalescedToOnePerSixHundredMs() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        val first = streaming("1")
        f.manager.show(first)
        f.start()
        runCurrent()
        assertEquals(1, f.display.sendCalls) // Virtual time zero: first card is immediate.
        repeat(4) { index ->
            advanceTimeBy(60)
            f.manager.show(streaming("${index + 2}"))
            runCurrent()
        }
        assertEquals(first, f.manager.lastSent.value)
        assertEquals(1, f.display.sendCalls)
        advanceTimeBy(359)
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(streaming("5"), f.manager.lastSent.value)
    }

    @Test
    fun finalLiveAiCardBypassesTheCoalescer() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(streaming("First"))
        f.start()
        runCurrent()
        advanceTimeBy(100)
        f.manager.show(streaming("Partial"))
        runCurrent()
        val finalCard = streaming("Complete", isFinal = true)
        f.manager.show(finalCard)
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(finalCard, f.manager.lastSent.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, f.display.sendCalls)
    }

    @Test
    fun nonLiveCardCancelsTheStreamingWait() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(streaming("First"))
        f.start()
        runCurrent()
        f.manager.show(streaming("Waiting"))
        runCurrent()
        f.manager.show(feature)
        runCurrent()
        assertEquals(feature, f.manager.lastSent.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, f.display.sendCalls)
    }

    @Test
    fun invalidSessionStateKeepsTheCardAndResendsOnNextStarted() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.display.scriptedSendFailures += DisplayError.INVALID_SESSION_STATE
        f.manager.show(feature)
        f.start()
        runCurrent()
        assertNull(f.manager.lastSent.value)
        assertEquals(feature, f.manager.currentCard.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        f.display.emitStopped()
        runCurrent()
        f.display.emitStarted()
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(feature, f.manager.lastSent.value)
    }

    @Test
    fun renderingFailedSendsTheFallbackNoticeOnce() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.display.scriptedSendFailures += DisplayError.RENDERING_FAILED
        f.manager.show(feature)
        f.start()
        runCurrent()
        assertEquals(2, f.display.sentBlocks.size)
        assertEquals(DisplayCard.Notice("Vision", "First result"), f.manager.lastSent.value)
        assertEquals(feature, f.manager.currentCard.value)
    }

    @Test
    fun failingFallbackIsDroppedWithoutAThirdSend() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        repeat(2) { f.display.scriptedSendFailures += DisplayError.RENDERING_FAILED }
        f.manager.show(feature)
        f.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, f.display.sentBlocks.size)
        assertNull(f.manager.lastSent.value)
        assertEquals(feature, f.manager.currentCard.value)
    }

    @Test
    fun deviceDisconnectedDropsTheCard() = runTest(UnconfinedTestDispatcher()) {
        assertFailureIsDropped(DisplayError.DEVICE_DISCONNECTED)
    }

    @Test
    fun unexpectedErrorDropsTheCard() = runTest(UnconfinedTestDispatcher()) {
        assertFailureIsDropped(DisplayError.UNEXPECTED_ERROR)
    }

    private fun TestScope.assertFailureIsDropped(error: DisplayError) {
        val f = Fixture(this)
        f.display.scriptedSendFailures += error
        f.manager.show(feature)
        f.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        assertNull(f.manager.lastSent.value)
        assertEquals(feature, f.manager.currentCard.value)
    }

    @Test
    fun showStatusForgetsTheFeatureCardAndSendsStatus() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(feature)
        f.start()
        runCurrent()
        f.manager.showStatus()
        runCurrent()
        assertNull(f.manager.currentCard.value)
        assertEquals(status, f.manager.lastSent.value)
        assertEquals(2, f.display.sendCalls)
    }

    @Test
    fun clearCallsClearDisplayAndForgetsTheCard() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(feature)
        f.start()
        runCurrent()
        f.manager.clear()
        runCurrent()
        assertEquals(1, f.display.clearCalls)
        assertNull(f.manager.currentCard.value)
        f.manager.show(newer)
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(newer, f.manager.lastSent.value)
    }

    @Test
    fun clearBeforeStartedIsDroppedAndStartShowsStatus() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(feature)
        f.manager.clear()
        runCurrent()
        f.start()
        runCurrent()
        assertEquals(0, f.display.clearCalls)
        assertEquals(status, f.manager.lastSent.value)
        assertNull(f.manager.currentCard.value)
    }

    @Test
    fun currentCardSurvivesDisplayDetachAndIsResentOnReattach() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(feature)
        f.start()
        runCurrent()
        val old = f.display
        f.sessions.setDisplayEnabled(false)
        runCurrent()
        assertEquals(feature, f.manager.currentCard.value)
        f.sessions.setDisplayEnabled(true)
        f.start()
        runCurrent()
        assertEquals(1, old.sendCalls)
        assertEquals(1, f.display.sendCalls)
        assertEquals(feature, f.manager.lastSent.value)
    }

    @Test
    fun displayManagerNeverAcquiresTheSession() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.start()
        f.manager.show(feature)
        f.manager.showStatus()
        f.manager.clear()
        runCurrent()
        f.display.emitStopped()
        runCurrent()
        f.start()
        runCurrent()
        assertEquals(1, f.sessions.ownerCount)
        assertFalse(f.sessions.hasCameraClaim)
        assertEquals(1, f.factory.createCalls)
    }

    @Test
    fun dispatchFromSdkHopsToMainAndCallsTheHandler() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this, mainDispatcher = StandardTestDispatcher(testScheduler))
        val handled = mutableListOf<DisplayAction>()
        val threads = mutableListOf<Thread>()
        f.manager.actionHandler = { handled += it; threads += Thread.currentThread() }
        val sdkThread = Thread { f.manager.dispatchFromSdk(DisplayAction.StartQuickVision) }
        sdkThread.start()
        sdkThread.join()
        assertTrue(handled.isEmpty())
        runCurrent()
        assertEquals(listOf(DisplayAction.StartQuickVision), handled)
        assertSame(Thread.currentThread(), threads.single())
    }

    @Test
    fun dispatchWithoutHandlerIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this, mainDispatcher = StandardTestDispatcher(testScheduler))
        f.manager.dispatchFromSdk(DisplayAction.StartLiveAI)
        runCurrent()
        val handled = mutableListOf<DisplayAction>()
        f.manager.actionHandler = { handled += it }
        runCurrent()
        assertTrue(handled.isEmpty())
        assertEquals(0, f.display.sendCalls)
    }

    @Test
    fun replacingAnInflightSendRetainsItsBookkeepingAndSendsOnlyLatestNext() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        f.manager.show(feature)
        f.start()
        runCurrent()
        assertEquals(1, io.pendingCount)
        f.manager.show(DisplayCard.Notice("Intermediate", "Drop"))
        f.manager.show(newer)
        runCurrent()
        assertEquals(1, io.pendingCount) // A second SDK call must not run concurrently.
        io.finishOne()
        runCurrent()
        assertEquals(feature, f.manager.lastSent.value) // Cancellation cannot skip this success.
        assertEquals(1, io.pendingCount)
        io.finishOne()
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(newer, f.manager.lastSent.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun oldRenderingFailureCannotOverwriteANewerPendingCard() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        f.display.scriptedSendFailures += DisplayError.RENDERING_FAILED
        f.manager.show(feature)
        f.start()
        runCurrent()
        f.manager.show(newer)
        runCurrent()
        io.finishOne()
        runCurrent()
        io.finishOne()
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(newer, f.manager.lastSent.value)
        assertEquals(newer, f.manager.currentCard.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun oldRenderingFailureCannotReplacePendingClear() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        f.display.scriptedSendFailures += DisplayError.RENDERING_FAILED
        f.manager.show(feature)
        f.start()
        runCurrent()
        f.manager.clear()
        runCurrent()
        io.finishOne()
        runCurrent()
        io.finishOne()
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        assertEquals(1, f.display.clearCalls)
        assertNull(f.manager.currentCard.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun anInflightClearFinishesBeforeTheNewCard() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        f.start()
        runCurrent()
        io.finishOne()
        runCurrent()
        f.manager.clear()
        runCurrent()
        f.manager.show(newer)
        runCurrent()
        assertEquals(1, io.pendingCount)
        io.finishOne()
        runCurrent()
        assertEquals(1, f.display.clearCalls)
        assertEquals(1, f.display.sendCalls) // The card cannot overtake the clear.
        io.finishOne()
        runCurrent()
        assertEquals(newer, f.manager.lastSent.value)
        assertEquals(2, f.display.sendCalls)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun lateDetachedFailureCannotReplayItsFallbackOnTheNewDisplay() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        val detached = f.display
        detached.scriptedSendFailures += DisplayError.RENDERING_FAILED
        f.manager.show(feature)
        f.start()
        runCurrent()
        f.sessions.setDisplayEnabled(false)
        f.manager.show(newer)
        f.sessions.setDisplayEnabled(true)
        f.start()
        runCurrent()
        io.finishOne()
        runCurrent()
        assertNull(f.manager.lastSent.value)
        io.finishOne()
        runCurrent()
        assertEquals(1, detached.sendCalls)
        assertEquals(1, f.display.sendCalls)
        assertEquals(newer, f.manager.lastSent.value)
        assertEquals(newer, f.manager.currentCard.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun lateDetachedSuccessDoesNotPublishStaleLastSent() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        f.manager.show(feature)
        f.start()
        runCurrent()
        f.sessions.setDisplayEnabled(false)
        runCurrent()
        io.finishOne()
        runCurrent()
        assertNull(f.manager.lastSent.value)
        assertEquals(feature, f.manager.currentCard.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun deviceNameStatusProviderReadsCurrentDeviceNameAndKeepsFlagsFalse() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        val provider = DeviceNameStatusProvider(f.sessions)
        assertEquals(status, provider.currentStatus())
        f.observer.device.value = f.observer.device.value!!.copy(name = "Renamed glasses")
        assertEquals(DisplayCard.Status("Renamed glasses", false, false), provider.currentStatus())
        f.observer.device.value = null
        assertEquals(DisplayCard.Status("", false, false), provider.currentStatus())
    }

    @Test
    fun layoutIsBuiltOnMainSendRunsOnIoAndBookkeepingReturnsToMain() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val layoutThreads = mutableListOf<Thread>()
        val strings = object : DisplayStrings by FixedDisplayStrings() {
            override val liveAi: String
                get() {
                    layoutThreads += Thread.currentThread()
                    return "Live AI"
                }
        }
        val f = Fixture(this, StandardTestDispatcher(testScheduler), io, strings)
        f.start()
        runCurrent()
        assertTrue(layoutThreads.isNotEmpty())
        assertTrue(layoutThreads.all { it === Thread.currentThread() })
        assertEquals(0, f.display.sendCalls)
        val sdkThread = Thread { io.finishOne() }
        sdkThread.start()
        sdkThread.join()
        assertEquals(1, f.display.sendCalls)
        assertNull(f.manager.lastSent.value) // IO has completed, Main has not resumed.
        runCurrent()
        assertEquals(status, f.manager.lastSent.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun finalSendUpdatesTheClockEvenWhenANewerStreamingCardCancelledItsCollector() = runTest(UnconfinedTestDispatcher()) {
        val io = QueuedSendDispatcher()
        val f = Fixture(this, sendDispatcher = io)
        val finalCard = streaming("Final", isFinal = true)
        f.manager.show(finalCard)
        f.start()
        runCurrent()
        f.manager.show(streaming("Next turn"))
        runCurrent()
        io.finishOne()
        runCurrent()
        assertEquals(finalCard, f.manager.lastSent.value)
        assertEquals(0, io.pendingCount)
        advanceTimeBy(599)
        runCurrent()
        assertEquals(0, io.pendingCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, io.pendingCount)
        io.finishOne()
        runCurrent()
        assertEquals(streaming("Next turn"), f.manager.lastSent.value)
        assertEquals(0, io.pendingCount)
    }

    @Test
    fun stoppingDuringTheStreamingDelayPreventsASendUntilStartedAgain() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture(this)
        f.manager.show(streaming("First"))
        f.start()
        runCurrent()
        f.manager.show(streaming("Pending"))
        runCurrent()
        f.display.emitStopped()
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, f.display.sendCalls)
        f.start()
        runCurrent()
        assertEquals(2, f.display.sendCalls)
        assertEquals(streaming("Pending"), f.manager.lastSent.value)
    }
}

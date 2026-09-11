package com.smartview.glassai.services

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Pcm16PlaybackTest {
    @Test fun oddChunkBoundariesAndPartialWritesPreserveEveryByte() = runTest {
        val track = Track(maxWrite = 2)
        val playback = StreamingPcm16Playback({ track }, StandardTestDispatcher(testScheduler))
        playback.play(flowOf(byteArrayOf(1), byteArrayOf(2, 3, 4), byteArrayOf(), byteArrayOf(5, 6)))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), track.bytes)
        assertEquals(1, track.closed)
    }

    @Test fun completionWaitsForPlayedFramesNotJustAcceptedWrites() = runTest {
        val track = Track(autoDrain = false)
        val playback = StreamingPcm16Playback({ track }, StandardTestDispatcher(testScheduler))
        val task = async { playback.play(flowOf(byteArrayOf(1, 2, 3, 4))) }
        runCurrent()
        assertFalse(task.isCompleted)
        track.frames = 2
        advanceTimeBy(20)
        task.await()
        assertEquals(1, track.closed)
    }

    @Test fun incompleteFinalSampleFailsAndReleasesTrack() = runTest {
        val track = Track()
        val playback = StreamingPcm16Playback({ track }, StandardTestDispatcher(testScheduler))
        try { playback.play(flowOf(byteArrayOf(1))); fail("Expected incomplete sample") }
        catch (_: SpeechPlaybackException) { }
        assertEquals(1, track.closed)
    }

    @Test fun stalledWritesAndDrainBothHaveDeadlines() = runTest {
        for (track in listOf(Track(maxWrite = 0), Track(autoDrain = false))) {
            val playback = StreamingPcm16Playback({ track }, StandardTestDispatcher(testScheduler), 50, 50)
            try { playback.play(flowOf(byteArrayOf(1, 2))); fail("Expected deadline") }
            catch (_: SpeechPlaybackException) { }
            assertEquals(1, track.closed)
        }
    }

    @Test fun negativeWriteAndOversizedInputFailWithoutRetainingData() = runTest {
        for ((track, bytes) in listOf(Track(maxWrite = -6) to byteArrayOf(1, 2), Track() to ByteArray(300_000))) {
            val playback = StreamingPcm16Playback({ track }, StandardTestDispatcher(testScheduler))
            try { playback.play(flowOf(bytes)); fail("Expected playback failure") }
            catch (_: SpeechPlaybackException) { }
            assertEquals(1, track.closed)
        }
    }

    @Test fun stopCancelsCollectionAndAReplacementOwnsItsOwnTrack() = runTest {
        val first = Track()
        val second = Track(autoDrain = false)
        val tracks = ArrayDeque(listOf(first, second))
        val playback = StreamingPcm16Playback({ tracks.removeFirst() }, StandardTestDispatcher(testScheduler))
        val old = async { playback.play(flow { emit(byteArrayOf(1, 2)); awaitCancellation() }) }
        runCurrent()
        playback.stop()
        val next = async { playback.play(flowOf(byteArrayOf(3, 4))) }
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(1, first.closed)
        assertEquals(0, second.closed)
        assertFalse(next.isCompleted)
        second.frames = 1
        advanceTimeBy(20)
        next.await()
        assertEquals(1, second.closed)
    }

    @Test fun callerCancellationDuringDrainReleasesTrack() = runTest {
        val track = Track(autoDrain = false)
        val playback = StreamingPcm16Playback({ track }, StandardTestDispatcher(testScheduler))
        val task = async { playback.play(flowOf(byteArrayOf(1, 2))) }
        runCurrent()
        task.cancelAndJoin()
        assertEquals(1, track.closed)
    }

    @Test fun supersedingAWaitingPlayCannotBypassTheRetiredTrackCleanup() = runTest {
        val first = Track()
        val second = Track(autoDrain = false)
        val tracks = ArrayDeque(listOf(first, second))
        val releaseOld = CompletableDeferred<Unit>()
        val playback = StreamingPcm16Playback({ tracks.removeFirst() }, StandardTestDispatcher(testScheduler))
        val old = async { playback.play(flow {
            try { emit(byteArrayOf(1, 2)); awaitCancellation() }
            finally { withContext(NonCancellable) { releaseOld.await() } }
        }) }
        runCurrent()
        val waiting = async { playback.play(flowOf(byteArrayOf(3, 4))) }
        runCurrent()
        val newest = async { playback.play(flowOf(byteArrayOf(5, 6))) }
        runCurrent()
        assertEquals(1, tracks.size)
        releaseOld.complete(Unit)
        runCurrent()
        assertTrue(old.isCancelled)
        assertTrue(waiting.isCancelled)
        assertEquals(1, first.closed)
        assertEquals(listOf<Byte>(5, 6), second.bytes)
        assertEquals(0, second.closed)
        second.frames = 1
        advanceTimeBy(20)
        newest.await()
        assertEquals(1, second.closed)
    }

    private class Track(val maxWrite: Int = Int.MAX_VALUE, val autoDrain: Boolean = true) : Pcm16Track {
        val bytes = mutableListOf<Byte>()
        var frames = 0L
        var closed = 0
        override fun start() = Unit
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(maxWrite, length)
            if (count > 0) bytes += data.copyOfRange(offset, offset + count).toList()
            return count
        }
        override val playedFrames: Long get() = if (autoDrain) bytes.size / 2L else frames
        override fun interrupt() = Unit
        override fun close() { closed++ }
    }
}

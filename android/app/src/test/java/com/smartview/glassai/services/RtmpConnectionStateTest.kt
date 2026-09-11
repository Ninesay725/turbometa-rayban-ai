package com.smartview.glassai.services

import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.smartview.glassai.services.RTMPStreamingService.StreamingState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpConnectionStateTest {
    @Test
    fun currentCallbacksPublishConnectionAndBitrateWithoutExposingUrl() {
        val fixture = Fixture()
        val callback = fixture.begin()

        callback.onConnectionStartedRtmp(SECRET_URL)
        callback.onAuthSuccessRtmp()
        assertSame(StreamingState.Connecting, fixture.state.value)
        callback.onConnectionSuccessRtmp()
        callback.onNewBitrateRtmp(1234)

        assertSame(StreamingState.Streaming, fixture.state.value)
        assertEquals(1, fixture.connectedCalls)
        assertEquals(listOf(1234L), fixture.bitrates)
        assertTrue(fixture.connection.isStreaming)
        assertEquals(0, fixture.stopCalls)
    }

    @Test
    fun activeAttemptRefusesAnotherStart() {
        val fixture = Fixture()
        fixture.begin()
        assertNull(fixture.connection.beginConnection())
        assertSame(StreamingState.Connecting, fixture.state.value)
    }

    @Test
    fun connectionFailurePublishesSafeErrorBeforeStoppingAndKeepsItAfterDisconnect() {
        val fixture = Fixture()
        val callback = fixture.begin()
        fixture.duringStop = callback::onDisconnectRtmp

        callback.onConnectionFailedRtmp("Server rejected $SECRET_URL")

        fixture.assertStoppedWithError("RTMP connection failed")
        assertFalse(fixture.state.value.toString().contains(SECRET_URL))
    }

    @Test
    fun authFailurePublishesErrorBeforeStoppingAndKeepsItAfterDisconnect() {
        val fixture = Fixture()
        val callback = fixture.begin()
        fixture.duringStop = callback::onDisconnectRtmp

        callback.onAuthErrorRtmp()

        fixture.assertStoppedWithError("Authentication failed")
    }

    @Test
    fun authFailureReleasesStartGuardAndNextAttemptCanConnect() {
        val fixture = Fixture()
        val retired = fixture.begin()
        retired.onAuthErrorRtmp()
        assertFalse(fixture.connection.isStreaming)

        val retry = fixture.begin()
        assertSame(StreamingState.Connecting, fixture.state.value)
        invokeEveryCallback(retired)
        assertSame(StreamingState.Connecting, fixture.state.value)
        retry.onConnectionSuccessRtmp()

        assertSame(StreamingState.Streaming, fixture.state.value)
        assertTrue(fixture.connection.isStreaming)
        assertEquals(1, fixture.stopCalls)
        assertEquals(1, fixture.connectedCalls)
        assertTrue(fixture.bitrates.isEmpty())
    }

    @Test
    fun connectionFailureReleasesStartGuardAndNextAttemptCanConnect() {
        val fixture = Fixture()
        fixture.begin().onConnectionFailedRtmp("Server rejected $SECRET_URL")
        assertFalse(fixture.connection.isStreaming)

        fixture.begin().onConnectionSuccessRtmp()

        assertSame(StreamingState.Streaming, fixture.state.value)
        assertTrue(fixture.connection.isStreaming)
        assertEquals(1, fixture.stopCalls)
    }

    @Test
    fun userStopEndsIdleAndEveryRetiredCallbackIsIgnoredWithoutRestart() {
        val fixture = Fixture()
        val retired = fixture.begin()
        retired.onConnectionSuccessRtmp()
        fixture.duringStop = retired::onDisconnectRtmp

        fixture.connection.stop()
        invokeEveryCallback(retired)

        assertSame(StreamingState.Idle, fixture.state.value)
        assertFalse(fixture.connection.isStreaming)
        assertEquals(1, fixture.stopCalls)
        assertEquals(1, fixture.connectedCalls)
        assertTrue(fixture.bitrates.isEmpty())
    }

    @Test
    fun replacedClientCannotMutateOrStopTheNewStreamingAttempt() {
        val fixture = Fixture()
        val retired = fixture.begin()
        fixture.connection.stop()
        fixture.begin().onConnectionSuccessRtmp()

        invokeEveryCallback(retired)

        assertSame(StreamingState.Streaming, fixture.state.value)
        assertTrue(fixture.connection.isStreaming)
        assertEquals(1, fixture.stopCalls)
        assertEquals(1, fixture.connectedCalls)
        assertTrue(fixture.bitrates.isEmpty())
    }

    @Test
    fun repeatedStopPreservesFailureUntilExplicitRetry() {
        val fixture = Fixture()
        fixture.begin().onAuthErrorRtmp()
        val error = fixture.state.value

        fixture.connection.stop()
        assertSame(error, fixture.state.value)
        assertFalse(fixture.connection.isStreaming)

        fixture.begin()
        assertSame(StreamingState.Connecting, fixture.state.value)
    }

    @Test
    fun failureWaitingForStopLockCannotPublishErrorAfterUserStop() {
        val fixture = Fixture()
        val callback = fixture.begin()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val pending = synchronized(fixture.stopLock) {
                val future = executor.submit {
                    entered.countDown()
                    callback.onAuthErrorRtmp()
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.connection.stop()
                future
            }
            pending.get(5, TimeUnit.SECONDS)

            assertSame(StreamingState.Idle, fixture.state.value)
            assertFalse(fixture.connection.isStreaming)
            assertEquals(1, fixture.stopCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun racingConnectionAndAuthFailuresTearDownTheAttemptOnlyOnce() {
        val fixture = Fixture()
        val callback = fixture.begin()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val connectionFailure = executor.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                callback.onConnectionFailedRtmp(SECRET_URL)
            }
            val authFailure = executor.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                callback.onAuthErrorRtmp()
            }
            start.countDown()
            connectionFailure.get(5, TimeUnit.SECONDS)
            authFailure.get(5, TimeUnit.SECONDS)

            assertTrue(fixture.state.value is StreamingState.Error)
            assertSame(fixture.state.value, fixture.stateAtStop)
            assertFalse(fixture.connection.isStreaming)
            assertEquals(1, fixture.stopCalls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun synchronousConnectFailureLeavesOutputLoopGuardClosed() {
        val fixture = Fixture()
        fixture.begin().onConnectionFailedRtmp(SECRET_URL)

        assertFalse(RTMPStreamingService.canStartOutputLoop(fixture.encoder, fixture.client))
        assertFalse(fixture.connection.isStreaming)
        assertTrue(fixture.state.value is StreamingState.Error)
    }

    @Test
    fun unexpectedDisconnectOfCurrentLiveAttemptStillReportsDisconnected() {
        val fixture = Fixture()
        val callback = fixture.begin()
        callback.onConnectionSuccessRtmp()
        callback.onDisconnectRtmp()

        assertSame(StreamingState.Disconnected, fixture.state.value)
        assertEquals(0, fixture.stopCalls)
    }

    private class Fixture : RtmpConnectionState.Listener {
        val stopLock = Any()
        val state = MutableStateFlow<StreamingState>(StreamingState.Idle)
        val connection = RtmpConnectionState(stopLock, state, this)
        var connectedCalls = 0
        val bitrates = mutableListOf<Long>()
        var stopCalls = 0
        var stateAtStop: StreamingState? = null
        var duringStop: () -> Unit = {}
        var encoder: Any? = null
        var client: Any? = null

        fun begin(): ConnectCheckerRtmp {
            val callback = connection.beginConnection()
            assertNotNull("A stopped/failed attempt must allow an explicit retry", callback)
            encoder = Any()
            client = Any()
            return checkNotNull(callback)
        }

        override fun onConnected() {
            connectedCalls++
        }

        override fun onBitrate(bitrate: Long) {
            bitrates += bitrate
        }

        // Only external resources are faked. Generation, start admission, state transitions,
        // Error preservation and stop serialization all run in the production class.
        override fun stopResources() {
            assertTrue(Thread.holdsLock(stopLock))
            assertFalse(connection.isStreaming)
            stopCalls++
            stateAtStop = state.value
            encoder = null
            client = null
            duringStop()
        }

        fun assertStoppedWithError(message: String) {
            assertEquals(StreamingState.Error(message), state.value)
            assertSame(state.value, stateAtStop)
            assertFalse(connection.isStreaming)
            assertEquals(1, stopCalls)
            assertNull(encoder)
            assertNull(client)
        }
    }

    private fun invokeEveryCallback(callback: ConnectCheckerRtmp) {
        callback.onConnectionStartedRtmp(SECRET_URL)
        callback.onConnectionSuccessRtmp()
        callback.onConnectionFailedRtmp(SECRET_URL)
        callback.onNewBitrateRtmp(999)
        callback.onDisconnectRtmp()
        callback.onAuthErrorRtmp()
        callback.onAuthSuccessRtmp()
    }

    private companion object {
        const val SECRET_URL = "rtmp://user:password@example.invalid/live/private-stream-key"
    }
}

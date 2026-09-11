package com.smartview.glassai.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec/socket integration needs Android; [RtmpConnectionStateTest] covers the real callbacks.
 * `canStartOutputLoop` is the pure "should the output loop start" decision extracted
 * from `startStreaming()` for fix-round-1 T1: a synchronous `RtmpClient.connect()` failure (a
 * malformed URL, per rtmp 2.2.6) re-enters `stopStreaming()` before `connect()` returns, which
 * tears the encoder and the client down to null. Starting the output loop anyway spins one IO
 * thread at 100% CPU forever, because a null encoder makes `dequeueOutputBuffer` return -1
 * with no exception, so the loop's own failure guard never trips.
 */
class RTMPStreamingServiceTest {

    @Test
    fun startsOnlyWhenBothEncoderAndClientAreNonNull() {
        assertTrue(RTMPStreamingService.canStartOutputLoop(encoder = Any(), client = Any()))
    }

    @Test
    fun refusesWhenEncoderWasTornDownBySynchronousConnectFailure() {
        assertFalse(RTMPStreamingService.canStartOutputLoop(encoder = null, client = Any()))
    }

    @Test
    fun refusesWhenClientWasTornDownBySynchronousConnectFailure() {
        assertFalse(RTMPStreamingService.canStartOutputLoop(encoder = Any(), client = null))
    }

    @Test
    fun refusesWhenBothWereTornDown() {
        assertFalse(RTMPStreamingService.canStartOutputLoop(encoder = null, client = null))
    }
}

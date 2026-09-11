package com.smartview.glassai.services

import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.smartview.glassai.services.RTMPStreamingService.StreamingState
import kotlinx.coroutines.flow.MutableStateFlow

/** The service's real RTMP callbacks; only codec/socket teardown and statistics are injected. */
internal class RtmpConnectionState(
    private val stopLock: Any,
    private val state: MutableStateFlow<StreamingState>,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onBitrate(bitrate: Long)
        fun stopResources()
    }

    private var generation = 0L

    @Volatile
    var isStreaming = false
        private set

    fun beginConnection(): ConnectCheckerRtmp? = synchronized(stopLock) {
        if (isStreaming) return@synchronized null
        val currentGeneration = ++generation
        isStreaming = true // Before connect(), whose failure callback can be synchronous.
        state.value = StreamingState.Connecting

        object : ConnectCheckerRtmp {
            // Neither callback carries a state transition. Do not retain/log the URL.
            override fun onConnectionStartedRtmp(rtmpUrl: String) = Unit
            override fun onAuthSuccessRtmp() = Unit

            override fun onConnectionSuccessRtmp() = whenCurrent {
                state.value = StreamingState.Streaming
                listener.onConnected()
            }

            // Server reasons can contain the full URL, credentials or stream key.
            override fun onConnectionFailedRtmp(reason: String) = fail("RTMP connection failed")

            override fun onAuthErrorRtmp() = fail("Authentication failed")

            override fun onNewBitrateRtmp(bitrate: Long) = whenCurrent {
                listener.onBitrate(bitrate)
            }

            override fun onDisconnectRtmp() = whenCurrent {
                if (isStreaming) state.value = StreamingState.Disconnected
            }

            private fun fail(message: String) = whenCurrent {
                state.value = StreamingState.Error(message)
                stop()
            }

            private fun whenCurrent(action: () -> Unit) = synchronized(stopLock) {
                // The generation check and mutation share the stop lock, so a callback waiting
                // behind user Stop cannot pass an earlier check and then overwrite Idle.
                if (currentGeneration == generation) action()
            }
        }
    }

    fun stop() = synchronized(stopLock) {
        isStreaming = false
        ++generation // Retire before teardown can cause another callback.
        // Production takes encoderLock here, preserving stopLock -> encoderLock. Socket
        // disconnect is queued by the listener and runs outside both locks.
        listener.stopResources()
        // StateFlow conflates: keep Error visible until the next explicit start.
        if (state.value !is StreamingState.Error) state.value = StreamingState.Idle
    }
}

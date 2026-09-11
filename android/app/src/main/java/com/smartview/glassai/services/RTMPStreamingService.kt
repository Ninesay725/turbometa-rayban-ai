package com.smartview.glassai.services

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.smartview.glassai.utils.RtmpUrlSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * RTMPStreamingService - Streams video from Ray-Ban Meta glasses to RTMP server
 *
 * This service takes raw I420 (YUV420P) frames from the DAT SDK,
 * encodes them to H.264 using MediaCodec, and streams via RTMP.
 *
 * Frame flow:
 * DAT SDK (I420) -> H.264 Encoder (MediaCodec) -> RTMP Client -> Server
 */
class RTMPStreamingService(private val context: Context) {

    companion object {
        private const val TAG = "RTMPStreamingService"

        // Default encoding parameters
        private const val DEFAULT_BITRATE = 2_000_000 // 2 Mbps
        private const val DEFAULT_FPS = 24
        private const val I_FRAME_INTERVAL = 1 // I-frame every 1 second for faster recovery

        // MIME type for H.264
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Pure "should the output loop start" decision, pulled out of [startStreaming] so it can
         * be unit-tested on the JVM (the surrounding code needs MediaCodec/RtmpClient, which
         * require an Android runtime). `RtmpClient.connect()` (rtmp 2.2.6) can call
         * `onConnectionFailedRtmp()` SYNCHRONOUSLY on a malformed URL, which re-enters
         * stopStreaming() and tears both down before connect() even returns; starting the loop
         * on a null encoder spins one IO thread at 100% CPU forever (dequeueOutputBuffer returns
         * -1 with no exception, so the loop's own failure guard never trips). `Any?` keeps this
         * free of Android/rtmp types so a plain test double can stand in for either argument.
         */
        @VisibleForTesting
        internal fun canStartOutputLoop(encoder: Any?, client: Any?): Boolean =
            encoder != null && client != null
    }

    // Streaming states
    sealed class StreamingState {
        object Idle : StreamingState()
        object Connecting : StreamingState()
        object Streaming : StreamingState()
        data class Error(val message: String) : StreamingState()
        object Disconnected : StreamingState()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    val state: StateFlow<StreamingState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(StreamingStats())
    val stats: StateFlow<StreamingStats> = _stats.asStateFlow()

    // RTMP client
    private var rtmpClient: RtmpClient? = null

    // H.264 encoder.
    // encoderLock serialises every MediaCodec call (feedFrame from the frame worker, the
    // encoderJob output loop) against the stop()/release() teardown in stopStreaming(), which runs
    // on whichever thread stops the stream. Without it release() can land inside a codec call.
    // encoder/isStreaming are @Volatile so the worker sees the teardown's writes immediately.
    // Fair, because the output loop below re-takes the lock immediately after releasing it: an
    // unfair monitor would let it barge ahead of a waiting feedFrame() or stopStreaming().
    private val encoderLock = ReentrantLock(true)

    // Serializes stopStreaming() — user Stop on Main, onConnectionFailedRtmp() on the RTMP thread,
    // the encoder loop on IO, release() — so the client is disconnected exactly once (ledger T6:
    // double disconnect).
    //
    // Lock order: stopLock -> encoderLock, never the other way round. stopStreaming() is the only
    // place that holds both, and nothing that runs under encoderLock (feedFrame, the output loop)
    // ever takes stopLock: the output loop's failure branch calls stopStreaming() *after* leaving
    // the encoderLock section, and the disconnect itself is handed to disconnectExecutor rather
    // than performed while holding either lock.
    private val stopLock = Any()

    // RtmpClient.disconnect() does socket I/O and must not run on the caller's (often Main)
    // thread (ledger T6). One thread keeps disconnects ordered; release() drains and stops it.
    private val disconnectExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rtmp-disconnect") }

    // Bumped once per RtmpClient created in startStreaming(). Each ConnectCheckerRtmp captures the
    // generation it was built for and compares against this on every callback (fix-round-1 T2):
    // a callback that fires after its client has been replaced or retired (a late async callback
    // racing a subsequent start/stop) sees a mismatch and returns instead of mutating state on
    // behalf of a client nobody holds a reference to anymore.
    private val connectionGeneration = AtomicLong(0)

    @Volatile
    private var encoder: MediaCodec? = null
    private var encoderInputBuffers: Array<ByteBuffer>? = null
    private var encoderJob: Job? = null

    // Video parameters
    private var videoWidth = 0
    private var videoHeight = 0
    @Volatile
    private var isStreaming = false

    // SPS/PPS for H.264 stream initialization
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // Frame statistics
    private var frameCount = 0L
    private var startTime = 0L

    data class StreamingStats(
        val framesSent: Long = 0,
        val bitrate: Long = 0,
        val fps: Double = 0.0,
        val connectionTime: Long = 0
    )

    /**
     * Connect to RTMP server and start streaming
     * @param rtmpUrl Full RTMP URL (e.g., rtmp://server.com/live/streamkey)
     * @param width Video width from DAT SDK
     * @param height Video height from DAT SDK
     * @param bitrate Target bitrate in bps (default 2Mbps)
     */
    suspend fun startStreaming(
        rtmpUrl: String,
        width: Int,
        height: Int,
        bitrate: Int = DEFAULT_BITRATE
    ): Boolean = withContext(Dispatchers.IO) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return@withContext false
        }

        try {
            // Server portion only: the stream key is a secret (encrypted at rest since 2.0) and
            // must never reach logcat.
            Log.d(TAG, "Starting RTMP streaming to: ${RtmpUrlSplitter.split(rtmpUrl).first}")
            Log.d(TAG, "Video: ${width}x${height} @ $bitrate bps")

            _state.value = StreamingState.Connecting

            videoWidth = width
            videoHeight = height

            // Initialize H.264 encoder
            if (!initEncoder(width, height, bitrate)) {
                _state.value = StreamingState.Error("Failed to initialize encoder")
                return@withContext false
            }

            // Fresh generation for this client/checker pair (fix-round-1 T2 — see
            // connectionGeneration's doc comment).
            val myGeneration = connectionGeneration.incrementAndGet()

            // Initialize RTMP client
            rtmpClient = RtmpClient(object : ConnectCheckerRtmp {
                override fun onConnectionStartedRtmp(rtmpUrl: String) {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.d(TAG, "RTMP connection started: ${RtmpUrlSplitter.split(rtmpUrl).first}")
                }

                override fun onConnectionSuccessRtmp() {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.d(TAG, "RTMP connected successfully")
                    _state.value = StreamingState.Streaming
                    startTime = System.currentTimeMillis()
                }

                override fun onConnectionFailedRtmp(reason: String) {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.e(TAG, "RTMP connection failed: $reason")
                    _state.value = StreamingState.Error(reason)
                    stopStreaming()
                }

                override fun onNewBitrateRtmp(bitrate: Long) {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.d(TAG, "RTMP bitrate: $bitrate")
                    updateStats(bitrate = bitrate)
                }

                override fun onDisconnectRtmp() {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.d(TAG, "RTMP disconnected")
                    // Invoked synchronously by RtmpClient.disconnect(), i.e. from inside
                    // stopStreaming() after a user Stop or after onConnectionFailedRtmp() set
                    // Error. isStreaming is cleared as the first statement of stopStreaming(), so
                    // only an unexpected server-side drop of a live stream reaches Disconnected;
                    // a user stop ends in Idle and a connection failure keeps its Error.
                    if (isStreaming) _state.value = StreamingState.Disconnected
                }

                override fun onAuthErrorRtmp() {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.e(TAG, "RTMP auth error")
                    _state.value = StreamingState.Error("Authentication failed")
                }

                override fun onAuthSuccessRtmp() {
                    if (myGeneration != connectionGeneration.get()) return
                    Log.d(TAG, "RTMP auth success")
                }
            })

            // isStreaming must flip true BEFORE connect(): RtmpClient.connect() (rtmp 2.2.6) calls
            // onConnectionFailedRtmp() SYNCHRONOUSLY when the URL is malformed, which re-enters
            // stopStreaming() and tears the encoder/client down before connect() even returns
            // (fix-round-1 T1). Setting it here lets that nested stopStreaming() observe
            // isStreaming == true and correctly flip it back to false as its first statement.
            isStreaming = true
            frameCount = 0

            // Connect to RTMP server
            rtmpClient?.connect(rtmpUrl)

            // A synchronous connect() failure already tore everything down via the nested
            // stopStreaming() above: encoder released/nulled, rtmpClient nulled, isStreaming
            // cleared, and the Error state preserved. Do not start the output loop in that case
            // (it would spin one IO thread at 100% CPU on a null encoder) and do not touch _state
            // — onConnectionFailedRtmp already published the Error.
            if (!canStartOutputLoop(encoder, rtmpClient)) {
                isStreaming = false
                return@withContext false
            }

            // Start encoder output processing
            startEncoderOutputProcessing()

            Log.d(TAG, "RTMP streaming started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming: ${e.message}", e)
            _state.value = StreamingState.Error(e.message ?: "Unknown error")
            stopStreaming()
            false
        }
    }

    /**
     * Initialize H.264 encoder using MediaCodec
     */
    private fun initEncoder(width: Int, height: Int, bitrate: Int): Boolean {
        var codec: MediaCodec? = null
        try {
            // Find encoder for H.264
            codec = MediaCodec.createEncoderByType(MIME_TYPE)

            // Use YUV420Planar (I420) format to match DAT SDK output
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                )
                // Lower latency encoding
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec

            Log.d(TAG, "H.264 encoder initialized: ${width}x${height} (I420/YUV420Planar)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder: ${e.message}", e)
            // configure()/start() threw: release the codec instead of leaking it (ledger T6)
            runCatching { codec?.release() }
            encoder = null
            return false
        }
    }

    /**
     * Process encoder output and send to RTMP
     */
    private fun startEncoderOutputProcessing() {
        encoderJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()
            var failure: Exception? = null

            while (isStreaming && failure == null) {
                encoderLock.withLock {
                    try {
                        val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1

                        when {
                            outputIndex >= 0 -> {
                                val outputBuffer = encoder?.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    // Check for codec config (SPS/PPS)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        extractSpsPps(outputBuffer, bufferInfo.size)
                                    } else {
                                        // Send H.264 data to RTMP
                                        sendH264Data(outputBuffer, bufferInfo)
                                    }
                                }
                                encoder?.releaseOutputBuffer(outputIndex, false)
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.d(TAG, "Encoder output format changed: ${encoder?.outputFormat}")
                            }
                        }
                    } catch (e: Exception) {
                        // A codec in the error state throws on every dequeue: leave the loop
                        // instead of spinning on it (ledger T6). Exceptions during teardown
                        // (isStreaming already false) are expected and ignored.
                        if (isStreaming) failure = e
                    }
                }
            }

            failure?.let { e ->
                Log.e(TAG, "Encoder output error: ${e.message}", e)
                // A concurrent user Stop may already have flipped isStreaming to false and moved
                // the state to Idle between the catch above and here (fix-round-1 T3): don't
                // follow a normal Stop with an error card. stopStreaming() below still runs either
                // way — it's idempotent/safe if a Stop already completed it.
                if (isStreaming) {
                    _state.value = StreamingState.Error("Encoder failed: ${e.message ?: e.javaClass.simpleName}")
                }
                stopStreaming() // keeps the Error set just above (see the guard at its end)
            }
        }
    }

    /**
     * Extract SPS and PPS from codec config
     */
    private fun extractSpsPps(buffer: ByteBuffer, size: Int) {
        val data = ByteArray(size)
        buffer.get(data)
        buffer.rewind()

        // Parse SPS and PPS from AnnexB format
        // Format: 00 00 00 01 [SPS] 00 00 00 01 [PPS]
        var spsStart = -1
        var spsEnd = -1
        var ppsStart = -1

        for (i in 0 until size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                if (spsStart == -1) {
                    spsStart = i + 4
                } else if (spsEnd == -1) {
                    spsEnd = i
                    ppsStart = i + 4
                }
            }
        }

        if (spsStart >= 0 && spsEnd > spsStart && ppsStart >= 0) {
            sps = data.copyOfRange(spsStart, spsEnd)
            pps = data.copyOfRange(ppsStart, size)
            Log.d(TAG, "SPS/PPS extracted: SPS=${sps?.size} bytes, PPS=${pps?.size} bytes")

            // Send SPS/PPS to RTMP client
            val localSps = sps
            val localPps = pps
            if (localSps != null && localPps != null) {
                rtmpClient?.setVideoInfo(ByteBuffer.wrap(localSps), ByteBuffer.wrap(localPps), null)
            }
        }
    }

    /**
     * Send H.264 encoded data to RTMP server
     */
    private fun sendH264Data(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val data = ByteArray(bufferInfo.size)
        buffer.get(data)

        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val timestamp = bufferInfo.presentationTimeUs / 1000 // Convert to milliseconds

        // Send to RTMP
        rtmpClient?.sendVideo(ByteBuffer.wrap(data), bufferInfo)

        frameCount++
        updateStats(framesSent = frameCount)
    }

    // Frame tracking
    private var totalFrames = 0L
    private var droppedFrames = 0L
    private var lastLogTime = 0L

    // Timestamp smoothing for consistent frame timing
    private var baseTimestampUs = 0L
    private var frameIndex = 0L
    private val targetFrameDurationUs = 1_000_000L / DEFAULT_FPS // ~41666 us for 24fps

    /**
     * Feed a raw I420 frame from ByteBuffer (direct from DAT SDK VideoFrame)
     * Directly passes I420 data to encoder configured with COLOR_FormatYUV420Planar
     */
    fun feedFrame(buffer: ByteBuffer, width: Int, height: Int, timestampUs: Long) {
        encoderLock.withLock {
            if (!isStreaming || encoder == null) return

            totalFrames++

            try {
                // Use longer timeout to reduce frame drops
                val inputIndex = encoder?.dequeueInputBuffer(10000) ?: -1
                if (inputIndex >= 0) {
                    val inputBuffer = encoder?.getInputBuffer(inputIndex)
                    inputBuffer?.clear()

                    // Make a defensive copy to avoid race conditions
                    val position = buffer.position()
                    val dataSize = buffer.remaining()

                    // Validate frame size (I420 = width * height * 1.5)
                    val expectedSize = width * height * 3 / 2
                    if (dataSize != expectedSize) {
                        Log.w(TAG, "Frame size mismatch! Expected: $expectedSize, Got: $dataSize")
                    }

                    // Create a local copy of the data
                    val frameCopy = ByteArray(dataSize)
                    buffer.get(frameCopy)
                    buffer.position(position) // Restore position

                    // Put the copied data into encoder
                    inputBuffer?.put(frameCopy)

                    // Use smoothed timestamp for consistent frame rate
                    // This prevents timing jitter from causing decoder issues
                    if (baseTimestampUs == 0L) {
                        baseTimestampUs = timestampUs
                    }
                    val smoothedTimestamp = baseTimestampUs + (frameIndex * targetFrameDurationUs)
                    frameIndex++

                    encoder?.queueInputBuffer(inputIndex, 0, dataSize, smoothedTimestamp, 0)
                } else {
                    droppedFrames++
                    Log.w(TAG, "Dropped frame - encoder queue full (total dropped: $droppedFrames)")
                }

                // Log stats every 5 seconds
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 5000) {
                    Log.d(TAG, "Frame stats: total=$totalFrames, dropped=$droppedFrames, drop rate=${droppedFrames * 100 / maxOf(totalFrames, 1)}%")
                    lastLogTime = now
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error feeding frame from buffer: ${e.message}", e)
            }
        }
    }

    /**
     * Stop streaming and release resources. Safe to call from any thread and any number of times.
     */
    fun stopStreaming() {
        synchronized(stopLock) {
            Log.d(TAG, "Stopping RTMP streaming")
            isStreaming = false

            // Stop encoder processing
            encoderJob?.cancel()
            encoderJob = null

            // Stop and release encoder under encoderLock, so release() can never run while the frame
            // worker or the output loop is inside a codec call on the same encoder.
            encoderLock.withLock {
                try {
                    encoder?.stop()
                    encoder?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping encoder: ${e.message}")
                }
                encoder = null
            }

            // Swap first so a second stopStreaming() sees null: exactly one disconnect per client.
            // The disconnect itself (socket I/O, and it invokes onDisconnectRtmp synchronously)
            // runs on the rtmp-disconnect thread, never on Main.
            val client = rtmpClient
            rtmpClient = null
            if (client != null) {
                try {
                    disconnectExecutor.execute {
                        try {
                            client.disconnect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error disconnecting RTMP: ${e.message}")
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    // release() already shut the executor down: last resort, disconnect inline
                    runCatching { client.disconnect() }
                }
            }

            // Clear SPS/PPS
            sps = null
            pps = null

            // Reset frame counters and timestamp smoothing
            totalFrames = 0
            droppedFrames = 0
            lastLogTime = 0
            baseTimestampUs = 0
            frameIndex = 0

            // Keep a connection/auth/encoder failure visible: those paths set Error and then call
            // stopStreaming(), and StateFlow conflates, so overwriting it here made the Main
            // collector see only Idle. The next startStreaming() moves the state on to Connecting.
            if (_state.value !is StreamingState.Error) {
                _state.value = StreamingState.Idle
            }
            Log.d(TAG, "RTMP streaming stopped")
        }
    }

    private fun updateStats(framesSent: Long? = null, bitrate: Long? = null) {
        val current = _stats.value
        val elapsed = if (startTime > 0) System.currentTimeMillis() - startTime else 0
        val fps = if (elapsed > 0) (framesSent ?: current.framesSent) * 1000.0 / elapsed else 0.0

        _stats.value = StreamingStats(
            framesSent = framesSent ?: current.framesSent,
            bitrate = bitrate ?: current.bitrate,
            fps = fps,
            connectionTime = elapsed
        )
    }

    /**
     * Check if currently streaming
     */
    fun isStreaming(): Boolean = isStreaming

    /**
     * Release all resources
     */
    fun release() {
        stopStreaming()
        disconnectExecutor.shutdown() // a queued disconnect still runs; nothing new is accepted
        scope.cancel()
    }
}

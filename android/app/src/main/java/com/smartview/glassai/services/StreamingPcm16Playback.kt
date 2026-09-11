package com.smartview.glassai.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal class SpeechPlaybackException(message: String) : IOException(message)

/** Nonblocking device boundary; JVM fixtures exercise the consumer without invoking Android. */
internal interface Pcm16Track {
    fun start()
    fun write(data: ByteArray, offset: Int, length: Int): Int
    val playedFrames: Long
    fun interrupt()
    fun close()
}

internal class StreamingPcm16Playback(
    private val factory: () -> Pcm16Track,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writeTimeoutMs: Long = 5_000,
    private val drainTimeoutMs: Long = 5_000,
) : Pcm16Playback {
    private class Run(val job: Job) { var track: Pcm16Track? = null }
    private val lock = Any()
    private val execution = Mutex()
    private var active: Run? = null

    override suspend fun play(chunks: Flow<ByteArray>): Unit = coroutineScope {
        ensureActive()
        val run = Run(coroutineContext.job)
        synchronized(lock) {
            active?.let { it.job.cancel(); it.track?.interrupt() }
            active = run
        }
        try {
            execution.withLock {
                withContext(dispatcher) {
                    ensureActive()
                    val track = factory()
                    try {
                        synchronized(lock) {
                            ensureActive()
                            run.track = track
                            track.start()
                        }
                        var pending: Byte? = null
                        var acceptedBytes = 0L
                        suspend fun write(data: ByteArray, offset: Int, size: Int) {
                            var written = 0
                            while (written < size) {
                                currentCoroutineContext().ensureActive()
                                val count = withTimeoutOrNull(writeTimeoutMs) {
                                    var result = track.write(data, offset + written, size - written)
                                    while (result == 0) {
                                        delay(5)
                                        result = track.write(data, offset + written, size - written)
                                    }
                                    result
                                } ?: throw SpeechPlaybackException("PCM playback write timed out")
                                if (count < 0 || count > size - written) throw SpeechPlaybackException("PCM playback write failed")
                                written += count
                                acceptedBytes += count
                            }
                        }
                        chunks.collect { data ->
                            currentCoroutineContext().ensureActive()
                            if (data.size > 262_144) throw SpeechPlaybackException("PCM chunk exceeds the playback bound")
                            if (data.isNotEmpty()) {
                                var offset = 0
                                pending?.let {
                                    write(byteArrayOf(it, data[0]), 0, 2)
                                    pending = null
                                    offset = 1
                                }
                                val even = (data.size - offset) / 2 * 2
                                if (even > 0) write(data, offset, even)
                                if (offset + even < data.size) pending = data.last()
                            }
                        }
                        if (pending != null) throw SpeechPlaybackException("Incomplete PCM16 sample")
                        val drained = withTimeoutOrNull(drainTimeoutMs) {
                            while (track.playedFrames < acceptedBytes / 2) delay(10)
                            true
                        } ?: false
                        if (!drained) throw SpeechPlaybackException("PCM playback drain timed out")
                    } finally {
                        synchronized(lock) { run.track = null }
                        track.close()
                    }
                }
            }
        } finally {
            synchronized(lock) { if (active === run) active = null }
        }
    }

    override fun stop() {
        synchronized(lock) { active?.let { it.job.cancel(); it.track?.interrupt() } }
    }
}

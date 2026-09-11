package com.smartview.glassai.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.Flow

/** One instance per feature. PCM16 little-endian mono; returns after bounded audible drain. */
interface Pcm16Playback {
    suspend fun play(chunks: Flow<ByteArray>)
    fun stop()
}

class AudioTrackPcm16Playback(sampleRate: Int = 24_000) : Pcm16Playback {
    init { require(sampleRate > 0) }
    private val playback = StreamingPcm16Playback({ AndroidPcm16Track(sampleRate) })
    override suspend fun play(chunks: Flow<ByteArray>) = playback.play(chunks)
    override fun stop() = playback.stop()
}

private class AndroidPcm16Track(sampleRate: Int) : Pcm16Track {
    private val lock = Any()
    private var closed = false
    private var previousHead = 0L
    private var wraps = 0L
    private val track: AudioTrack

    init {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw SpeechPlaybackException("PCM playback is unavailable")
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate / 5) / 2 * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw SpeechPlaybackException("PCM playback could not initialize")
        }
    }

    override fun start() = synchronized(lock) {
        if (closed) throw SpeechPlaybackException("PCM playback is closed")
        // API 31+: short status phrases must play even when they never fill the device buffer.
        track.setStartThresholdInFrames(1)
        track.play()
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (closed) throw SpeechPlaybackException("PCM playback is closed")
        track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)
    }

    override val playedFrames: Long get() = synchronized(lock) {
        if (closed) throw SpeechPlaybackException("PCM playback is closed")
        val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (head < previousHead) wraps += 1L shl 32
        previousHead = head
        wraps + head
    }

    override fun interrupt() = synchronized(lock) {
        if (!closed) {
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            interrupt()
            closed = true
            track.release()
        }
    }
}

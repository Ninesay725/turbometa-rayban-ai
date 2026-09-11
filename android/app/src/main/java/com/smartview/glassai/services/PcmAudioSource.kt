package com.smartview.glassai.services

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 16 kHz mono PCM16 capture seam so FunASRService can be tested without AudioRecord. */
interface PcmAudioSource {
    /** Starts delivering little-endian PCM16 chunks on a background thread; false if it could not start. */
    fun start(onChunk: (ByteArray) -> Unit): Boolean
    fun stop()
}

/**
 * AudioRecord at 16 kHz / mono / PCM16 from [audioSource] (MediaRecorder.AudioSource.MIC for the
 * phone, VOICE_COMMUNICATION after BluetoothAudioManager started SCO for the glasses).
 * RECORD_AUDIO must already be granted (the chat screen requests it before listening).
 */
class AudioRecordPcmSource(private val audioSource: Int) : PcmAudioSource {
    companion object {
        private const val TAG = "AudioRecordPcmSource"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null

    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (record != null) return true
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val bufferSize = maxOf(minBuffer, 3200) // >= 100 ms of 16 kHz PCM16
            val audioRecord = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 2)
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord.release()
                return false
            }
            audioRecord.startRecording()
            record = audioRecord
            job = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) onChunk(buffer.copyOf(read))
                }
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            false
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        record?.let { audioRecord ->
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }
        record = null
    }
}

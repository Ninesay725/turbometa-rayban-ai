package com.smartview.glassai.services

import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.managers.BluetoothAudioManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The mic seam on a device: 16 kHz mono PCM16 chunks arrive and stop on stop(). */
@RunWith(AndroidJUnit4::class)
class AudioRecordPcmSourceInstrumentedTest {

    @Before
    fun grantMic() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO").close()
        }
    }

    @Test
    fun phoneMicDeliversPcm16ChunksUntilStopped() {
        val source = AudioRecordPcmSource(MediaRecorder.AudioSource.MIC)
        val chunks = LinkedBlockingQueue<ByteArray>()

        assertTrue("AudioRecord did not start (RECORD_AUDIO granted? emulator mic enabled?)", source.start { chunks.add(it) })
        val first = chunks.poll(5, TimeUnit.SECONDS)
        assertNotNull("no PCM within 5 s", first)
        assertTrue(first!!.isNotEmpty())
        assertEquals(0, first.size % 2) // 16-bit samples

        source.stop()
        chunks.clear()
        Thread.sleep(300)
        assertNull(chunks.poll()) // nothing after stop()
    }

    @Test
    fun voiceCommunicationSourceStartsWithoutSco() {
        // BLUETOOTH_MIC maps to VOICE_COMMUNICATION; without an SCO link Android routes it to the
        // phone mic, which is the Live AI fallback the chat screen relies on.
        val source = AudioRecordPcmSource(FunASRService.recorderSourceFor(BluetoothAudioManager.AudioSource.BLUETOOTH_MIC))
        val chunks = LinkedBlockingQueue<ByteArray>()
        assertTrue(source.start { chunks.add(it) })
        assertNotNull(chunks.poll(5, TimeUnit.SECONDS))
        source.stop()
    }
}

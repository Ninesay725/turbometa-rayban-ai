package com.smartview.glassai.services

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Real platform backends. PCM fixtures are silence; no cloud, permissions, or persisted settings. */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackInstrumentedTest {
    @Test fun audioTrackDrainsSilentPcmAndCanPlayAgain() = runBlocking {
        val playback = AudioTrackPcm16Playback()
        try {
            withTimeout(10_000) {
                playback.play(flowOf(ByteArray(1), ByteArray(4_799)))
                playback.play(flowOf(ByteArray(2))) // A short utterance must not wait for a full device buffer.
            }
        } finally { playback.stop() }
    }

    @Test fun audioTrackStopCancelsTheStreamAndReleasesForANewPlay() = runBlocking {
        val playback = AudioTrackPcm16Playback()
        val consuming = CompletableDeferred<Unit>()
        val task = async {
            playback.play(flow { emit(ByteArray(2_400)); consuming.complete(Unit); awaitCancellation() })
        }
        try {
            withTimeout(10_000) { consuming.await() }
            playback.stop()
            withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            withTimeout(10_000) { playback.play(flowOf(ByteArray(2_400))) }
        } finally { task.cancel(); playback.stop() }
    }

    @Test fun installedSystemEngineCompletesAnUtteranceAndCloseRejectsAnother() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val ready = CompletableDeferred<Int>()
        val probe = withContext(Dispatchers.Main) { TextToSpeech(context) { ready.complete(it) } }
        try {
            assumeTrue("No system TTS engine initialized", withTimeoutOrNull(10_000) { ready.await() } == TextToSpeech.SUCCESS)
            assumeTrue("English voice data is unavailable", withContext(Dispatchers.Main) {
                probe.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            })
        } finally { withContext(Dispatchers.Main) { probe.shutdown() } }
        val speech = AndroidSystemSpeech(context)
        try {
            val completed = withTimeout(65_000) { speech.speak("Speech fixture.", "en-US") }
            assertTrue("A usable system engine must report utterance completion", completed)
            speech.close()
            assertFalse(speech.speak("Must not be spoken.", "en-US"))
        } finally { speech.close() }
    }

    @Test fun systemSpeechCancellationDuringInitializationOrPlaybackCannotRestartAfterClose() = runBlocking {
        val speech = AndroidSystemSpeech(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
        val task = async(start = CoroutineStart.UNDISPATCHED) { speech.speak("Cancellation fixture.", "en-US") }
        speech.close()
        withTimeout(5_000) { task.join() }
        assertTrue(task.isCancelled)
        assertFalse(speech.speak("Must not be spoken.", "en-US"))
    }
}

package com.smartview.glassai.services

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import com.smartview.glassai.managers.APIProvider
import com.smartview.glassai.managers.AlibabaEndpoint
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TTSServiceTest {
    @Test fun providerAndRegionChooseOnlyTheirOwnNativeHttpEndpoint() {
        assertNull(cloudSpeechConfig(APIProvider.OPENROUTER, AlibabaEndpoint.BEIJING, "fixture"))
        assertNull(cloudSpeechConfig(APIProvider.ALIBABA, AlibabaEndpoint.BEIJING, " "))
        val beijing = cloudSpeechConfig(APIProvider.ALIBABA, AlibabaEndpoint.BEIJING, "beijing-fixture")!!
        val singapore = cloudSpeechConfig(APIProvider.ALIBABA, AlibabaEndpoint.SINGAPORE, "singapore-fixture")!!
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", beijing.url)
        assertEquals("https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", singapore.url)
        assertEquals("beijing-fixture", beijing.apiKey)
        assertEquals("singapore-fixture", singapore.apiKey)
    }

    @Test fun settingsAreSnapshottedOnceAndLongTextRequestsStayOrdered() = runTest {
        var reads = 0
        val config = CloudSpeechConfig("https://example.invalid/beijing", "fixture")
        val calls = mutableListOf<String>()
        val system = System()
        val service = TTSService({ reads++; config }, CloudSpeech { text, language, settings ->
            assertSame(config, settings)
            assertEquals("Japanese", language.languageType)
            calls += text
            flowOf(byteArrayOf(1, 2))
        }, system, Playback(), StandardTestDispatcher(testScheduler))
        val text = "x".repeat(1_300)
        assertTrue(service.speak(text, "ja-JP"))
        assertEquals(text, calls.joinToString(""))
        assertTrue(calls.all { it.length <= 600 })
        assertEquals(1, reads)
        assertTrue(system.texts.isEmpty())
    }

    @Test fun missingCloudConfigurationUsesSystemAndReturnsItsCompletionResult() = runTest {
        val system = System()
        val service = TTSService({ null }, CloudSpeech { _, _, _ -> error("Cloud must not be called") },
            system, Playback(), StandardTestDispatcher(testScheduler))
        assertTrue(service.speak("Hello", "en-US"))
        system.result = false
        assertFalse(service.speak("Unavailable", "en-US"))
        assertEquals(listOf("Hello", "Unavailable"), system.texts)
    }

    @Test fun cloudFailureFallsBackFromTheFailedChunkAndKeepsRemainingText() = runTest {
        val system = System()
        val texts = mutableListOf<String>()
        val service = TTSService({ config }, CloudSpeech { text, _, _ ->
            texts += text
            if (texts.size == 1) flowOf(byteArrayOf(1, 2)) else flow { throw SpeechProtocolException("Synthetic failure") }
        }, system, Playback(), StandardTestDispatcher(testScheduler))
        val text = "x".repeat(1_300)
        assertTrue(service.speak(text, "en"))
        assertEquals(2, texts.size)
        assertEquals("x".repeat(700), system.texts.joinToString(""))
    }

    @Test fun callerCancellationAbortsCloudWithoutSystemFallback() = runTest {
        var closed = false
        val system = System()
        val service = TTSService({ config }, CloudSpeech { _, _, _ -> flow {
            try { awaitCancellation() } finally { closed = true }
        } }, system, Playback(), StandardTestDispatcher(testScheduler))
        val task = async { service.speak("Hello", "en") }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(closed)
        assertTrue(system.texts.isEmpty())
    }

    @Test fun latestUtteranceWaitsForRetiredCleanupAndOldFinallyCannotStopIt() = runTest {
        val retiring = CompletableDeferred<Unit>()
        val finishOld = CompletableDeferred<Unit>()
        val finishNew = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val system = System()
        val service = TTSService({ config }, CloudSpeech { text, _, _ -> flow {
            started += text
            if (text == "old") {
                try { awaitCancellation() } finally {
                    retiring.complete(Unit)
                    withContext(NonCancellable) { finishOld.await() }
                }
            } else { emit(byteArrayOf(1, 2)); finishNew.await() }
        } }, system, Playback(), StandardTestDispatcher(testScheduler))
        val old = async { service.speak("old", "en") }
        runCurrent()
        val waiting = async { service.speak("waiting", "en") }
        runCurrent()
        val newest = async { service.speak("newest", "en") }
        runCurrent()
        assertTrue(retiring.isCompleted)
        assertEquals(listOf("old"), started)
        finishOld.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "newest"), started)
        assertTrue(old.isCancelled)
        assertTrue(waiting.isCancelled)
        assertFalse(newest.isCompleted)
        finishNew.complete(Unit)
        assertTrue(newest.await())
    }

    @Test fun stopAndCloseCancelOwnedSpeechAndCloseRejectsFutureRequests() = runTest {
        val system = System().apply { waitForever = true }
        val service = TTSService({ null }, CloudSpeech { _, _, _ -> error("Unexpected cloud") },
            system, Playback(), StandardTestDispatcher(testScheduler))
        val first = async { service.speak("first", "en") }
        runCurrent()
        service.stop()
        runCurrent()
        assertTrue(first.isCancelled)
        val second = async { service.speak("second", "en") }
        runCurrent()
        service.close()
        runCurrent()
        assertTrue(second.isCancelled)
        assertEquals(1, system.closes)
        assertFalse(service.speak("late", "en"))
    }

    @Test fun totalSpeechDeadlineReturnsFalseWithoutCancellationFallback() = runTest {
        val system = System()
        val service = TTSService({ config }, CloudSpeech { _, _, _ -> flow { awaitCancellation() } },
            system, Playback(), StandardTestDispatcher(testScheduler), 50)
        assertFalse(service.speak("timeout", "en"))
        assertTrue(system.texts.isEmpty())
    }

    @Test fun systemFallbackWithoutCompletionAlsoHasATotalDeadline() = runTest {
        val system = System().apply { waitForever = true }
        val service = TTSService({ null }, CloudSpeech { _, _, _ -> error("Unexpected cloud") },
            system, Playback(), StandardTestDispatcher(testScheduler), 50)
        assertFalse(service.speak("timeout", "en"))
        assertEquals(listOf("timeout"), system.texts)
        service.close()
        assertEquals(1, system.closes)
    }

    @Test fun playbackDrainMustFinishBeforeSpeakReturnsTrueAndBlankTextDoesNotStartBackend() = runTest {
        val drained = CompletableDeferred<Unit>()
        val playback = object : Pcm16Playback {
            override suspend fun play(chunks: Flow<ByteArray>) { chunks.collect(); drained.await() }
            override fun stop() = Unit
        }
        val system = System()
        val service = TTSService({ config }, CloudSpeech { _, _, _ -> flowOf(byteArrayOf(1, 2)) },
            system, playback, StandardTestDispatcher(testScheduler))
        assertFalse(service.speak(" \n", "en"))
        val speech = async { service.speak("Hello", "en") }
        runCurrent()
        assertFalse(speech.isCompleted)
        drained.complete(Unit)
        assertTrue(speech.await())
    }

    private class Playback : Pcm16Playback {
        override suspend fun play(chunks: Flow<ByteArray>) { chunks.collect() }
        override fun stop() = Unit
    }
    private class System : SystemSpeech {
        val texts = mutableListOf<String>()
        var result = true
        var closes = 0
        var waitForever = false
        override suspend fun speak(text: String, languageCode: String): Boolean {
            texts += text
            if (waitForever) awaitCancellation()
            return result
        }
        override fun stop() = Unit
        override fun close() { closes++ }
    }
    companion object { private val config = CloudSpeechConfig("https://example.invalid/tts", "fixture") }
}

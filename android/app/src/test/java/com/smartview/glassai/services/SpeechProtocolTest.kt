package com.smartview.glassai.services

import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SpeechProtocolTest {
    @Test fun longTextIsPreservedWithinSixHundredUtf16UnitsWithoutSplittingSurrogates() {
        val text = "a".repeat(599) + "😀" + "。" + "b".repeat(1_205)
        val parts = splitSpeechText(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.size > 1)
        parts.forEach {
            assertTrue(it.length <= 600)
            assertFalse(Character.isHighSurrogate(it.last()))
            assertFalse(Character.isLowSurrogate(it.first()))
        }
    }

    @Test fun blankTextHasNoRequestsAndPunctuationIsPreserved() {
        assertTrue(splitSpeechText(" \n\t").isEmpty())
        val text = "First. " + "a".repeat(595) + "。 Second!"
        assertEquals(text, splitSpeechText(text).joinToString(""))
    }

    @Test fun languageCodesSelectProviderLanguageWithoutCollapsingOtherLocalesToEnglish() {
        val expected = mapOf("zh-CN" to "Chinese", "en_US" to "English", "ja-JP" to "Japanese",
            "ko" to "Korean", "fr" to "French", "de" to "German", "ru" to "Russian",
            "es" to "Spanish", "pt-BR" to "Portuguese", "it" to "Italian", "unknown" to "Auto")
        expected.forEach { (code, name) ->
            val language = speechLanguage(code)
            assertEquals(name, language.languageType)
            assertEquals(if (code == "zh-CN") "Cherry" else "Ethan", language.voice)
        }
    }

    @Test fun multilineRecordsCommentsAndCrLfYieldPcmAndStopWithoutDoneSentinel() = runTest {
        val stream = ": heartbeat\r\nevent: result\r\nid: 1\r\ndata: {\r\ndata: \"output\":{\"audio\":{\"data\":\"AQIDBA==\"}}}\r\n\r\n" + stop
        val audio = mutableListOf<ByteArray>()
        readSpeechSse(Buffer().writeUtf8(stream)) { audio += it }
        assertEquals(1, audio.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.single())
    }

    @Test fun terminalRecordCanIncludeAudioAndOmitTrailingBlankLine() = runTest {
        val audio = mutableListOf<ByteArray>()
        readSpeechSse(Buffer().writeUtf8("data: {\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"data\":\"AQI=\"}}}")) { audio += it }
        assertArrayEquals(byteArrayOf(1, 2), audio.single())
    }

    @Test fun legacyDoneIsAcceptedOnlyAfterAudio() = runTest {
        val audio = mutableListOf<ByteArray>()
        readSpeechSse(Buffer().writeUtf8(chunk + "data: [DONE]\n\n")) { audio += it }
        assertArrayEquals(byteArrayOf(1, 2), audio.single())
        assertProtocolFailure("data: [DONE]\n\n")
    }

    @Test fun terminalWithNullAudioDoesNotDiscardEarlierPcm() = runTest {
        val audio = mutableListOf<ByteArray>()
        readSpeechSse(Buffer().writeUtf8(chunk + "data: {\"output\":{\"finish_reason\":\"stop\",\"audio\":null}}\n\n")) { audio += it }
        assertArrayEquals(byteArrayOf(1, 2), audio.single())
    }

    @Test fun missingTerminalEmptyAudioMalformedJsonAndInvalidBase64Fail() = runTest {
        listOf(chunk, stop, "data: nope\n\n", "data: {\"output\":{\"audio\":{\"data\":\"!!!\"}}}\n\n")
            .forEach { assertProtocolFailure(it) }
    }

    @Test fun providerErrorsAreRejectedWithoutEchoingMessageBodies() = runTest {
        listOf("data: {\"code\":\"Denied\",\"message\":\"private fixture\"}\n\n",
            "event: error\ndata: {\"message\":\"private fixture\"}\n\n",
            "data: {\"status_code\":429,\"message\":\"private fixture\"}\n\n").forEach { stream ->
            try {
                readSpeechSse(Buffer().writeUtf8(stream)) {}
                fail("Expected provider failure")
            } catch (error: SpeechProtocolException) {
                assertFalse(error.message.orEmpty().contains("private fixture"))
            }
        }
    }

    @Test fun oversizedLineOrMultilineRecordIsRejected() = runTest {
        assertProtocolFailure("data: " + "x".repeat(300_000) + "\n\n")
        assertProtocolFailure(("data: " + "x".repeat(50_000) + "\n").repeat(6) + "\n")
    }

    private suspend fun assertProtocolFailure(text: String) {
        try {
            readSpeechSse(Buffer().writeUtf8(text)) {}
            fail("Expected malformed/incomplete speech failure")
        } catch (_: SpeechProtocolException) { }
    }

    companion object {
        const val chunk = "data: {\"output\":{\"audio\":{\"data\":\"AQI=\"}}}\n\n"
        const val stop = "data: {\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"data\":\"\",\"url\":\"https://example.invalid/never-fetch\"}}}\n\n"
    }
}

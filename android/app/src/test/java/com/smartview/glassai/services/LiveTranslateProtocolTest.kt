package com.smartview.glassai.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.translation.TranslateSettings
import org.junit.Assert.*
import org.junit.Test

class LiveTranslateProtocolTest {
    @Test fun configurationUsesTheApprovedHistoricalProfile() {
        val settings = TranslateSettings()
        val event = json(LiveTranslateProtocol.configure(settings))
        assertEquals("session.update", event["type"].asString)
        assertTrue(event["event_id"].asString.isNotBlank())
        val session = event.getAsJsonObject("session")
        assertEquals(listOf("text", "audio"), session.getAsJsonArray("modalities").map { it.asString })
        assertEquals("Cherry", session["voice"].asString)
        assertEquals("pcm16", session["input_audio_format"].asString)
        assertEquals("pcm24", session["output_audio_format"].asString)
        assertEquals("en", session.getAsJsonObject("input_audio_transcription")["language"].asString)
        assertEquals("zh", session.getAsJsonObject("translation")["language"].asString)
        assertEquals(500, session.getAsJsonObject("turn_detection")["silence_duration_ms"].asInt)
        assertFalse(session.has("instructions"))
        assertFalse(session.has("enable_voice_clone"))
        assertEquals(listOf("text"), json(LiveTranslateProtocol.configure(settings.copy(audioEnabled = false)))
            .getAsJsonObject("session").getAsJsonArray("modalities").map { it.asString })
    }

    @Test fun acknowledgementMustMatchConfigurationAndCannotUpgradeTheModel() {
        val settings = TranslateSettings()
        val ack = json(LiveTranslateProtocol.configure(settings)).apply { addProperty("type", "session.updated") }
        assertTrue(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        ack.getAsJsonObject("session").addProperty("model", "qwen3.5-livetranslate-flash-realtime")
        assertFalse(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        ack.getAsJsonObject("session").addProperty("model", LiveTranslateProtocol.MODEL)
        ack.getAsJsonObject("session").getAsJsonObject("translation").addProperty("language", "en")
        assertFalse(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        assertFalse(LiveTranslateProtocol.matchesConfiguration(json("""{"type":"session.updated"}"""), settings))
    }

    @Test fun confirmedTextAndRevisableStashReplaceTheInterimSnapshot() {
        val reducer = TranslateTextReducer()
        assertEquals("Hello world", reducer.accept(json("""{"type":"response.text.text","response_id":"one","text":"Hello ","stash":"world"}"""))!!.text)
        val revised = reducer.accept(json("""{"type":"response.text.text","response_id":"one","text":"Hello ","stash":"there"}"""))!!
        assertEquals("Hello there", revised.text)
        assertFalse(revised.isFinal)
        val final = reducer.accept(json("""{"type":"response.text.done","response_id":"one","text":"Hello there!"}"""))!!
        assertEquals("Hello there!", final.text)
        assertTrue(final.isFinal)
    }

    @Test fun acknowledgementRejectsContradictorySourceFormatsVoiceAndVadAsWellAsTarget() {
        val settings = TranslateSettings()
        val changes: List<(JsonObject) -> Unit> = listOf(
            { it.getAsJsonObject("input_audio_transcription").addProperty("language", "fr") },
            { it.addProperty("input_audio_format", "pcm") },
            { it.addProperty("output_audio_format", "pcm16") },
            { it.addProperty("voice", "Dylan") },
            { it.getAsJsonObject("turn_detection").addProperty("threshold", 0.9) },
            { it.getAsJsonObject("turn_detection").addProperty("silence_duration_ms", 100) },
            { it.addProperty("modalities", "text") },
            { it.addProperty("sample_rate", 24_000) },
            { it.addProperty("enable_voice_clone", true) },
        )
        changes.forEach { change ->
            val ack = json(LiveTranslateProtocol.configure(settings)).apply { addProperty("type", "session.updated") }
            change(ack.getAsJsonObject("session"))
            assertFalse(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        }
        val snapshot = json(LiveTranslateProtocol.configure(settings)).apply { addProperty("type", "session.updated") }
        snapshot.getAsJsonObject("session").addProperty("model", "${LiveTranslateProtocol.MODEL}-2025-09-22")
        assertTrue(LiveTranslateProtocol.matchesConfiguration(snapshot, settings))
    }

    @Test fun acknowledgementAcceptsOmittedOptionalEchoesButRequiresCoreConfiguration() {
        val settings = TranslateSettings()
        val ack = json(LiveTranslateProtocol.configure(settings)).apply { addProperty("type", "session.updated") }
        val session = ack.getAsJsonObject("session")
        session.remove("input_audio_transcription") // docs: only echoed when a separate ASR model was configured
        session.remove("translation") // documented optional echo
        session.getAsJsonObject("turn_detection").remove("prefix_padding_ms")
        assertTrue(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        session.remove("turn_detection")
        assertTrue(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        session.add("translation", json("""{"language":"fr"}"""))
        assertFalse(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        session.remove("translation")
        session.remove("input_audio_format")
        assertFalse(LiveTranslateProtocol.matchesConfiguration(ack, settings))
        assertFalse(LiveTranslateProtocol.matchesConfiguration(json("""{"type":"session.updated","session":{}}"""), settings))
    }

    @Test fun retiredItemsCannotOverwriteTheCurrentItemWithinAResponse() {
        val reducer = TranslateTextReducer()
        reducer.accept(json("""{"type":"response.text.text","response_id":"one","item_id":"a","text":"old"}"""))
        assertEquals("new", reducer.accept(json("""{"type":"response.text.text","response_id":"one","item_id":"b","text":"new"}"""))!!.text)
        assertNull(reducer.accept(json("""{"type":"response.text.done","response_id":"one","item_id":"a","text":"old final"}""")))
        assertEquals("new final", reducer.accept(json("""{"type":"response.text.done","response_id":"one","item_id":"b","text":"new final"}"""))!!.text)
    }

    @Test fun audioModeSupportsDocumentedFieldsAndLegacyAliasesWithoutJoiningResponses() {
        val reducer = TranslateTextReducer()
        reducer.accept(json("""{"type":"response.audio_transcript.text","response_id":"one","text":"你好","stash":"吗"}"""))
        assertEquals("你好！", reducer.accept(json("""{"type":"response.audio_transcript.done","response_id":"one","transcript":"你好！"}"""))!!.text)
        assertEquals("Good", reducer.accept(json("""{"type":"response.audio_transcript.delta","response_id":"two","delta":"Good"}"""))!!.text)
        assertEquals("Good day", reducer.accept(json("""{"type":"response.audio_transcript.text","response_id":"two","delta":" day"}"""))!!.text)
        assertEquals("Good day!", reducer.accept(json("""{"type":"response.audio_transcript.done","response_id":"two","text":"Good day!"}"""))!!.text)
        assertNull(reducer.accept(json("""{"type":"response.audio_transcript.done","response_id":"one","transcript":"late"}""")))
    }

    @Test fun aNewResponseIsNotOverwrittenByOldPartialOrFinalEvents() {
        val reducer = TranslateTextReducer()
        reducer.accept(json("""{"type":"response.created","response":{"id":"one"}}"""))
        reducer.accept(json("""{"type":"response.text.text","response_id":"one","text":"first"}"""))
        reducer.accept(json("""{"type":"response.created","response":{"id":"two"}}"""))
        assertNull(reducer.accept(json("""{"type":"response.text.done","response_id":"one","text":"old final"}""")))
        assertEquals("second", reducer.accept(json("""{"type":"response.text.text","response_id":"two","text":"second"}"""))!!.text)
    }

    @Test fun longTextIsBoundedAtACompleteCodePointAndDoesNotGrowOnMoreDeltas() {
        val reducer = TranslateTextReducer(maxChars = 12)
        val event = JsonObject().apply {
            addProperty("type", "response.text.delta")
            addProperty("delta", "a".repeat(10) + "😀" + "tail".repeat(50))
        }
        repeat(20) {
            val text = reducer.accept(event)!!.text
            assertTrue(text.length <= 12)
            assertEquals("a".repeat(10) + "…", text)
        }
    }

    @Test fun malformedShapesAndNonTextLifecycleEventsDoNotInventText() {
        val reducer = TranslateTextReducer()
        listOf("{}", """{"type":"response.text.done","text":{}}""",
            """{"type":"response.audio.done"}""", """{"type":"response.text.text","text":42}""")
            .forEach { assertNull(reducer.accept(json(it))) }
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
}

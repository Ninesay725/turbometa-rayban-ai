package com.smartview.glassai.services

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartview.glassai.translation.TranslateSettings
import com.smartview.glassai.translation.TranslationText
import java.util.Base64
import java.util.UUID

/** Historical, approved Qwen 3 profile. Exact-model live wire acceptance remains pending. */
internal object LiveTranslateProtocol {
    const val MODEL = "qwen3-livetranslate-flash-realtime"
    const val MAX_EVENT_CHARS = 131_072
    const val MAX_AUDIO_BYTES = 32_000
    const val MAX_IMAGE_BYTES = 500_000
    const val MAX_SOCKET_BYTES = 1_048_576L

    fun event(type: String): JsonObject = JsonObject().apply {
        addProperty("event_id", "translate_${UUID.randomUUID()}")
        addProperty("type", type)
    }

    fun configure(settings: TranslateSettings): String = event("session.update").apply {
        add("session", JsonObject().apply {
            add("modalities", JsonArray().apply { add("text"); if (settings.audioEnabled) add("audio") })
            addProperty("voice", settings.voice.id)
            addProperty("input_audio_format", "pcm16")
            // pcm24 is the historical rate label: output samples are still signed PCM16.
            addProperty("output_audio_format", "pcm24")
            add("input_audio_transcription", JsonObject().apply { addProperty("language", settings.sourceLanguage.code) })
            add("translation", JsonObject().apply { addProperty("language", settings.targetLanguage.code) })
            add("turn_detection", JsonObject().apply {
                addProperty("type", "server_vad")
                addProperty("threshold", 0.5)
                addProperty("prefix_padding_ms", 300)
                addProperty("silence_duration_ms", 500)
            })
        })
    }.toString()

    fun parse(value: String): JsonObject? = runCatching {
        JsonParser.parseString(value).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    fun matchesConfiguration(event: JsonObject, settings: TranslateSettings): Boolean {
        if (event.string("type") != "session.updated") return false
        val session = event.obj("session") ?: return false
        val model = session.string("model")
        if (session.has("model") && model != MODEL && model != "$MODEL-2025-09-22") return false
        val modalities = session["modalities"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        val wanted = if (settings.audioEnabled) setOf("text", "audio") else setOf("text")
        if (modalities.size() != wanted.size || modalities.map { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }.toSet() != wanted) return false
        // Core wire formats/modalities must be observable. Optional echoes are not proof of
        // failure when absent: current docs omit source ASR unless a separate ASR model is set,
        // and mark translation optional. The exact Qwen 3 ACK still needs a live fixture.
        return session.string("input_audio_format") == "pcm16" &&
            session.string("output_audio_format") == "pcm24" &&
            ((!settings.audioEnabled && !session.has("voice")) || session.string("voice") == settings.voice.id) &&
            session.optionalNumberMatches("sample_rate", 16_000.0) &&
            session.optionalObjectMatches("input_audio_transcription") {
                it.optionalStringMatches("language", settings.sourceLanguage.code)
            } && session.optionalObjectMatches("translation") {
                it.optionalStringMatches("language", settings.targetLanguage.code) &&
                    it.optionalStringMatches("source_language", settings.sourceLanguage.code)
            } && session.optionalObjectMatches("turn_detection") {
                it.optionalStringMatches("type", "server_vad") && it.optionalNumberMatches("threshold", 0.5) &&
                    it.optionalNumberMatches("prefix_padding_ms", 300.0) &&
                    it.optionalNumberMatches("silence_duration_ms", 500.0)
            } && (!session.has("enable_voice_clone") || session["enable_voice_clone"].let {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean
            })
    }

    fun append(type: String, field: String, bytes: ByteArray): String = event(type).apply {
        addProperty(field, Base64.getEncoder().encodeToString(bytes))
    }.toString()

    fun decodeAudio(event: JsonObject): ByteArray? {
        val delta = event.string("delta") ?: return null
        if (delta.length > ((MAX_AUDIO_BYTES + 2) / 3) * 4) return null
        return runCatching { Base64.getDecoder().decode(delta) }.getOrNull()
            ?.takeIf { it.size <= MAX_AUDIO_BYTES }
    }
}

internal fun JsonObject.string(key: String): String? = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.number(key: String): Double? = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
private fun JsonObject.optionalStringMatches(key: String, expected: String) = !has(key) || string(key) == expected
private fun JsonObject.optionalNumberMatches(key: String, expected: Double) = !has(key) || number(key) == expected
private fun JsonObject.optionalObjectMatches(key: String, matches: (JsonObject) -> Boolean) =
    !has(key) || obj(key)?.let(matches) == true

/** One bounded current utterance; revised stash is a snapshot, never an accumulated delta. */
internal class TranslateTextReducer(private val maxChars: Int = 8_000) {
    init { require(maxChars >= 2) }
    private var responseId: String? = null
    private var itemId: String? = null
    private val retired = LinkedHashSet<String>()
    private var explicitResponseLifecycle = false
    private var value = ""
    private var final = false
    private var truncated = false

    private fun retire(key: String?) {
        if (key == null) return
        retired += key
        if (retired.size > 64) retired.remove(retired.first())
    }

    private fun resetText() { value = ""; final = false; truncated = false }

    /** Audio and text share this identity fence. Legacy streams without IDs remain supported. */
    fun acceptsIdentity(event: JsonObject): Boolean {
        val created = event.string("type") == "response.created"
        val response = event.string("response_id") ?: event.obj("response")?.string("id")
        val item = event.string("item_id")
        if ((response?.length ?: 0) > 128 || (item?.length ?: 0) > 128) return false
        if (response != null && "r:$response" in retired || item != null && "i:$item" in retired) return false
        if (response != null && response != responseId) {
            if (!created && explicitResponseLifecycle && responseId != null) return false
            retire(responseId?.let { "r:$it" }); retire(itemId?.let { "i:$it" })
            responseId = response; itemId = null; resetText()
        }
        if (created) explicitResponseLifecycle = true
        if (item != null && item != itemId) {
            if (itemId != null) { retire("i:$itemId"); resetText() }
            itemId = item
        }
        return true
    }

    fun accept(event: JsonObject): TranslationText? {
        val type = event.string("type") ?: return null
        if (type == "response.created") { acceptsIdentity(event); return null }
        val snapshot = type == "response.text.text" || type == "response.audio_transcript.text"
        val delta = type == "response.text.delta" || type == "response.audio_transcript.delta"
        val done = type == "response.text.done" || type == "response.audio_transcript.done"
        if (!snapshot && !delta && !done) return null
        val content = if (done && type == "response.audio_transcript.done")
            event.string("transcript") ?: event.string("text")
        else if (done) event.string("text") ?: event.string("transcript")
        else if (snapshot) event.string("text") ?: event.string("delta")
        else event.string("delta")
        if (content == null || !acceptsIdentity(event)) return null
        if (final) {
            if (event.string("response_id") != null || event.string("item_id") != null || done) return null
            resetText() // old no-ID protocol: a new partial starts the next utterance
        }
        val isDelta = delta || snapshot && event.string("text") == null
        if (isDelta) {
            if (!truncated) value = bound(value + content)
        } else {
            truncated = false
            value = bound(content + if (done) "" else event.string("stash").orEmpty())
        }
        final = done
        return TranslationText(value, done)
    }

    private fun bound(text: String): String {
        if (text.length <= maxChars) return text
        var end = maxChars - 1
        if (end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        truncated = true
        return text.substring(0, end) + "…"
    }
}

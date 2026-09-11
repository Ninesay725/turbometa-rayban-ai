package com.smartview.glassai.services

import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import okio.BufferedSource
import java.io.EOFException
import java.io.IOException
import java.util.Base64
import java.util.Locale

internal class CloudSpeechConfig(val url: String, val apiKey: String)
internal data class SpeechLanguage(val voice: String, val languageType: String)
internal class SpeechProtocolException(message: String) : IOException(message)

internal fun interface CloudSpeech {
    fun audio(text: String, language: SpeechLanguage, config: CloudSpeechConfig): Flow<ByteArray>
}

internal interface SystemSpeech {
    suspend fun speak(text: String, languageCode: String): Boolean
    fun stop()
    fun close()
}

internal fun speechLanguage(code: String): SpeechLanguage {
    val base = code.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    val name = when (base) {
        "zh" -> "Chinese"
        "en" -> "English"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "fr" -> "French"
        "de" -> "German"
        "ru" -> "Russian"
        "es" -> "Spanish"
        "pt" -> "Portuguese"
        "it" -> "Italian"
        else -> "Auto"
    }
    return SpeechLanguage(if (base == "zh") "Cherry" else "Ethan", name)
}

/** UTF-16 length is a conservative character bound; joining the pieces recovers the original. */
internal fun splitSpeechText(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 600, text.length)
        if (end < text.length) {
            if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            val breakAt = (end - 1 downTo start + 300).firstOrNull {
                text[it].isWhitespace() || text[it] in ".!?。！？；;"
            }
            if (breakAt != null) end = breakAt + 1
        }
        parts += text.substring(start, end)
        start = end
    }
    return parts
}

private const val MAX_SSE_RECORD = 262_144L

private fun BufferedSource.speechLine(): String? {
    return try {
        readUtf8LineStrict(MAX_SSE_RECORD)
    } catch (_: EOFException) {
        if (buffer.size > MAX_SSE_RECORD) throw SpeechProtocolException("Speech event exceeds its size bound")
        if (buffer.size == 0L) null else readUtf8().removeSuffix("\r")
    }
}

/** Complete SSE records, with no dependence on SDK-only response/event wrappers. */
internal suspend fun readSpeechSse(source: BufferedSource, onAudio: suspend (ByteArray) -> Unit) {
    val data = StringBuilder()
    var event = ""
    var receivedAudio = false
    var stopped = false
    suspend fun dispatch() {
        if (event == "error") throw SpeechProtocolException("Speech provider returned an error")
        if (data.isEmpty()) { event = ""; return }
        val json = data.toString()
        data.setLength(0)
        event = ""
        if (json.trim() == "[DONE]") {
            stopped = true // Compatibility with the existing iOS client; stop is also supported.
            return
        }
        val output = try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.get("code")?.takeUnless { it.isJsonNull }?.asString
            val status = root.get("status_code")?.takeUnless { it.isJsonNull }?.asInt
            if (!code.isNullOrEmpty() || (status != null && status !in 200..299)) {
                throw SpeechProtocolException("Speech provider returned an error")
            }
            root.getAsJsonObject("output") ?: throw SpeechProtocolException("Speech output is missing")
        } catch (error: SpeechProtocolException) { throw error }
        catch (_: RuntimeException) { throw SpeechProtocolException("Malformed speech event") }
        val encoded: String?
        val finish: String?
        try {
            encoded = output.get("audio")?.takeUnless { it.isJsonNull }?.asJsonObject
                ?.get("data")?.takeUnless { it.isJsonNull }?.asString
            finish = output.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString
        } catch (_: RuntimeException) { throw SpeechProtocolException("Malformed speech audio") }
        if (!encoded.isNullOrEmpty()) {
            val bytes = try { Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw SpeechProtocolException("Invalid speech audio encoding") }
            if (bytes.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                onAudio(bytes)
                receivedAudio = true
            }
        }
        if (finish == "stop") stopped = true
        else if (finish != null && finish != "null") throw SpeechProtocolException("Speech ended unexpectedly")
    }

    while (!stopped) {
        currentCoroutineContext().ensureActive()
        val line = source.speechLine()
        if (line == null) { dispatch(); break }
        if (line.isEmpty()) { dispatch(); continue }
        if (line.startsWith(':')) continue
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.length + value.length + 1 > MAX_SSE_RECORD) throw SpeechProtocolException("Speech event exceeds its size bound")
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }
    if (!stopped) throw SpeechProtocolException("Speech stream ended before completion")
    if (!receivedAudio) throw SpeechProtocolException("Speech stream contained no audio")
}

package com.smartview.glassai.services

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class HttpCloudSpeech(private val calls: Call.Factory = defaultClient) : CloudSpeech {
    override fun audio(text: String, language: SpeechLanguage, config: CloudSpeechConfig): Flow<ByteArray> = flow {
        require(text.length <= 600)
        val body = JsonObject().apply {
            addProperty("model", "qwen3-tts-flash")
            add("input", JsonObject().apply {
                addProperty("text", text)
                addProperty("voice", language.voice)
                addProperty("language_type", language.languageType)
            })
        }
        val request = Request.Builder().url(config.url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-DashScope-SSE", "enable")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = calls.newCall(request)
        coroutineScope {
            // A separate IO child closes a blocking header/body read as soon as its parent cancels.
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw SpeechProtocolException("Speech HTTP request failed (${response.code})")
                    val responseBody = response.body ?: throw SpeechProtocolException("Speech response is empty")
                    if (responseBody.contentType()?.let { "${it.type}/${it.subtype}" } != "text/event-stream") {
                        throw SpeechProtocolException("Speech response is not an event stream")
                    }
                    readSpeechSse(responseBody.source()) { emit(it) }
                }
            } catch (error: IOException) {
                // Closing a cancelled Call wakes blocking reads with an IOException.
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                call.cancel()
                cancellation.cancel()
            }
        }
    }.flowOn(Dispatchers.IO).buffer(0)

    companion object {
        private val defaultClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        }
    }
}

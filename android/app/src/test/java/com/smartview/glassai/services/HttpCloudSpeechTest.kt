package com.smartview.glassai.services

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpCloudSpeechTest {
    @Test fun realHttpRequestUsesExactPayloadAndSseHeaderAndDoesNotFetchFinalUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(SpeechProtocolTest.chunk + SpeechProtocolTest.stop).throttleBody(3, 1, TimeUnit.MILLISECONDS))
            val config = CloudSpeechConfig(server.url("/tts").toString(), "synthetic-key")
            val audio = HttpCloudSpeech(OkHttpClient()).audio("Hello", speechLanguage("en-US"), config).toList()
            assertArrayEquals(byteArrayOf(1, 2), audio.single())
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("enable", request.getHeader("X-DashScope-SSE"))
            assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(setOf("model", "input"), body.keySet())
            assertEquals("qwen3-tts-flash", body["model"].asString)
            assertEquals("Hello", body.getAsJsonObject("input")["text"].asString)
            assertEquals("Ethan", body.getAsJsonObject("input")["voice"].asString)
            assertEquals("English", body.getAsJsonObject("input")["language_type"].asString)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun nonSuccessHttpFailsWithoutReturningBodyDetails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private fixture body"))
            try {
                HttpCloudSpeech(OkHttpClient()).audio("Hello", speechLanguage("en"),
                    CloudSpeechConfig(server.url("/tts").toString(), "fixture")).toList()
                fail("Expected HTTP error")
            } catch (error: SpeechProtocolException) {
                assertFalse(error.message.orEmpty().contains("private fixture"))
            }
        }
    }

    @Test fun cancellationWhileWaitingForResponseCancelsTheActualOkHttpCall() = runBlocking {
        MockWebServer().use { server ->
            val cancelled = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.complete(Unit) }
            }).build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = async {
                HttpCloudSpeech(client).audio("Hello", speechLanguage("en"),
                    CloudSpeechConfig(server.url("/tts").toString(), "fixture")).toList()
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            task.cancel()
            withTimeout(2_000) { cancelled.await(); task.join() }
            assertTrue(task.isCancelled)
        }
    }

    @Test fun cancellationAfterAnAudioRecordInterruptsABlockedBodyRead() = runBlocking {
        MockWebServer().use { server ->
            val cancelled = CompletableDeferred<Unit>()
            val audioReceived = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.complete(Unit) }
            }).build()
            // Deliberately leave the advertised body incomplete, with an open connection.
            server.enqueue(MockResponse().setBody(SpeechProtocolTest.chunk)
                .setHeader("Content-Type", "text/event-stream").setHeader("Content-Length", "1000000"))
            val task = async {
                HttpCloudSpeech(client).audio("Hello", speechLanguage("en"),
                    CloudSpeechConfig(server.url("/tts").toString(), "fixture")).collect {
                    audioReceived.complete(Unit)
                }
            }
            withTimeout(2_000) { audioReceived.await() }
            task.cancel()
            withTimeout(2_000) { cancelled.await(); task.join() }
            assertTrue(task.isCancelled)
        }
    }
}

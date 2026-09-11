package com.smartview.glassai.services.openclaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawNodeChatTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun ack(frame: JsonObject) = json("""{"type":"res","id":"${frame.get("id").asString}","ok":true,"payload":{"ok":true}}""")
    private fun session(frame: JsonObject) = json(frame.getAsJsonObject("params").get("payloadJSON").asString).get("sessionKey").asString

    @Test fun ackIsNotCompletionAndMissingReplyTimesOutWithoutResending() = runTest {
        val sent = mutableListOf<JsonObject>()
        val states = mutableListOf<OpenClawConnectionState>()
        val events = mutableListOf<OpenClawChatEvent>()
        val chat = OpenClawNodeChat(Any(), this, "s", { sent.add(it); true }, events::add, states::add,
            replyTimeoutMs = 100)
        chat.start()
        chat.handleResponse(ack(sent.last()))
        assertEquals(OpenClawConnectionState.Connected, states.last())
        assertTrue(chat.sendMessage("hello", null))
        chat.handleResponse(ack(sent.last()))
        assertTrue(events.isEmpty())
        assertFalse(chat.sendMessage("second overlapping turn", null))
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(2, sent.size) // subscription + original request, no inference replay
        assertTrue(states.last() is OpenClawConnectionState.Error)
        assertEquals(listOf(OpenClawChatEvent("", true)), events)
        chat.close()
    }

    @Test fun subscriptionTimeoutAndExplicitRetryAreBounded() = runTest {
        val sent = mutableListOf<JsonObject>()
        val states = mutableListOf<OpenClawConnectionState>()
        val chat = OpenClawNodeChat(Any(), this, "s", { sent.add(it); true }, {}, states::add,
            requestTimeoutMs = 100)
        chat.start()
        val staleAck = ack(sent.single())
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertTrue(states.last() is OpenClawConnectionState.Error)
        assertFalse(chat.sendMessage("before subscription", null))
        chat.start()
        assertFalse(chat.handleResponse(staleAck))
        assertTrue(chat.handleResponse(ack(sent.last())))
        assertEquals(OpenClawConnectionState.Connected, states.last())
        chat.close()
    }

    @Test fun wrongSessionDuplicatesAndOldRunCannotContaminateV4Text() = runTest {
        val sent = mutableListOf<JsonObject>()
        val events = mutableListOf<OpenClawChatEvent>()
        val chat = OpenClawNodeChat(Any(), this, "s", { sent.add(it); true }, events::add, {})
        chat.start()
        val session = session(sent.last())
        chat.handleResponse(ack(sent.last()))
        chat.sendMessage("hi", null)
        chat.handleChat(json("""{"sessionKey":"other","runId":"r","seq":1,"state":"delta","deltaText":"wrong"}"""))
        chat.handleChat(json("""{"sessionKey":"$session","runId":"r","seq":1,"state":"delta","deltaText":"hello"}"""))
        chat.handleChat(json("""{"sessionKey":"$session","runId":"r","seq":1,"state":"delta","deltaText":"duplicate"}"""))
        chat.handleChat(json("""{"sessionKey":"$session","runId":"old","seq":2,"state":"delta","deltaText":"wrong run"}"""))
        // Gateway can flush a delta and then a final with the same sequence number.
        chat.handleChat(json("""{"sessionKey":"$session","runId":"r","seq":1,"state":"final","message":{"content":[{"type":"text","text":"hello!"}]}}"""))
        assertEquals(listOf(OpenClawChatEvent("hello", false), OpenClawChatEvent("hello!", true)), events)
        chat.close()
    }

    @Test fun officialV3SnapshotsReplaceInsteadOfAppendingAndAbortEndsTheTurn() = runTest {
        val sent = mutableListOf<JsonObject>()
        val events = mutableListOf<OpenClawChatEvent>()
        val states = mutableListOf<OpenClawConnectionState>()
        val chat = OpenClawNodeChat(Any(), this, "s", { sent.add(it); true }, events::add, states::add)
        chat.start()
        val session = session(sent.last())
        chat.handleResponse(ack(sent.last()))
        chat.sendMessage("hi", null)
        for ((seq, text) in listOf("h", "hello").withIndex()) {
            chat.handleChat(json("""{"sessionKey":"$session","runId":"r","seq":$seq,"state":"delta","message":{"content":[{"type":"text","text":"$text"}]}}"""))
        }
        chat.handleChat(json("""{"sessionKey":"$session","runId":"r","seq":2,"state":"aborted","errorMessage":"cancelled"}"""))
        assertEquals(listOf(OpenClawChatEvent("h", false), OpenClawChatEvent("hello", false), OpenClawChatEvent("", true)), events)
        assertTrue(states.last() is OpenClawConnectionState.Error)
        chat.close()
    }

    @Test fun refusedAgentRequestAndClosedSessionCannotClaimSuccess() = runTest {
        val sent = mutableListOf<JsonObject>()
        val states = mutableListOf<OpenClawConnectionState>()
        val chat = OpenClawNodeChat(Any(), this, "s", { sent.add(it); true }, {}, states::add)
        chat.start()
        chat.handleResponse(ack(sent.last()))
        assertFalse(chat.sendMessage("x".repeat(20001), null))
        assertFalse(chat.sendMessage(" ", null))
        assertTrue(chat.sendMessage("hi", null))
        val id = sent.last().get("id").asString
        chat.handleResponse(json("""{"type":"res","id":"$id","ok":false,"error":{"code":"UNAVAILABLE","message":"no model"}}"""))
        assertTrue(states.last() is OpenClawConnectionState.Error)
        chat.close()
        assertFalse(chat.handleResponse(ack(sent.last())))
        assertFalse(chat.sendMessage("closed", null))
    }

    @Test fun cancelledSubscriptionTimeoutAlreadyWaitingForTheLockCannotFailANewTurn() = runTest {
        checkCancelledTimeoutAfterLockAcquisition(subscriptionTimeout = true)
    }

    @Test fun cancelledReplyTimeoutAlreadyWaitingForTheLockCannotFailANewTurn() = runTest {
        checkCancelledTimeoutAfterLockAcquisition(subscriptionTimeout = false)
    }

    private fun TestScope.checkCancelledTimeoutAfterLockAcquisition(subscriptionTimeout: Boolean) {
        val lock = Any()
        val sent = mutableListOf<JsonObject>()
        val states = mutableListOf<OpenClawConnectionState>()
        val events = mutableListOf<OpenClawChatEvent>()
        var busy = false
        val chat = OpenClawNodeChat(lock, this, "s", { sent.add(it); true }, events::add, states::add,
            requestTimeoutMs = 100, replyTimeoutMs = 100, onBusy = { busy = it })
        chat.start()
        val session = session(sent.last())
        if (!subscriptionTimeout) {
            chat.handleResponse(ack(sent.last()))
            assertTrue(chat.sendMessage("A", null))
        }
        runCurrent() // Arm the delay before expiring it on a worker blocked by the monitor.
        val workerFailure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                testScheduler.advanceTimeBy(101)
                testScheduler.runCurrent()
            } catch (failure: Throwable) { workerFailure.set(failure) }
        }, "expired-chat-timeout")
        try {
            synchronized(lock) {
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                // Android's mockable API excludes java.lang.management. The blocked state and
                // top timeout frame establish that this continuation reached the chat monitor.
                while (worker.state != Thread.State.BLOCKED || worker.stackTrace.firstOrNull()?.let {
                        it.className.contains("OpenClawNodeChat") && it.className.contains("armTimeout")
                    } != true
                ) {
                    check(worker.isAlive && System.nanoTime() < deadline) { "timeout did not reach the chat lock" }
                    Thread.sleep(1)
                }
                // Cancellation now happens AFTER delay resumed, while its continuation waits for lock.
                if (subscriptionTimeout) chat.handleResponse(ack(sent.last()))
                else chat.handleChat(json("""{"sessionKey":"$session","runId":"session-id","seq":1,"state":"final","message":{"content":[{"type":"text","text":"A reply"}]}}"""))
                assertTrue(chat.sendMessage("B", null))
            }
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertNull(workerFailure.get())
            assertEquals(OpenClawConnectionState.Connected, states.last())
            assertTrue(busy)
            assertEquals(if (subscriptionTimeout) emptyList<OpenClawChatEvent>() else listOf(OpenClawChatEvent("A reply", true)), events)
            chat.handleChat(json("""{"sessionKey":"$session","runId":"session-id","seq":1,"state":"final","message":{"content":[{"type":"text","text":"B reply"}]}}"""))
            assertEquals(OpenClawChatEvent("B reply", true), events.last())
            assertFalse(busy)
        } finally {
            chat.close()
            worker.join(5_000)
        }
    }
}

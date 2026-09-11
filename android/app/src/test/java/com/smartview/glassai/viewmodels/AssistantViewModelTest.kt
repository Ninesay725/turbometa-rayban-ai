package com.smartview.glassai.viewmodels

import com.smartview.glassai.bridge.BridgeSettings
import com.smartview.glassai.services.assistant.*
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {
    @Test fun stoppedReplyCannotDisplaySpeakOrClearTheNextRequestsBusyFlag() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<AssistantReply>()
        val next = CompletableDeferred<AssistantReply>()
        var calls = 0
        f.answer = { if (++calls == 1) withContext(NonCancellable) { old.await() } else next.await() }
        f.vm.enterScreen()
        f.vm.setInput("old")
        f.vm.send()
        runCurrent()
        f.vm.stop()
        f.vm.setInput("new")
        f.vm.send()
        runCurrent()
        old.complete(AssistantReply("late", emptyList()))
        runCurrent()
        assertTrue(f.vm.ui.value.busy)
        assertTrue(f.displayed.isEmpty())
        assertTrue(f.spoken.isEmpty())
        next.complete(AssistantReply("current", emptyList()))
        runCurrent()
        assertEquals(listOf("current"), f.displayed)
        assertEquals(listOf("current"), f.spoken)
        assertFalse(f.vm.ui.value.busy)
        f.vm.leaveScreen()
    }

    @Test fun leavingDuringPermissionCannotCaptureOrSendAfterADeferredGrant() = runTest {
        val f = Fixture(this)
        val permission = CompletableDeferred<Boolean>()
        f.vm.enterScreen()
        f.vm.setInput("What is this?")
        f.vm.send(withPhoto = true, requestCameraPermission = { withContext(NonCancellable) { permission.await() } })
        runCurrent()
        f.vm.leaveScreen()
        permission.complete(true)
        runCurrent()
        assertEquals(0, f.captures)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertEquals("", f.vm.ui.value.input)
    }

    @Test fun consentRevocationErasesSummariesAndInvalidatesAnInFlightReply() = runTest {
        val f = Fixture(this)
        val pending = CompletableDeferred<AssistantReply>()
        f.answer = { withContext(NonCancellable) { pending.await() } }
        f.vm.enterScreen()
        f.vm.summarizeNotifications("Summarize the selected apps")
        runCurrent()
        assertTrue(f.requests.single().notificationsOnly)
        f.notifications.value = BridgeSettings()
        runCurrent()
        pending.complete(AssistantReply("private summary", emptyList()))
        runCurrent()
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertTrue(f.displayed.isEmpty())
        assertTrue(f.spoken.isEmpty())
        assertFalse(f.vm.ui.value.busy)
        f.vm.leaveScreen()
    }

    @Test fun ordinaryChatNeverReceivesNotificationSummaryHistory() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        f.vm.summarizeNotifications("Summarize notifications")
        runCurrent()
        f.vm.setInput("Translate hello into Chinese")
        f.vm.send()
        runCurrent()
        assertTrue(f.requests.first().notificationsOnly)
        assertFalse(f.requests.last().notificationsOnly)
        assertTrue(f.requests.last().history.isEmpty())
        f.vm.leaveScreen()
    }

    @Test fun modelChosenCardIsNotOverwrittenByTheGenericReplyCard() = runTest {
        val f = Fixture(this)
        f.answer = { AssistantReply("Done", listOf("display_card")) }
        f.vm.enterScreen()
        f.vm.setInput("Show a card")
        f.vm.send()
        runCurrent()
        assertTrue(f.displayed.isEmpty())
        assertEquals("Done", f.vm.ui.value.messages.last().text)
        f.vm.leaveScreen()
    }

    @Test fun conversationIsBoundedAndClearedOnLeave() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        repeat(9) {
            f.vm.setInput("question $it")
            f.vm.send()
            runCurrent()
        }
        assertEquals(12, f.vm.ui.value.messages.size)
        assertTrue(f.requests.all { it.history.size <= 12 })
        f.vm.leaveScreen()
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertTrue(f.releases > 0)
    }

    @Test fun consecutiveTurnsKeepTheSessionUntilExplicitStop() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        repeat(2) {
            f.vm.setInput("hello $it")
            f.vm.send()
            runCurrent()
        }
        assertEquals(0, f.releases)
        assertEquals(0, f.cardClears)
        assertEquals(listOf(false, false), f.turns)
        f.vm.stop()
        assertEquals(1, f.releases)
        f.vm.leaveScreen()
        assertEquals(1, f.releases)
    }

    @Test fun onlySanitizedCoreErrorsAreDisplayed() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        f.answer = { throw AssistantException("HTTP 401：请检查密钥") }
        f.vm.setInput("hello")
        f.vm.send()
        runCurrent()
        assertEquals("HTTP 401：请检查密钥", f.vm.ui.value.safeDetail)
        f.answer = { throw IllegalStateException("Authorization: Bearer private-token") }
        f.vm.setInput("try again")
        f.vm.send()
        runCurrent()
        assertEquals(AssistantUiError.REQUEST, f.vm.ui.value.error)
        assertNull(f.vm.ui.value.safeDetail)
        assertFalse(f.vm.ui.value.messages.any { "private-token" in it.text })
        f.vm.leaveScreen()
    }

    @Test fun appSelectionIsPassedSeparatelyAndUnapprovedPackagesCannotSend() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        f.vm.summarizeNotifications("Summarize notifications", "com.example.chat")
        runCurrent()
        assertEquals("com.example.chat", f.requests.single().packageName)
        f.vm.summarizeNotifications("Summarize notifications", "com.other.app")
        runCurrent()
        assertEquals(1, f.requests.size)
        assertEquals(AssistantUiError.NOTIFICATIONS_DISABLED, f.vm.ui.value.error)
        f.vm.leaveScreen()
    }

    @Test fun normalChatCameraToolWaitsForTheScreensPermissionCallback() = runTest {
        val f = Fixture(this)
        val permission = CompletableDeferred<Boolean>()
        f.answer = { tools -> tools.execute("camera_capture", JsonObject()); AssistantReply("photo answer", listOf("camera_capture")) }
        f.vm.enterScreen()
        f.vm.setInput("Look at this")
        f.vm.send(requestCameraPermission = { permission.await() })
        runCurrent()
        assertTrue(f.executedTools.isEmpty())
        permission.complete(true)
        runCurrent()
        assertEquals(listOf("camera_capture"), f.executedTools)
        f.vm.leaveScreen()
    }

    @Test fun aSummaryCannotRunADeviceActionAndNormalChatCannotReadNotifications() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        f.answer = { tools -> tools.execute("music_control", JsonObject()); AssistantReply("unexpected", emptyList()) }
        f.vm.summarizeNotifications("Summarize notifications")
        runCurrent()
        assertTrue(f.executedTools.isEmpty())
        f.answer = { tools -> tools.execute("notifications_read", JsonObject()); AssistantReply("unexpected", emptyList()) }
        f.vm.setInput("Read notifications")
        f.vm.send()
        runCurrent()
        assertTrue(f.executedTools.isEmpty())
        f.vm.leaveScreen()
    }

    @Test fun lateDictationCannotRefillTheInputAfterLeaving() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        f.vm.dictate { true }
        runCurrent()
        val result = f.dictationResult!!
        f.vm.leaveScreen()
        result("late transcript")
        assertEquals("", f.vm.ui.value.input)
        assertFalse(f.vm.ui.value.listening)
    }

    @Test fun externalPermissionStopKeepsOnlyTheQuestionAndNeverResumesWorkAutomatically() = runTest {
        val f = Fixture(this)
        val permission = CompletableDeferred<Boolean>()
        f.vm.enterScreen()
        f.vm.setInput("What is in front of me?")
        f.vm.send(withPhoto = true, requestCameraPermission = { withContext(NonCancellable) { permission.await() } })
        runCurrent()
        f.vm.onBackground()
        assertEquals("What is in front of me?", f.vm.ui.value.input)
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertFalse(f.vm.ui.value.busy)
        f.vm.enterScreen()
        permission.complete(true)
        runCurrent()
        assertEquals(0, f.captures)
        assertTrue(f.requests.isEmpty())
        assertEquals(AssistantUiError.INTERRUPTED, f.vm.ui.value.error)
        f.vm.send(withPhoto = true, requestCameraPermission = { true })
        runCurrent()
        assertEquals(1, f.captures)
        assertEquals(1, f.requests.size)
        f.vm.leaveScreen()
        assertEquals("", f.vm.ui.value.input)
    }

    @Test fun aModelCameraPermissionRoundTripRestoresTheOriginalQuestionWithoutItsHistory() = runTest {
        val f = Fixture(this)
        val permission = CompletableDeferred<Boolean>()
        f.answer = { tools -> tools.execute("camera_capture", JsonObject()); AssistantReply("unused", emptyList()) }
        f.vm.enterScreen()
        f.vm.setInput("Look at my desk")
        f.vm.send(requestCameraPermission = { withContext(NonCancellable) { permission.await() } })
        runCurrent()
        assertEquals("", f.vm.ui.value.input)
        f.vm.onBackground()
        f.vm.enterScreen()
        permission.complete(true)
        runCurrent()
        assertEquals("Look at my desk", f.vm.ui.value.input)
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertTrue(f.executedTools.isEmpty())
        f.vm.leaveScreen()
    }

    @Test fun rapidConsentOffOnInvalidatesTheCopiedNotificationsEvenWhenFlagsMatchAgain() = runTest {
        val f = Fixture(this)
        val pending = CompletableDeferred<AssistantReply>()
        f.answer = { withContext(NonCancellable) { pending.await() } }
        f.vm.enterScreen()
        f.vm.summarizeNotifications("Summarize notifications")
        runCurrent()
        f.notifications.value = f.notifications.value.copy(aiNotificationsEnabled = false, aiConsentRevision = 1)
        f.notifications.value = f.notifications.value.copy(aiNotificationsEnabled = true, aiConsentRevision = 2)
        runCurrent()
        pending.complete(AssistantReply("retired private content", emptyList()))
        runCurrent()
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertTrue(f.displayed.isEmpty())
        assertTrue(f.spoken.isEmpty())
        f.vm.leaveScreen()
    }

    @Test fun notificationAccessLossClearsCompletedSummariesAndRejectsInFlightOnes() = runTest {
        val f = Fixture(this)
        f.vm.enterScreen()
        f.vm.summarizeNotifications("First summary")
        runCurrent()
        assertEquals(2, f.vm.ui.value.messages.size)
        f.accessEpoch.value++
        runCurrent()
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertEquals(1, f.cardClears)
        val pending = CompletableDeferred<AssistantReply>()
        f.answer = { withContext(NonCancellable) { pending.await() } }
        f.vm.summarizeNotifications("Second summary")
        runCurrent()
        f.accessEpoch.value++
        runCurrent()
        pending.complete(AssistantReply("late private content", emptyList()))
        runCurrent()
        assertTrue(f.vm.ui.value.messages.isEmpty())
        assertEquals(listOf("answer"), f.displayed)
        assertEquals(listOf("answer"), f.spoken)
        f.vm.leaveScreen()
    }

    private data class Request(val history: List<AssistantMessage>, val notificationsOnly: Boolean, val packageName: String?)

    private class Fixture(scope: TestScope) {
        val notifications = MutableStateFlow(BridgeSettings(
            aiNotificationsEnabled = true, aiNotificationPackages = setOf("com.example.chat")))
        val accessEpoch = MutableStateFlow(0L)
        val requests = mutableListOf<Request>()
        val displayed = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val executedTools = mutableListOf<String>()
        var captures = 0
        var releases = 0
        var cardClears = 0
        val turns = mutableListOf<Boolean>()
        var dictationResult: ((String) -> Unit)? = null
        var answer: suspend (AssistantTools) -> AssistantReply = { AssistantReply("answer", emptyList()) }
        val vm = AssistantViewModel(
            readConfig = { AssistantConfig(endpoint = "https://api.example.com/v1", model = "test", supportsImages = true) },
            notifications = notifications,
            reply = { _, history, _, _, only, tools, pkg -> requests += Request(history, only, pkg); answer(tools) },
            tools = AssistantTools { name, _ -> executedTools += name; AssistantToolResult("ok") },
            capture = { captures++; byteArrayOf(1) },
            enterDevice = {}, leaveDevice = { releases++ },
            displayReply = { displayed += it }, clearReply = { cardClears++ },
            speak = { spoken += it; true }, stopSpeech = {}, closeSpeech = {},
            dictationAvailable = true, startDictation = { result, _ -> dictationResult = result }, stopDictation = {},
            injectedScope = scope.backgroundScope,
            beginTurn = { turns += it },
            accessEpoch = accessEpoch,
        )
    }
}

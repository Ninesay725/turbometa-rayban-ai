package com.smartview.glassai.bridge

import org.junit.Assert.*
import org.junit.Test

class AssistantNotificationInboxTest {
    private val enabled = BridgeSettings(aiNotificationsEnabled = true,
        aiNotificationPackages = setOf("app.chat", "app.mail"))

    @Test fun newestTwentyReplaceByKeyAndCapBothTextFields() {
        val inbox = AssistantNotificationInbox()
        (1..25).forEach { inbox.replace("$it", item("$it", it.toLong())) }
        assertEquals((25 downTo 6).map(Int::toString), inbox.notifications.value.map { it.notificationKey })
        inbox.replace("25", item("25", 30).copy(title = "T".repeat(500), text = "B".repeat(5_000)))
        assertEquals(20, inbox.notifications.value.size)
        assertEquals(256, inbox.notifications.value.first().title.length)
        assertEquals(2_000, inbox.notifications.value.first().text.length)
        inbox.replace("25", null) // Redacted/malformed/summary replacement must discard old details.
        assertEquals("24", inbox.notifications.value.first().notificationKey)
        assertEquals(19, inbox.notifications.value.size) // No evicted history comes back.
    }

    @Test fun narrowerSettingsAndRemovalDiscardDetailsPermanently() {
        val inbox = AssistantNotificationInbox()
        inbox.replace("chat", item("chat", 1))
        inbox.replace("mail", item("mail", 2).copy(packageName = "app.mail"))
        inbox.reconcile(enabled.copy(aiNotificationPackages = setOf("app.chat")), true, true)
        assertEquals(listOf("chat"), inbox.forAssistant(enabled, true, true).map { it.notificationKey })
        assertEquals(emptyList<AssistantNotification>(), inbox.forAssistant(enabled, true, true, "app.mail"))
        inbox.replace("chat", null)
        assertTrue(inbox.notifications.value.isEmpty())
    }

    @Test fun disableRevokeAndDisconnectClearBeforeThrowingSafeErrors() {
        val cases = listOf(Triple(enabled.copy(aiNotificationsEnabled = false), true, true),
            Triple(enabled, false, true), Triple(enabled, true, false),
            Triple(enabled.copy(aiNotificationPackages = emptySet()), true, true))
        cases.forEach { (settings, access, connected) ->
            val inbox = AssistantNotificationInbox()
            inbox.replace("sensitive-key", item("sensitive-key", 1).copy(text = "PRIVATE BODY"))
            val error = assertThrows(IllegalStateException::class.java) {
                inbox.forAssistant(settings, access, connected)
            }
            assertFalse(error.toString().contains("PRIVATE BODY"))
            assertFalse(error.toString().contains("sensitive-key"))
            assertNull(error.cause)
            assertTrue(inbox.notifications.value.isEmpty())
            assertTrue(inbox.forAssistant(enabled, true, true).isEmpty())
        }
    }

    @Test fun exactPackageAuthorizationIsIndependentOfWeChatDisplayAndMusic() {
        val inbox = AssistantNotificationInbox()
        inbox.replace("chat", item("chat", 1))
        assertEquals(1, inbox.forAssistant(enabled, true, true, "app.chat").size)
        val error = assertThrows(IllegalStateException::class.java) {
            inbox.forAssistant(enabled, true, true, "app.chat.sensitive")
        }
        assertFalse(error.toString().contains("app.chat.sensitive"))
        assertEquals(1, inbox.notifications.value.size)
    }

    @Test fun permissionAndConnectionRoundTripsInvalidateCopiedWorkEvenWithAnEmptyInbox() {
        val inbox = AssistantNotificationInbox()
        inbox.observeAccess(hasAccess = true, connected = true, locked = false)
        val capturedEpoch = inbox.accessEpoch.value
        inbox.observeAccess(hasAccess = false, connected = true, locked = false)
        inbox.observeAccess(hasAccess = true, connected = true, locked = false)
        assertEquals(capturedEpoch + 1, inbox.accessEpoch.value)
        inbox.observeAccess(hasAccess = false, connected = false, locked = false)
        inbox.observeAccess(hasAccess = true, connected = true, locked = false)
        assertEquals(capturedEpoch + 2, inbox.accessEpoch.value)
        assertTrue(inbox.notifications.value.isEmpty())
    }

    @Test fun lockDetectedByLaterReadClearsContentsAndStableDeniedReadsDoNotKeepAdvancingEpoch() {
        val inbox = AssistantNotificationInbox()
        inbox.observeAccess(hasAccess = true, connected = true, locked = false)
        inbox.replace("chat", item("chat", 1))
        inbox.observeAccess(hasAccess = true, connected = true, locked = true)
        assertEquals(1L, inbox.accessEpoch.value)
        assertTrue(inbox.notifications.value.isEmpty())
        inbox.observeAccess(hasAccess = true, connected = true, locked = true)
        assertEquals(1L, inbox.accessEpoch.value)
        inbox.observeAccess(hasAccess = false, connected = true, locked = true)
        inbox.observeAccess(hasAccess = false, connected = true, locked = true)
        assertEquals(2L, inbox.accessEpoch.value)
    }

    @Test fun explicitLockAndRetirementSignalsAdvanceEpochWithoutRetainedContent() {
        val inbox = AssistantNotificationInbox()
        inbox.observeAccess(hasAccess = true, connected = true, locked = false)
        inbox.onDeviceLocked()
        assertEquals(1L, inbox.accessEpoch.value)
        inbox.observeAccess(hasAccess = true, connected = true, locked = true)
        assertEquals(1L, inbox.accessEpoch.value) // A read after the lock callback is not another loss.
        inbox.invalidateAccess() // Connected-listener replacement, even while the inbox is empty.
        assertEquals(2L, inbox.accessEpoch.value)
        inbox.replace("chat", item("chat", 1))
        inbox.replace("chat", null)
        assertEquals(2L, inbox.accessEpoch.value) // Ordinary notification traffic is not consent loss.
    }

    @Test fun privacyEvictionInvalidatesCopiedWorkOnceAndRetainsUnrelatedEntries() {
        val inbox = AssistantNotificationInbox()
        inbox.replace("chat", item("chat", 1))
        inbox.replace("other", item("other", 2))
        val copiedEpoch = inbox.accessEpoch.value
        inbox.replace("chat", null, privacyRemoval = true)
        assertEquals(copiedEpoch + 1, inbox.accessEpoch.value)
        assertEquals(listOf("other"), inbox.notifications.value.map { it.notificationKey })
        inbox.replace("chat", null, privacyRemoval = true)
        inbox.replace("never-readable", null, privacyRemoval = true)
        assertEquals(copiedEpoch + 1, inbox.accessEpoch.value)
        inbox.replace("other", null) // Ordinary removal retains the parent's summary policy.
        assertEquals(copiedEpoch + 1, inbox.accessEpoch.value)
    }

    @Test fun unreadablePostedReplacementRemembersReadabilityAfterErasingBeforeParse() {
        val inbox = AssistantNotificationInbox()
        inbox.replace("chat", item("chat", 1))
        inbox.replacePosted("chat") {
            assertTrue(inbox.notifications.value.isEmpty())
            null // Hidden/redacted/malformed replacement.
        }
        assertEquals(1L, inbox.accessEpoch.value)
        assertTrue(inbox.notifications.value.isEmpty())
        inbox.replacePosted("chat") { null }
        inbox.replacePosted("new-hidden-key") { null }
        assertEquals(1L, inbox.accessEpoch.value)
    }

    @Test fun readableReplacementAndSummaryDeduplicationDoNotInvalidateCopiedWork() {
        val inbox = AssistantNotificationInbox()
        inbox.replace("chat", item("chat", 1))
        inbox.replacePosted("chat") {
            assertTrue(inbox.notifications.value.isEmpty())
            item("chat", 2).copy(text = "Updated")
        }
        assertEquals("Updated", inbox.notifications.value.single().text)
        assertEquals(0L, inbox.accessEpoch.value)
        inbox.replace("chat", null) // Visible group-summary deduplication, not a privacy rejection.
        assertTrue(inbox.notifications.value.isEmpty())
        assertEquals(0L, inbox.accessEpoch.value)
    }

    @Test fun unexpectedReadFailureDropsContentsAndSignalsPrivacyWithoutExposingTheError() {
        val inbox = AssistantNotificationInbox()
        inbox.replace("chat", item("chat", 1))
        inbox.replacePosted("chat") { throw IllegalStateException("PRIVATE EXCEPTION CONTENT") }
        assertTrue(inbox.notifications.value.isEmpty())
        assertEquals(1L, inbox.accessEpoch.value)
    }

    private fun item(key: String, time: Long) = AssistantNotification(key, "app.chat", "Title", "Body", time)
}

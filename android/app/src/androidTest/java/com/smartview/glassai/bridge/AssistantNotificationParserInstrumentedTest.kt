package com.smartview.glassai.bridge

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform notifications/bundles; no notifications are posted and no access grant is needed. */
@RunWith(AndroidJUnit4::class)
class AssistantNotificationParserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val allowed = setOf("app.chat")

    @Test fun rejectedPackagesSummariesAndPrivateContentAreNeverRead() {
        val unreadable = object : CharSequence by "PRIVATE" {
            override fun toString(): String = throw AssertionError("Rejected notification content was read")
        }
        val fixtures = listOf(
            Triple(posted(notification(text = unreadable), "app.chat.other"), false, allowed),
            Triple(posted(notification(text = unreadable)), false, emptySet()),
            Triple(posted(notification(text = unreadable).apply {
                flags = flags or Notification.FLAG_GROUP_SUMMARY
            }), false, allowed),
            Triple(posted(notification(text = unreadable, visibility = Notification.VISIBILITY_SECRET)), false, allowed),
            Triple(posted(notification(text = unreadable, visibility = Notification.VISIBILITY_SECRET)), true, allowed),
            Triple(posted(notification(text = unreadable, visibility = Notification.VISIBILITY_PRIVATE)), true, allowed),
        )
        fixtures.forEach { (sbn, locked, packages) ->
            assertNull(AssistantNotificationParser.parse(sbn, packages, locked))
        }
    }

    @Test fun publicContentWhileLockedAndPrivateContentWhileUnlockedPreserveThePostedSnapshot() {
        listOf(Notification.VISIBILITY_PUBLIC to true, Notification.VISIBILITY_PRIVATE to false).forEach { (visibility, locked) ->
            val sbn = posted(notification("Title", "Short", "Full body", visibility))
            assertEquals(AssistantNotification(sbn.key, "app.chat", "Title", "Full body", 1_000),
                AssistantNotificationParser.parse(sbn, allowed, locked))
        }
    }

    @Test fun userOrDevicePolicyVisibilityCanNarrowButCannotExposePrivateContent() {
        val unreadable = object : CharSequence by "PRIVATE" {
            override fun toString(): String = throw AssertionError("Policy-hidden content was read")
        }
        val publicNotification = posted(notification(text = unreadable))
        assertNull(AssistantNotificationParser.parse(publicNotification, allowed, false, Notification.VISIBILITY_SECRET))
        assertNull(AssistantNotificationParser.parse(publicNotification, allowed, true, Notification.VISIBILITY_PRIVATE))
        val privateNotification = posted(notification(text = unreadable, visibility = Notification.VISIBILITY_PRIVATE))
        assertNull(AssistantNotificationParser.parse(privateNotification, allowed, true, Notification.VISIBILITY_PUBLIC))
    }

    @Test fun titleAndBodyAreCappedAndBlankBigTextFallsBackToBody() {
        val huge = posted(notification("T".repeat(1_000), "B".repeat(10_000)))
        val result = AssistantNotificationParser.parse(huge, allowed, false)!!
        assertEquals("T".repeat(256), result.title)
        assertEquals("B".repeat(2_000), result.text)
        val fallback = posted(notification(text = "Preview", bigText = " \n"))
        assertEquals("Preview", AssistantNotificationParser.parse(fallback, allowed, false)!!.text)
    }

    @Test fun messagingStyleFallbackUsesOnlyLatestCurrentMessageAndIgnoresHistory() {
        val person = Person.Builder().setName("Me").build()
        val style = Notification.MessagingStyle(person)
            .addMessage("Old", 1, person).addMessage("New", 2, person)
            .addHistoricMessage(Notification.MessagingStyle.Message("HISTORIC", 100, person))
        val notification = builder().setStyle(style).build().apply {
            extras.remove(Notification.EXTRA_TEXT); extras.remove(Notification.EXTRA_BIG_TEXT)
        }
        assertEquals("New", AssistantNotificationParser.parse(posted(notification), allowed, false)!!.text)
    }

    @Test fun unreadableOrRedactedReplacementCannotRetainPreviousDetailsOrReadPublicVersion() {
        val inbox = AssistantNotificationInbox()
        val initial = posted(notification(text = "PRIVATE BODY"))
        inbox.replace(initial.key, AssistantNotificationParser.parse(initial, allowed, false))
        val unreadable = object : CharSequence by "PRIVATE" {
            override fun toString(): String = throw IllegalStateException("PRIVATE EXCEPTION CONTENT")
        }
        val replacement = posted(notification(text = unreadable))
        inbox.replacePosted(replacement.key) { AssistantNotificationParser.parse(replacement, allowed, false) }
        assertTrue(inbox.notifications.value.isEmpty())
        assertEquals(1L, inbox.accessEpoch.value)
        inbox.replacePosted(replacement.key) { AssistantNotificationParser.parse(replacement, allowed, false) }
        assertEquals(1L, inbox.accessEpoch.value) // Already unreadable; no new copy was invalidated.
        val redacted = notification(title = "Hidden").apply {
            publicVersion = notification(text = "Do not treat as private body")
            extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(Bundle()))
        }
        inbox.replace(initial.key, AssistantNotificationParser.parse(initial, allowed, false))
        inbox.replacePosted(initial.key) { AssistantNotificationParser.parse(posted(redacted), allowed, false) }
        assertTrue(inbox.notifications.value.isEmpty())
        assertEquals(2L, inbox.accessEpoch.value)
    }

    private fun builder() = Notification.Builder(context, "assistant-parser-fixture")
        .setSmallIcon(android.R.drawable.ic_dialog_info).setVisibility(Notification.VISIBILITY_PUBLIC)

    private fun notification(title: CharSequence? = null, text: CharSequence? = null,
        bigText: CharSequence? = null, visibility: Int = Notification.VISIBILITY_PUBLIC): Notification =
        builder().setVisibility(visibility).build().apply {
            extras = Bundle().apply {
                putCharSequence(Notification.EXTRA_TITLE, title)
                putCharSequence(Notification.EXTRA_TEXT, text)
                putCharSequence(Notification.EXTRA_BIG_TEXT, bigText)
            }
        }

    @Suppress("DEPRECATION")
    private fun posted(notification: Notification, pkg: String = "app.chat") = StatusBarNotification(
        pkg, pkg, 1, "assistant-fixture", Process.myUid(), 0, 0,
        notification, Process.myUserHandle(), 1_000,
    )
}

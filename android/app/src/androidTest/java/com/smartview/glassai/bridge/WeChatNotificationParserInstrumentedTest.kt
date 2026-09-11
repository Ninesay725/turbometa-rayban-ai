package com.smartview.glassai.bridge

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform bundles/builders only; nothing is posted and notification access is not needed. */
@RunWith(AndroidJUnit4::class)
class WeChatNotificationParserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nonWeChatPackageIsRejectedBeforeReadingPreviewContent() {
        val unreadable = object : CharSequence by "private" {
            override fun toString(): String = throw AssertionError("Non-WeChat preview was read")
        }
        val notification = plainNotification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, unreadable)
            extras.putCharSequence(Notification.EXTRA_TEXT, unreadable)
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, unreadable)
        }

        assertEquals(emptyList<BridgeMessage>(), WeChatNotificationParser.parse(posted(notification, "other.app")))
        assertEquals(emptyList<BridgeMessage>(), WeChatNotificationParser.parse(posted(notification, "com.tencent.mm.other")))
    }

    @Test
    fun plainPreviewPreservesTitleTextAndPostTime() {
        val sbn = posted(plainNotification(SpannableString("Alice"), SpannableString("See you at 6")))

        assertEquals(
            listOf(BridgeMessage(sbn.key, "Alice", "See you at 6", 1_000)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun bigTextTakesPrecedenceOverTheShortPreviewWithoutTruncation() {
        val sbn = posted(plainNotification("Family", "Short", "First line\n完整的消息"))

        assertEquals(
            listOf(BridgeMessage(sbn.key, "Family", "First line\n完整的消息", 1_000)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun blankBigTextFallsBackToTextAndMissingSenderStaysEmpty() {
        val sbn = posted(plainNotification(text = "Preview", bigText = " \n"))

        assertEquals(
            listOf(BridgeMessage(sbn.key, "", "Preview", 1_000)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun missingOrRedactedBodyDoesNotInventAMessageFromTheTitle() {
        val notifications = listOf(
            plainNotification(),
            plainNotification(title = "WeChat"),
            plainNotification(title = "Alice", text = " \n", bigText = "\t"),
        )

        notifications.forEach { notification ->
            assertEquals(emptyList<BridgeMessage>(), WeChatNotificationParser.parse(posted(notification)))
        }
    }

    @Test
    fun groupedSummaryPassesThroughWithoutInventingIndividualMessages() {
        val sbn = posted(plainNotification("微信", "[3条] 你收到3条新消息").apply {
            flags = flags or Notification.FLAG_GROUP_SUMMARY
        })

        assertEquals(
            listOf(BridgeMessage(sbn.key, "微信", "[3条] 你收到3条新消息", 1_000)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun messagingStylePrefersNewestThreeMessagesAndIgnoresHistoricContent() {
        val style = Notification.MessagingStyle(person("Me"))
            .setConversationTitle("Family")
            .setGroupConversation(true)
            .addMessage("Third", 30, person("Carol"))
            .addMessage("First", 10, person("Alice"))
            .addMessage("Fourth", 40, person("Dan"))
            .addMessage("Second", 20, person("Bob"))
            .addMessage("Fourth", 40, person("Dan"))
            .addHistoricMessage(Notification.MessagingStyle.Message("History", 999, person("Old sender")))
        val sbn = posted(styledNotification(style).apply {
            extras.putCharSequence(Notification.EXTRA_TEXT, "Summary instead of details")
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Long summary instead of details")
        })

        assertEquals(
            listOf(
                BridgeMessage(sbn.key, "Dan", "Fourth", 40),
                BridgeMessage(sbn.key, "Carol", "Third", 30),
                BridgeMessage(sbn.key, "Bob", "Second", 20),
            ),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun blankMessagingStyleEntriesAreSkippedAndMissingSenderUsesNotificationTitle() {
        val style = Notification.MessagingStyle(person("Me"))
            .addMessage("Visible", 10, null as Person?)
            .addMessage(" \n", 20, person("Alice"))
        val sbn = posted(styledNotification(style).apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "Conversation")
        })

        assertEquals(
            listOf(BridgeMessage(sbn.key, "Conversation", "Visible", 10)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun emptyMessagingStyleFallsBackToAnAvailableSummary() {
        val sbn = posted(styledNotification(Notification.MessagingStyle(person("Me"))).apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "微信")
            extras.putCharSequence(Notification.EXTRA_TEXT, "[2条] 新消息")
        })

        assertEquals(
            listOf(BridgeMessage(sbn.key, "微信", "[2条] 新消息", 1_000)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun malformedMessageBundlesStillAllowThePublicTextFallback() {
        val sbn = posted(plainNotification("WeChat", "Summary").apply {
            extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(Bundle()))
        })

        assertEquals(
            listOf(BridgeMessage(sbn.key, "WeChat", "Summary", 1_000)),
            WeChatNotificationParser.parse(sbn),
        )
    }

    @Test
    fun unreadableWeChatContentReturnsEmptyWithoutPropagatingItsException() {
        val unreadable = object : CharSequence by "unreadable" {
            override fun toString(): String = throw IllegalStateException("Synthetic unreadable content")
        }
        val sbn = posted(plainNotification("WeChat", unreadable))

        assertEquals(emptyList<BridgeMessage>(), WeChatNotificationParser.parse(sbn))
    }

    @Test
    fun repostedMessagingStyleBundlesDeduplicateInTheInboxAndRemovalClearsTheirKey() {
        val inbox = WeChatInbox()
        val first = posted(styledNotification(Notification.MessagingStyle(person("Me"))
            .addMessage("First", 10, person("Alice"))))
        val updated = posted(styledNotification(Notification.MessagingStyle(person("Me"))
            .addMessage("First", 10, person("Alice"))
            .addMessage("Second", 20, person("Alice"))), postTime = 2_000)
        val another = posted(plainNotification("Bob", "Other conversation"), id = 2, postTime = 30)

        inbox.update(WeChatNotificationParser.parse(first))
        inbox.update(WeChatNotificationParser.parse(another))
        inbox.update(WeChatNotificationParser.parse(updated))
        assertEquals(
            listOf(
                BridgeMessage(another.key, "Bob", "Other conversation", 30),
                BridgeMessage(first.key, "Alice", "Second", 20),
                BridgeMessage(first.key, "Alice", "First", 10),
            ),
            inbox.update(WeChatNotificationParser.parse(updated)),
        )
        assertEquals(
            listOf(BridgeMessage(another.key, "Bob", "Other conversation", 30)),
            inbox.remove(first.key),
        )
    }

    private fun person(name: String): Person = Person.Builder().setName(name).build()

    private fun plainNotification(
        title: CharSequence? = null,
        text: CharSequence? = null,
        bigText: CharSequence? = null,
    ): Notification = builder().build().apply {
        extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, title)
            putCharSequence(Notification.EXTRA_TEXT, text)
            putCharSequence(Notification.EXTRA_BIG_TEXT, bigText)
        }
    }

    private fun styledNotification(style: Notification.MessagingStyle): Notification =
        builder().setStyle(style).build()

    private fun builder() = Notification.Builder(context, "phase-e-parser-fixture")
        .setSmallIcon(android.R.drawable.ic_dialog_info)

    @Suppress("DEPRECATION") // Public fixture constructor; no system-only overload or notification post.
    private fun posted(
        notification: Notification,
        packageName: String = "com.tencent.mm",
        id: Int = 1,
        postTime: Long = 1_000,
    ) = StatusBarNotification(
        packageName, packageName, id, "parser-fixture", Process.myUid(), 0, 0,
        notification, Process.myUserHandle(), postTime,
    )
}

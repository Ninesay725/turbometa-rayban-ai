package com.smartview.glassai.bridge

import android.app.Notification
import android.service.notification.StatusBarNotification

/** Called by the notification listener after its opt-in check; never stores or logs content. */
object WeChatNotificationParser {
    @Suppress("DEPRECATION") // The untyped Bundle getter is public on minSdk 31.
    fun parse(sbn: StatusBarNotification): List<BridgeMessage> {
        if (sbn.packageName != "com.tencent.mm") return emptyList()

        return try {
            val extras = sbn.notification.extras ?: return emptyList()
            val title = extras.getCharSequence(Notification.EXTRA_TITLE).nonBlankText().orEmpty()
            // Public since API 30; do not read historic messages or attachment contents.
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES),
            ).mapNotNull { message ->
                val text = message.text.nonBlankText() ?: return@mapNotNull null
                BridgeMessage(
                    notificationKey = sbn.key,
                    sender = message.senderPerson?.name.nonBlankText() ?: title,
                    text = text,
                    timestamp = message.timestamp,
                )
            }.distinct().sortedByDescending { it.timestamp }.take(3)

            if (messages.isNotEmpty()) {
                messages
            } else {
                val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).nonBlankText()
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT).nonBlankText()
                    ?: return emptyList()
                listOf(BridgeMessage(sbn.key, title, text, sbn.postTime))
            }
        } catch (_: RuntimeException) {
            // Unreadable external bundles have the same contract as redacted content.
            emptyList()
        }
    }

    private fun CharSequence?.nonBlankText(): String? = this?.toString()?.takeIf { it.isNotBlank() }
}

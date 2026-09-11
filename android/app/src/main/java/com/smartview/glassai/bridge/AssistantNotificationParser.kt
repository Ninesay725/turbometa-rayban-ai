package com.smartview.glassai.bridge

import android.app.Notification
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification

/** Content is accessed only after exact package, visibility and summary checks. */
object AssistantNotificationParser {
    @Suppress("DEPRECATION") // Bundle getter is public on minSdk 31.
    fun parse(sbn: StatusBarNotification, allowedPackages: Set<String>, deviceLocked: Boolean,
        visibilityOverride: Int = Ranking.VISIBILITY_NO_OVERRIDE): AssistantNotification? {
        if (sbn.packageName !in allowedPackages) return null
        return try {
            val notification = sbn.notification
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            if (notification.visibility == Notification.VISIBILITY_SECRET ||
                (deviceLocked && notification.visibility != Notification.VISIBILITY_PUBLIC)) return null
            if (hiddenByPolicy(visibilityOverride, deviceLocked)) return null
            // Never read publicVersion, historic messages, attachments, intents or remote views.
            val extras = notification.extras ?: return null
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).boundedText(ASSISTANT_NOTIFICATION_TEXT_LIMIT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT).boundedText(ASSISTANT_NOTIFICATION_TEXT_LIMIT)
                ?: Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.takeLast(20)?.toTypedArray(),
                ).sortedByDescending { it.timestamp }.firstNotNullOfOrNull {
                    it.text.boundedText(ASSISTANT_NOTIFICATION_TEXT_LIMIT)
                } ?: return null
            AssistantNotification(sbn.key, sbn.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE).boundedText(ASSISTANT_NOTIFICATION_TITLE_LIMIT).orEmpty(),
                text, sbn.postTime)
        } catch (_: RuntimeException) {
            // External bundles/CharSequences may throw with content in the message. Drop it all.
            null
        }
    }

    internal fun hiddenByPolicy(visibility: Int, deviceLocked: Boolean): Boolean =
        visibility != Ranking.VISIBILITY_NO_OVERRIDE &&
            (visibility == Notification.VISIBILITY_SECRET || (deviceLocked && visibility != Notification.VISIBILITY_PUBLIC))

    private fun CharSequence?.boundedText(limit: Int): String? {
        if (this == null) return null
        return (if (length > limit) subSequence(0, limit) else this).toString()
            .take(limit).takeIf { it.isNotBlank() }
    }
}

package com.smartview.glassai.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartview.glassai.glasses.MusicController
import com.smartview.glassai.services.NotificationBridgeService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test-owned notifications only. Parent emulator runner supplies notification/listener grants. */
@RunWith(AndroidJUnit4::class)
class AssistantNotificationBridgeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext

    @Test fun postedDataIsIndependentOfWeChatAndEvictedSynchronouslyBySettingsAndRemoval(): Unit = runBlocking {
        assumeTrue("Requires listener access", NotificationBridgeRuntime.hasNotificationAccess(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Requires notification posting permission", manager.areNotificationsEnabled())
        val preferences = BridgePreferences.getInstance(context)
        val saved = preferences.settings.value
        val stored = context.getSharedPreferences("notification_bridge", 0)
        val savedAiSwitch = stored.all["ai_notifications_enabled"]
        val savedAiPackages = stored.getStringSet("ai_notification_packages", null)?.toSet()
        val savedWechat = stored.all["wechat_enabled"]
        val channel = "bridge-ai-fixture-${java.util.UUID.randomUUID()}"
        val tag = "bridge-ai-fixture"
        fun post(body: String?, summary: Boolean = false) {
            manager.notify(tag, 1, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Bridge AI fixture")
                .setContentText(body).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setGroup("bridge-ai-fixture").setGroupSummary(summary).build())
        }
        fun safeDiagnostics(): String {
            var result = "unavailable"
            instrumentation.runOnMainSync {
                val settings = preferences.settings.value
                val platform = try {
                    val fixture = manager.activeNotifications.firstOrNull {
                        it.tag == tag && it.id == 1 && it.notification.channelId == channel
                    }
                    "postingAllowed=${manager.areNotificationsEnabled()}, " +
                        "channelImportance=${manager.getNotificationChannel(channel)?.importance}, " +
                        "fixtureActive=${fixture != null}, " +
                        "fixtureSummary=${fixture?.notification?.flags?.and(Notification.FLAG_GROUP_SUMMARY)?.let { it != 0 }}"
                } catch (_: RuntimeException) { "platformState=unavailable" }
                // Only booleans/counts/generations, never notifications, keys, extras or exceptions.
                result = "connected=${NotificationBridgeRuntime.listenerConnected.value}, " +
                    "access=${NotificationBridgeRuntime.hasNotificationAccess(context)}, " +
                    "locked=${NotificationBridgeRuntime.isDeviceLocked(context)}, " +
                    "aiEnabled=${settings.aiNotificationsEnabled}, " +
                    "appAllowed=${context.packageName in settings.aiNotificationPackages}, " +
                    "cachedCount=${NotificationBridgeRuntime.assistantNotifications.value.size}, " +
                    "consentRevision=${settings.aiConsentRevision}, " +
                    "accessEpoch=${NotificationBridgeRuntime.assistantAccessEpoch.value}, $platform"
            }
            return result
        }
        suspend fun awaitStage(stage: String, block: suspend () -> Unit) {
            val completed = withTimeoutOrNull(10_000) { block(); true } == true
            if (!completed) throw AssertionError("Timed out after 10000 ms at '$stage': ${safeDiagnostics()}")
        }
        suspend fun awaitBody(stage: String, body: String) = awaitStage(stage) {
            NotificationBridgeRuntime.assistantNotifications.first { list ->
                list.any { it.packageName == context.packageName && it.text == body }
            }
        }
        suspend fun awaitEmpty(stage: String) = awaitStage(stage) {
            NotificationBridgeRuntime.assistantNotifications.first { it.isEmpty() }
        }
        try {
            manager.createNotificationChannel(NotificationChannel(channel, "Bridge AI fixture", NotificationManager.IMPORTANCE_LOW))
            instrumentation.runOnMainSync {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationBridgeService::class.java))
                preferences.setWechatEnabled(false)
                preferences.setAiNotificationPackages(setOf(context.packageName))
                preferences.setAiNotificationsEnabled(true)
            }
            awaitStage("listener connection") { NotificationBridgeRuntime.listenerConnected.first { it } }
            post("First fixture")
            awaitBody("first delivery", "First fixture")
            instrumentation.runOnMainSync {
                assertEquals("First fixture", NotificationBridgeRuntime.notificationsForAssistant(context.packageName).single().text)
                preferences.setAiNotificationsEnabled(false)
                assertThrows(IllegalStateException::class.java) { NotificationBridgeRuntime.notificationsForAssistant() }
                preferences.setAiNotificationsEnabled(true)
                assertTrue(NotificationBridgeRuntime.assistantNotifications.value.isEmpty())
                assertTrue(NotificationBridgeRuntime.notificationsForAssistant().isEmpty())
            }
            post("Second fixture"); awaitBody("delivery after off/on", "Second fixture")
            instrumentation.runOnMainSync {
                preferences.setAiNotificationPackages(emptySet())
                preferences.setAiNotificationPackages(setOf(context.packageName))
                assertTrue(NotificationBridgeRuntime.assistantNotifications.value.isEmpty())
            }
            post("Third fixture"); awaitBody("delivery after allowlist round trip", "Third fixture")
            val readableEpoch = NotificationBridgeRuntime.assistantAccessEpoch.value
            post("Readable update"); awaitBody("ordinary readable replacement", "Readable update")
            instrumentation.runOnMainSync {
                assertEquals(readableEpoch, NotificationBridgeRuntime.assistantAccessEpoch.value)
                val key = NotificationBridgeRuntime.notificationsForAssistant(context.packageName).single().notificationKey
                // Use the connected publisher without adding a production test accessor. Exercise
                // the same runtime ranking route as the service; no OS privacy setting is changed.
                val controller = NotificationBridgeRuntime::class.java.getDeclaredField("listener")
                    .apply { isAccessible = true }.get(NotificationBridgeRuntime) as MusicController
                NotificationBridgeRuntime.updateAssistantNotificationVisibility(controller, key,
                    Notification.VISIBILITY_PRIVATE, deviceLocked = false)
                assertEquals(readableEpoch, NotificationBridgeRuntime.assistantAccessEpoch.value)
                assertEquals(1, NotificationBridgeRuntime.notificationsForAssistant(context.packageName).size)
                NotificationBridgeRuntime.updateAssistantNotificationVisibility(controller, key,
                    null, deviceLocked = false)
                assertTrue(NotificationBridgeRuntime.notificationsForAssistant(context.packageName).isEmpty())
                assertEquals(readableEpoch, NotificationBridgeRuntime.assistantAccessEpoch.value)
                // Reinsert only this test-owned DTO to exercise the next ranking transition.
                NotificationBridgeRuntime.replaceAssistantNotification(controller, key,
                    AssistantNotification(key, context.packageName, "Fixture", "Readable update", 1_000))
                NotificationBridgeRuntime.updateAssistantNotificationVisibility(controller, key,
                    Notification.VISIBILITY_SECRET, deviceLocked = false)
                assertEquals(readableEpoch + 1, NotificationBridgeRuntime.assistantAccessEpoch.value)
                assertTrue(NotificationBridgeRuntime.notificationsForAssistant(context.packageName).isEmpty())
                NotificationBridgeRuntime.updateAssistantNotificationVisibility(controller, key,
                    Notification.VISIBILITY_SECRET, deviceLocked = false)
                assertEquals(readableEpoch + 1, NotificationBridgeRuntime.assistantAccessEpoch.value)
            }
            post("After ranking"); awaitBody("delivery after privacy ranking eviction", "After ranking")
            val beforeUnreadable = NotificationBridgeRuntime.assistantAccessEpoch.value
            post(null)
            awaitEmpty("unreadable replacement eviction")
            awaitStage("unreadable replacement epoch") {
                NotificationBridgeRuntime.assistantAccessEpoch.first { it != beforeUnreadable }
            }
            assertEquals(beforeUnreadable + 1, NotificationBridgeRuntime.assistantAccessEpoch.value)
            post("After unreadable"); awaitBody("delivery after unreadable replacement", "After unreadable")
            val beforeRemoval = NotificationBridgeRuntime.assistantAccessEpoch.value
            manager.cancel(tag, 1)
            awaitEmpty("removal eviction")
            instrumentation.runOnMainSync {
                assertEquals(beforeRemoval, NotificationBridgeRuntime.assistantAccessEpoch.value)
            }
            post("Fourth fixture"); awaitBody("delivery after removal", "Fourth fixture")
            // Keep summary replacement last: Android can cancel an enqueued same-group child
            // when its previous notification was the group summary, before listener delivery.
            post("Summary fixture", summary = true)
            awaitEmpty("summary replacement eviction")
            instrumentation.runOnMainSync {
                assertEquals(beforeRemoval, NotificationBridgeRuntime.assistantAccessEpoch.value)
            }
        } finally {
            manager.cancel(tag, 1); manager.deleteNotificationChannel(channel)
            instrumentation.runOnMainSync {
                preferences.setAiNotificationsEnabled(false)
                preferences.setAiNotificationPackages(saved.aiNotificationPackages)
                preferences.setAiNotificationsEnabled(saved.aiNotificationsEnabled)
                preferences.setWechatEnabled(saved.wechatEnabled)
            }
            val restore = stored.edit()
            if (savedAiSwitch == null) restore.remove("ai_notifications_enabled")
            else restore.putBoolean("ai_notifications_enabled", savedAiSwitch as Boolean)
            if (savedAiPackages == null) restore.remove("ai_notification_packages")
            else restore.putStringSet("ai_notification_packages", savedAiPackages)
            if (savedWechat == null) restore.remove("wechat_enabled")
            else restore.putBoolean("wechat_enabled", savedWechat as Boolean)
            assertTrue(restore.commit())
        }
    }
}

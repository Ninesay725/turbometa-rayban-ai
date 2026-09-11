package com.smartview.glassai.bridge

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BridgeAiPreferencesInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun migrationIsPersistedButExplicitCustomAndEmptySetsStayUnchanged() = isolated { wrapper, prefs ->
        val legacy = setOf("com.netease.cloudmusic", "com.luna.music")
        prefs.edit().putStringSet("media_packages", legacy).commit()
        assertEquals(legacy + "com.tencent.qqmusic", BridgePreferences(wrapper).settings.value.mediaPackages)
        assertEquals(legacy + "com.tencent.qqmusic", prefs.getStringSet("media_packages", null))
        val settings = BridgePreferences(wrapper)
        settings.setMediaPackages(legacy)
        assertEquals(legacy, BridgePreferences(wrapper).settings.value.mediaPackages)
        settings.setMediaPackages(emptySet())
        assertEquals(emptySet<String>(), BridgePreferences(wrapper).settings.value.mediaPackages)
    }

    @Test fun assistantOptInPersistsOnlySwitchAndValidatedPackagesSeparatelyFromWeChat() = isolated { wrapper, prefs ->
        val preferences = BridgePreferences(wrapper)
        assertFalse(preferences.settings.value.aiNotificationsEnabled)
        assertTrue(preferences.settings.value.aiNotificationPackages.isEmpty())
        instrumentation.runOnMainSync {
            preferences.setAiNotificationPackages(setOf(" app.mail ", "app.chat", "not a package", ""))
            preferences.setAiNotificationsEnabled(true)
        }
        val restored = BridgePreferences(wrapper).settings.value
        assertTrue(restored.aiNotificationsEnabled)
        assertEquals(setOf("app.mail", "app.chat"), restored.aiNotificationPackages)
        assertFalse(restored.wechatEnabled); assertFalse(restored.musicEnabled)
        assertEquals(setOf("ai_notification_packages", "ai_notifications_enabled"), prefs.all.keys)
    }

    @Test fun consentRoundTripsChangeRevisionButNoOpEditsAndReloadDoNotPersistIt() = isolated { wrapper, prefs ->
        val preferences = BridgePreferences(wrapper)
        instrumentation.runOnMainSync {
            preferences.setAiNotificationsEnabled(false)
            preferences.setAiNotificationPackages(emptySet())
            assertEquals(0L, preferences.settings.value.aiConsentRevision)
            assertTrue(prefs.all.isEmpty())

            preferences.setAiNotificationPackages(setOf("app.chat"))
            preferences.setAiNotificationsEnabled(true)
            val before = preferences.settings.value
            assertEquals(2L, before.aiConsentRevision)
            preferences.setAiNotificationsEnabled(false)
            preferences.setAiNotificationsEnabled(true)
            preferences.setAiNotificationPackages(emptySet())
            preferences.setAiNotificationPackages(setOf("app.chat"))
            val after = preferences.settings.value
            assertEquals(before.aiNotificationsEnabled, after.aiNotificationsEnabled)
            assertEquals(before.aiNotificationPackages, after.aiNotificationPackages)
            assertEquals(6L, after.aiConsentRevision)
            assertNotEquals(before, after) // A conflated collector still sees the consent change.

            preferences.setAiNotificationsEnabled(true)
            preferences.setAiNotificationPackages(setOf(" app.chat ", "invalid package"))
            assertEquals(after, preferences.settings.value)
            assertEquals(after.copy(aiConsentRevision = 0L), BridgePreferences(wrapper).settings.value)
            assertEquals(setOf("ai_notification_packages", "ai_notifications_enabled"), prefs.all.keys)
        }
    }

    private fun isolated(block: (Context, SharedPreferences) -> Unit) {
        val name = "bridge_ai_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = prefs
        }
        try { block(wrapper, prefs) } finally { context.deleteSharedPreferences(name) }
    }
}

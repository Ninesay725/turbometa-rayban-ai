package com.smartview.glassai.services.assistant

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses only a unique test-owned preferences file; never opens the user's assistant credentials. */
@RunWith(AndroidJUnit4::class)
class AssistantSettingsStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "assistant_test_${UUID.randomUUID()}"

    @After fun cleanUp() { context.deleteSharedPreferences(name) }

    @Test fun encryptedSaveRoundTripsAndBlankKeyRemovesStoredSecret() {
        val store = AssistantSettingsStore(context, name)
        val config = AssistantConfig(endpoint = "https://api.example.com/v1/chat/completions", model = "vision-model",
            apiKey = "test-secret-only", supportsImages = true, supportsTools = false, speakReplies = false)
        assertNull(store.save(config))
        assertEquals(config, AssistantSettingsStore(context, name).load())
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        assertFalse(raw.containsKey("api_key"))
        assertTrue(raw.values.none { it.toString().contains("test-secret-only") })
        assertNull(store.save(config.copy(apiKey = "  ")))
        assertEquals("", AssistantSettingsStore(context, name).load().apiKey)
    }

    @Test fun invalidSavePreservesPreviouslySavedConfigurationAndKey() {
        val store = AssistantSettingsStore(context, name)
        val config = AssistantConfig(endpoint = "https://api.example.com/v1", model = "text-model", apiKey = "existing-test-key")
        assertNull(store.save(config))
        assertEquals(AssistantSettingsError.INVALID_CONFIG,
            store.save(config.copy(endpoint = "http://192.168.1.2:8080/v1", apiKey = "")))
        assertEquals(config, AssistantSettingsStore(context, name).load())
        assertNull(store.save(config.copy(endpoint = "http://192.168.1.2:8080/v1", allowInsecureHttp = true)))
    }
}

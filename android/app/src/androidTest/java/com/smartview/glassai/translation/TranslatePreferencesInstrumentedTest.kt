package com.smartview.glassai.translation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SharedPreferences in a unique test-owned file; never changes the process singleton. */
@RunWith(AndroidJUnit4::class)
class TranslatePreferencesInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var stored: SharedPreferences
    private lateinit var isolated: Context

    @Before
    fun createIsolatedPreferences() {
        name = "translate_test_${UUID.randomUUID()}"
        stored = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = stored
        }
    }

    @After
    fun removeOnlyTheTestPreferences() {
        context.deleteSharedPreferences(name)
    }

    @Test
    fun emptyStorageUsesOriginalDefaultsWithoutWritingOnRead() {
        assertEquals(TranslateSettings(), TranslatePreferences(isolated).settings.value)
        assertTrue(stored.all.isEmpty())
    }

    @Test
    fun unknownLanguageAndVoiceValuesFallBackWithoutResettingValidSwitches() {
        stored.edit()
            .putString("translate_source_language", "unknown")
            .putString("translate_target_language", "unknown")
            .putString("translate_voice", "unknown")
            .putBoolean("translate_audio_enabled", false)
            .putBoolean("translate_image_enhance", true)
            .putBoolean("translate_use_phone_mic", true)
            .commit()

        assertEquals(TranslateSettings(audioEnabled = false, imageEnabled = true, usePhoneMic = true),
            TranslatePreferences(isolated).settings.value)
    }

    @Test
    fun corruptStoredTypesFallBackIndividuallyWithoutThrowing() {
        stored.edit()
            .putInt("translate_source_language", 42)
            .putBoolean("translate_target_language", true)
            .putInt("translate_voice", 42)
            .putString("translate_audio_enabled", "false")
            .putInt("translate_image_enhance", 1)
            .putString("translate_use_phone_mic", "true")
            .commit()

        assertEquals(TranslateSettings(), TranslatePreferences(isolated).settings.value)
    }

    @Test
    fun loadedCantoneseRepairsAnIncompatibleVoice() {
        stored.edit()
            .putString("translate_source_language", "ja")
            .putString("translate_target_language", "yue")
            .putString("translate_voice", "Dylan")
            .commit()

        assertEquals(TranslateSettings(sourceLanguage = TranslateLanguage.JA,
            targetLanguage = TranslateLanguage.YUE, voice = TranslateVoice.KIKI),
            TranslatePreferences(isolated).settings.value)
    }

    @Test
    fun unsupportedStoredTargetIsRepairedEvenWhenAudioIsOff() {
        stored.edit()
            .putString("translate_source_language", "ar")
            .putString("translate_target_language", "tr")
            .putString("translate_voice", "Kiki")
            .putBoolean("translate_audio_enabled", false)
            .commit()

        assertEquals(TranslateSettings(sourceLanguage = TranslateLanguage.AR, audioEnabled = false),
            TranslatePreferences(isolated).settings.value)
    }

    @Test
    fun updatePersistsOnlyTheSixOriginalKeysWithValidatedProviderValues() {
        val preferences = TranslatePreferences(isolated)
        preferences.update(TranslateSettings(sourceLanguage = TranslateLanguage.HI,
            targetLanguage = TranslateLanguage.YUE, audioEnabled = false, imageEnabled = true,
            usePhoneMic = true))

        assertEquals(mapOf(
            "translate_source_language" to "hi",
            "translate_target_language" to "yue",
            "translate_voice" to "Kiki",
            "translate_audio_enabled" to false,
            "translate_image_enhance" to true,
            "translate_use_phone_mic" to true,
        ), stored.all)
        assertEquals(TranslateSettings(sourceLanguage = TranslateLanguage.HI,
            targetLanguage = TranslateLanguage.YUE, voice = TranslateVoice.KIKI,
            audioEnabled = false, imageEnabled = true, usePhoneMic = true),
            TranslatePreferences(isolated).settings.value)
    }

    @Test
    fun swapPublishesOneCompatibleSnapshotAfterAllItsValuesAreStored(): Unit = runBlocking {
        val preferences = TranslatePreferences(isolated)
        val original = TranslateSettings(sourceLanguage = TranslateLanguage.ZH,
            targetLanguage = TranslateLanguage.YUE, voice = TranslateVoice.KIKI)
        preferences.update(original)
        val observed = mutableListOf<TranslateSettings>()
        val persisted = mutableListOf<Map<String, *>>()
        val collector = launch(Dispatchers.Unconfined) {
            preferences.settings.collect {
                observed += it
                persisted += stored.all
            }
        }
        try {
            preferences.update(original.copy(sourceLanguage = original.targetLanguage,
                targetLanguage = original.sourceLanguage))
            assertEquals(listOf(original, TranslateSettings(sourceLanguage = TranslateLanguage.YUE,
                targetLanguage = TranslateLanguage.ZH, voice = TranslateVoice.CHERRY)), observed)
            assertEquals("yue", persisted.last()["translate_source_language"])
            assertEquals("zh", persisted.last()["translate_target_language"])
            assertEquals("Cherry", persisted.last()["translate_voice"])
        } finally {
            collector.cancelAndJoin()
        }
    }
}

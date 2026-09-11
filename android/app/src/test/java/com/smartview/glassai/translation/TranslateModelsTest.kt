package com.smartview.glassai.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateModelsTest {
    @Test
    fun languageCodesAndAudioTargetsMatchTheApprovedModel() {
        assertEquals(
            listOf("en", "zh", "ja", "ko", "fr", "de", "ru", "es", "pt", "it", "yue",
                "id", "vi", "th", "ar", "hi", "el", "tr"),
            TranslateLanguage.entries.map { it.code },
        )
        assertEquals(
            setOf("en", "zh", "ja", "ko", "fr", "de", "ru", "es", "pt", "it", "yue"),
            TranslateLanguage.entries.filter { it.audioTarget }.map { it.code }.toSet(),
        )
        assertTrue(TranslateLanguage.entries.all { it.label.isNotBlank() })
    }

    @Test
    fun voicesKeepTheirExactCaseSensitiveProviderIds() {
        assertEquals(
            listOf("Cherry", "Nofish", "Jada", "Dylan", "Sunny", "Peter", "Kiki", "Eric"),
            TranslateVoice.entries.map { it.id },
        )
    }

    @Test
    fun voiceCompatibilityMatchesEveryLanguagePair() {
        val multilingual = setOf("en", "zh", "ja", "ko", "fr", "de", "ru", "es", "pt", "it")
        val supported = mapOf(
            TranslateVoice.CHERRY to multilingual,
            TranslateVoice.NOFISH to multilingual,
            TranslateVoice.JADA to setOf("zh"),
            TranslateVoice.DYLAN to setOf("zh"),
            TranslateVoice.SUNNY to setOf("zh"),
            TranslateVoice.PETER to setOf("zh"),
            TranslateVoice.KIKI to setOf("yue"),
            TranslateVoice.ERIC to setOf("zh"),
        )
        supported.forEach { (voice, languages) ->
            TranslateLanguage.entries.forEach { language ->
                assertEquals("${voice.id}/${language.code}", language.code in languages, voice.supports(language))
            }
        }
    }

    @Test
    fun defaultsPreserveTheSixOriginalPreferences() {
        val settings = TranslateSettings().validated()
        assertEquals(TranslateLanguage.EN, settings.sourceLanguage)
        assertEquals(TranslateLanguage.ZH, settings.targetLanguage)
        assertEquals(TranslateVoice.CHERRY, settings.voice)
        assertTrue(settings.audioEnabled)
        assertFalse(settings.imageEnabled)
        assertFalse(settings.usePhoneMic)
    }

    @Test
    fun cantoneseSelectsKikiAndChangingAwayRepairsTheVoice() {
        val cantonese = TranslateSettings(targetLanguage = TranslateLanguage.YUE).validated()
        assertEquals(TranslateVoice.KIKI, cantonese.voice)
        val french = cantonese.copy(targetLanguage = TranslateLanguage.FR).validated()
        assertEquals(TranslateLanguage.FR, french.targetLanguage)
        assertEquals(TranslateVoice.CHERRY, french.voice)
    }

    @Test
    fun supportedVoiceAndFeatureSwitchesSurviveValidation() {
        val settings = TranslateSettings(
            sourceLanguage = TranslateLanguage.AR, targetLanguage = TranslateLanguage.ZH,
            voice = TranslateVoice.ERIC, audioEnabled = false, imageEnabled = true, usePhoneMic = true,
        )
        assertEquals(settings, settings.validated())
    }

    @Test
    fun sourceOnlyLanguagesRemainSourcesButCannotBecomeTargetsEvenWithAudioOff() {
        val sourceOnly = listOf(TranslateLanguage.ID, TranslateLanguage.VI, TranslateLanguage.TH,
            TranslateLanguage.AR, TranslateLanguage.HI, TranslateLanguage.EL, TranslateLanguage.TR)
        sourceOnly.forEach { language ->
            listOf(true, false).forEach { audio ->
                val settings = TranslateSettings(sourceLanguage = language, targetLanguage = language,
                    voice = TranslateVoice.KIKI, audioEnabled = audio).validated()
                assertEquals(language, settings.sourceLanguage)
                assertEquals(TranslateLanguage.ZH, settings.targetLanguage)
                assertEquals(TranslateVoice.CHERRY, settings.voice)
                assertEquals(audio, settings.audioEnabled)
            }
        }
    }

    @Test
    fun swappingSupportedLanguagesRepairsVoiceInTheSameSettingsValue() {
        val original = TranslateSettings(sourceLanguage = TranslateLanguage.ZH,
            targetLanguage = TranslateLanguage.YUE, voice = TranslateVoice.KIKI)
        val swapped = original.copy(sourceLanguage = original.targetLanguage,
            targetLanguage = original.sourceLanguage).validated()
        assertEquals(TranslateSettings(sourceLanguage = TranslateLanguage.YUE,
            targetLanguage = TranslateLanguage.ZH, voice = TranslateVoice.CHERRY), swapped)
        assertEquals(TranslateVoice.KIKI, swapped.copy(sourceLanguage = swapped.targetLanguage,
            targetLanguage = swapped.sourceLanguage).validated().voice)
    }
}

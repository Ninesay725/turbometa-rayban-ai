package com.smartview.glassai.translation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the six translation settings are persisted; conversation content stays with its owner. */
class TranslatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("live_translate", Context.MODE_PRIVATE)
    // A single snapshot also permits safe defaults for preferences saved with an incorrect type.
    private val mutable = MutableStateFlow(prefs.all.let { saved ->
        TranslateSettings(
            sourceLanguage = TranslateLanguage.entries.firstOrNull { it.code == saved[SOURCE] }
                ?: TranslateLanguage.EN,
            targetLanguage = TranslateLanguage.entries.firstOrNull { it.code == saved[TARGET] }
                ?: TranslateLanguage.ZH,
            voice = TranslateVoice.entries.firstOrNull { it.id == saved[VOICE] }
                ?: TranslateVoice.CHERRY,
            audioEnabled = saved[AUDIO] as? Boolean ?: true,
            imageEnabled = saved[IMAGE] as? Boolean ?: false,
            usePhoneMic = saved[PHONE_MIC] as? Boolean ?: false,
        ).validated()
    })
    val settings: StateFlow<TranslateSettings> = mutable.asStateFlow()

    /** Persist and publish one validated value, including language swaps and their voice repair. */
    @Synchronized
    fun update(value: TranslateSettings) {
        val validated = value.validated()
        prefs.edit()
            .putString(SOURCE, validated.sourceLanguage.code)
            .putString(TARGET, validated.targetLanguage.code)
            .putString(VOICE, validated.voice.id)
            .putBoolean(AUDIO, validated.audioEnabled)
            .putBoolean(IMAGE, validated.imageEnabled)
            .putBoolean(PHONE_MIC, validated.usePhoneMic)
            .apply()
        mutable.value = validated
    }

    companion object {
        private const val SOURCE = "translate_source_language"
        private const val TARGET = "translate_target_language"
        private const val VOICE = "translate_voice"
        private const val AUDIO = "translate_audio_enabled"
        private const val IMAGE = "translate_image_enhance"
        private const val PHONE_MIC = "translate_use_phone_mic"

        @Volatile
        private var instance: TranslatePreferences? = null

        fun getInstance(context: Context): TranslatePreferences = instance ?: synchronized(this) {
            instance ?: TranslatePreferences(context.applicationContext).also { instance = it }
        }
    }
}

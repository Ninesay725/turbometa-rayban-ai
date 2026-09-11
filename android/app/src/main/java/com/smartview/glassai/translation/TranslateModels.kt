package com.smartview.glassai.translation

/** Original Qwen 3 translation inventory; labels are language self-names. */
enum class TranslateLanguage(val code: String, val label: String, val audioTarget: Boolean) {
    EN("en", "English", true),
    ZH("zh", "中文", true),
    JA("ja", "日本語", true),
    KO("ko", "한국어", true),
    FR("fr", "Français", true),
    DE("de", "Deutsch", true),
    RU("ru", "Русский", true),
    ES("es", "Español", true),
    PT("pt", "Português", true),
    IT("it", "Italiano", true),
    YUE("yue", "粵語", true),
    ID("id", "Bahasa Indonesia", false),
    VI("vi", "Tiếng Việt", false),
    TH("th", "ไทย", false),
    AR("ar", "العربية", false),
    HI("hi", "हिन्दी", false),
    EL("el", "Ελληνικά", false),
    TR("tr", "Türkçe", false),
}

enum class TranslateVoice(val id: String, val label: String) {
    CHERRY("Cherry", "Cherry"),
    NOFISH("Nofish", "Nofish"),
    JADA("Jada", "Jada"),
    DYLAN("Dylan", "Dylan"),
    SUNNY("Sunny", "Sunny"),
    PETER("Peter", "Peter"),
    KIKI("Kiki", "Kiki"),
    ERIC("Eric", "Eric"),
}

data class TranslateSettings(
    val sourceLanguage: TranslateLanguage = TranslateLanguage.EN,
    val targetLanguage: TranslateLanguage = TranslateLanguage.ZH,
    val voice: TranslateVoice = TranslateVoice.CHERRY,
    val audioEnabled: Boolean = true,
    val imageEnabled: Boolean = false,
    val usePhoneMic: Boolean = false,
)

fun TranslateVoice.supports(language: TranslateLanguage): Boolean = when (this) {
    TranslateVoice.CHERRY, TranslateVoice.NOFISH -> language.audioTarget && language != TranslateLanguage.YUE
    TranslateVoice.KIKI -> language == TranslateLanguage.YUE
    TranslateVoice.JADA, TranslateVoice.DYLAN, TranslateVoice.SUNNY,
    TranslateVoice.PETER, TranslateVoice.ERIC -> language == TranslateLanguage.ZH
}

/** Keep the original target-picker restriction even in text-only mode. */
fun TranslateSettings.validated(): TranslateSettings {
    val target = targetLanguage.takeIf { it.audioTarget } ?: TranslateLanguage.ZH
    val compatibleVoice = when {
        voice.supports(target) -> voice
        target == TranslateLanguage.YUE -> TranslateVoice.KIKI
        else -> TranslateVoice.CHERRY
    }
    return copy(targetLanguage = target, voice = compatibleVoice)
}

enum class TranslateConnectionState { DISCONNECTED, CONNECTING, READY, ERROR }

data class TranslationText(val text: String, val isFinal: Boolean, val originalText: String = "")

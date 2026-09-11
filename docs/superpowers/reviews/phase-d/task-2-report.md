# Phase D Task 2 — translation models and preferences

Implemented the exact shared declarations from the Phase D plan in `com.smartview.glassai.translation`. No service/UI/Home/navigation/resource edits, dependency changes, Gradle, adb or commits. User configuration and other workers' files were preserved; `local.properties` was not read.

## Files

- `android/app/src/main/java/com/smartview/glassai/translation/TranslateModels.kt`
- `android/app/src/main/java/com/smartview/glassai/translation/TranslatePreferences.kt`
- `android/app/src/test/java/com/smartview/glassai/translation/TranslateModelsTest.kt`
- `android/app/src/androidTest/java/com/smartview/glassai/translation/TranslatePreferencesInstrumentedTest.kt`
- This report.

## Interfaces and decisions

`TranslateLanguage(code, label, audioTarget)` exposes EN, ZH, JA, KO, FR, DE, RU, ES, PT, IT, YUE, ID, VI, TH, AR, HI, EL, TR in the requested order. Codes are their lowercase provider values; labels are native language names. The first eleven are audio targets. This preserves the original target-picker restriction even with audio disabled; it makes no broader claim about text-only server support.

`TranslateVoice(id, label)` exposes CHERRY, NOFISH, JADA, DYLAN, SUNNY, PETER, KIKI, ERIC, with exact case-sensitive provider IDs Cherry, Nofish, Jada, Dylan, Sunny, Peter, Kiki, Eric. Labels use those proper names. `supports(language)` matches the research/iOS matrix: Cherry/Nofish support the ten multilingual targets excluding Cantonese; Kiki supports Cantonese; the five dialect voices support Chinese. Independently checked the dedicated Qwen3-LiveTranslate section of the [provider voice table](https://help.aliyun.com/zh/model-studio/omni-voice-list), rather than its broader Omni/new-model tables.

`TranslateSettings` retains all six defaults: EN → ZH, CHERRY, audio on, image enhancement off, phone mic off. `validated()` preserves the source and feature switches; an unsupported target falls back to ZH, a compatible voice remains selected, and an incompatible voice becomes KIKI for YUE or CHERRY otherwise. Swaps use one `copy(...)` followed by `update(...)`; UI can retain the original rule that both sides must be eligible targets before offering a swap.

`TranslateConnectionState` and `TranslationText(text, isFinal, originalText = "")` match the plan. Models are Android-free and have no persistence or recording side effects.

`TranslatePreferences(context)` exposes `StateFlow<TranslateSettings>`, synchronized `update(value)`, and application-context `getInstance(context)`. The dedicated private `live_translate` preferences file uses exactly the six original `translate_*` keys. Loading takes one raw snapshot, handles unknown enum values and incorrect stored types independently, then validates the complete value. Reads do not rewrite storage. Updates write all six validated provider values through one editor before publishing one validated state. No transcript/history/audio/photo/key storage was added; unrelated mode-manager preferences are untouched.

## Authored versus executed

**Authored: 8 JVM model tests and 7 instrumented preference tests. Executed by this worker: 0.** Each test file was written before its corresponding production file. Execution belongs exclusively to the parent runner, so no RED, GREEN or compilation success is claimed.

Model tests cover the exact language/voice inventories, every voice/language compatibility pair, original defaults, Cantonese and multilingual voice repair, preservation of compatible choices/switches, all seven unsupported targets with audio on/off, and supported-language swaps.

Preference tests use real Android SharedPreferences behind an isolated ContextWrapper and a unique test-owned filename, removed after each test. They do not touch the production singleton. Coverage includes empty/default reads, unknown values, corrupt stored types, loaded incompatible combinations, source-only target repair, exact six-key round trip, and a swap publishing one compatible state only after its persisted values agree. Instrumentation avoids adding a preferences mock or a new JVM framework.

Source inspection verified signatures, key spelling, enum/provider mappings, test isolation and owned-file boundaries. Parent should run `TranslateModelsTest` in both JVM variants and `TranslatePreferencesInstrumentedTest` with the existing instrumentation runner. Real model/region entitlement and audio-language acceptance remain the separately documented account/hardware checks.

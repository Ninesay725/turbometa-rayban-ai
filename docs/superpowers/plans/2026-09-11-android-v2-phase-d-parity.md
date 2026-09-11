# Phase D — camera, speech and translation

Approved design §9; continues Phase E `33e261e` on `android-v2`. Use subagent-driven-development with disjoint files and one parent build/adb runner. Existing design is authorized; implement routine choices without renewed approval. No iOS changes, main merge, release, credential-file reads or external messages.

## Scope and constraints

- Camera hub: automatic stream while visible, selectable 1/5/10/15-minute stop budget, DAT capturePhoto, photo preview/share/AI/nutrition, reachable Vision route and working LeanEat capture.
- Cloud speech: exact `qwen3-tts-flash` HTTP SSE, not its WebSocket sibling. Existing regional Alibaba key/provider selection; system TTS for OpenRouter/missing key or cloud failure. No fallback on user cancellation. Snapshot settings per utterance. Split long results into <=600-character requests without broken surrogate pairs. PCM16 little-endian mono24k; completion must wait for audible drain with a bound.
- Translation: exact approved `qwen3-livetranslate-flash-realtime`, still listed separately in current Alibaba docs. Preserve 18 source languages/8 voices, 11 audio-compatible targets and the six iOS preference defaults. Validate target/voice combinations atomically. Do not silently upgrade to3.5 or introduce cloning.
- Use the historical exact-model/iOS configuration (`pcm16` input, `pcm24` output, 16k capture/24k PCM16 playback) as the initial compatibility profile; accept documented old/new transcript field aliases. Current docs have mixed-version examples, so live wire acceptance/account entitlement is explicitly pending. Configure before recording; require session.updated, surface rejection, never silently record on configuration failure.
- Translation is user-started and foreground-only. Stop on lifecycle STOP/disposal, release mic/SCO/camera work, reject retired socket callbacks. Optional visual enhancement uses the existing shared WearablesViewModel stream while this screen owns it; no camera when disabled. No every-frame capturer borrowing. JPEG<=500,000 bytes, <=2fps, only after accepted audio.
- No persisted transcripts/audio/photos beyond an explicit existing Gallery save/share action. Translation history, if kept, is RAM-only, bounded50. Do not log speech/text/image bodies or credentials.
- Preserve C/E content ownership, privacy and known SDK stress exclusion. Actual lenses, real third-party apps and real cloud speech/translation require hardware/account validation, not fixture assertions.
- Fix the pre-existing API31 app-language restart problem with Android's locale lifecycle, with a real emulator check. Avoid unneeded dependencies and unrelated rewrites.

## Shared contracts

New `services/Pcm16Playback.kt` (speech worker owns):
```
interface Pcm16Playback { suspend fun play(chunks: Flow<ByteArray>); fun stop() }
class AudioTrackPcm16Playback(sampleRate: Int = 24_000) : Pcm16Playback
```
Each caller owns one playback instance. play consumes a bounded stream, preserves odd PCM-byte boundaries, handles partial writes, drains accepted frames within a deadline, and releases AudioTrack in finally. stop interrupts current playback; a retired finally cannot stop a replacement. Do not place hardware calls in JVM tests.

New `services/TTSService.kt`:
```
class TTSService(context: Context) {
    suspend fun speak(text: String, languageCode: String): Boolean
    fun stop()
    fun close()
}
```
Instance per feature owner. Latest speak on that instance supersedes the previous utterance; caller cancellation aborts transport/playback, not system fallback. Return true only for completed audible playback. Pure transport/request/SSE and orchestration seams should be injectable for tests. Use app provider/region/key once per request; languageCode determines Chinese/English/etc language_type with Cherry for Chinese, Ethan otherwise. No new credential store.

New `translation/TranslateModels.kt`, `TranslatePreferences.kt` (models worker owns):
```
enum class TranslateLanguage(val code:String,val label:String,val audioTarget:Boolean)
enum class TranslateVoice(val id:String,val label:String)
data class TranslateSettings(
 sourceLanguage:TranslateLanguage=EN, targetLanguage:TranslateLanguage=ZH,
 voice:TranslateVoice=CHERRY, audioEnabled:Boolean=true,
 imageEnabled:Boolean=false, usePhoneMic:Boolean=false)
fun TranslateVoice.supports(language:TranslateLanguage):Boolean
fun TranslateSettings.validated():TranslateSettings
class TranslatePreferences(context:Context) {
 val settings:StateFlow<TranslateSettings>
 fun update(value:TranslateSettings)
 companion object { fun getInstance(context:Context):TranslatePreferences }
}
enum class TranslateConnectionState { DISCONNECTED, CONNECTING, READY, ERROR }
data class TranslationText(val text:String,val isFinal:Boolean,val originalText:String="")
```
Exact enum identifiers: EN,ZH,JA,KO,FR,DE,RU,ES,PT,IT,YUE,ID,VI,TH,AR,HI,EL,TR. Voices CHERRY,NOFISH,JADA,DYLAN,SUNNY,PETER,KIKI,ERIC with exact provider IDs from research. Keys `translate_source_language`, `translate_target_language`, `translate_voice`, `translate_audio_enabled`, `translate_image_enhance`, `translate_use_phone_mic`. Existing mode-manager translation preferences are unrelated.

New `services/LiveTranslateService.kt` (translation protocol worker owns):
```
class LiveTranslateService(/* injectable OkHttpClient/Pcm16Playback as needed */) {
 val state:StateFlow<TranslateConnectionState>
 val text:StateFlow<TranslationText>
 val error:StateFlow<String?>
 fun connect(apiKey:String,endpoint:String,settings:TranslateSettings,source:PcmAudioSource)
 fun sendImage(jpeg:ByteArray):Boolean
 fun disconnect()
 fun close()
}
```
connect is Main-confined, reconnect-safe, endpoint is existing Alibaba realtime baseURL; append exact model. Use shared client and generation/identity fences. Begin source only after session.updated; its false return is an error. PCM16 input via existing AudioRecordPcmSource. Audio output flows through Pcm16Playback with a bounded queue; overflow fails visibly, never accumulates unbounded memory. Preserve revised text/stash and final transcript aliases, correlate response IDs, bound text. Error messages are visible but never include request headers. disconnect stops immediately and closes/cancels the socket; optional session.finish is best-effort, not a claim of cloud acknowledgement.

UI worker `LiveTranslateViewModel` + `LiveTranslateScreen`/`LiveTranslateSettingsScreen` owns mic routing, source construction, service and settings/history. Expose screen:
```
@Composable fun LiveTranslateScreen(wearablesViewModel:WearablesViewModel,
 onBackClick:()->Unit,onSettingsClick:()->Unit,
 onRequestWearablesPermission:suspend(Permission)->PermissionStatus)
@Composable fun LiveTranslateSettingsScreen(onBackClick:()->Unit)
```
Request RECORD_AUDIO at start; recheck on RESUME. For requested glasses mic wait <=3s for SCO; fallback visibly to actual phone input if unavailable. Do not arm/mute media merely on opening settings. Only route/mic owner is stopped on leave. Optional camera permission and stream lifecycle are tied to recording+imageEnabled; preserve a failed camera notice while continuing audio if appropriate.

Camera worker owns `CameraScreen`, `CameraPhotoPreviewScreen` (or one screen with preview state), a small timer/capture policy and related tests. Public screen:
```
@Composable fun CameraScreen(wearablesViewModel:WearablesViewModel,onBackClick:()->Unit,
 onAnalyzePhoto:(Bitmap)->Unit,onNutritionPhoto:(Bitmap)->Unit)
```
The parent connects photo handoff in RAM to Vision/LeanEat routes; callbacks receive the newly captured photo, never takePhoto's old synchronous return. Stream stop budget and lifecycle STOP cancel capture/timer and release only this feature's stream. A timed-out stream can be restarted explicitly. Share through existing FileProvider to a temporary JPEG; never automatic uploads. Do not add gallery permissions to release. Existing screen/viewmodel signature changes must be backward compatible and reported to parent.

## Tasks / ownership / validation

1. Speech worker: Pcm16Playback, TTSService, pure HTTP/SSE/orchestration support, JVM tests, platform playback tests. Read `phase-d-tts-continuation.md`; record first new-test RED then parent-run GREEN.
2. Models worker: translation models/preferences validation, enum/default/corrupt-value/voice/swap tests. No service/UI edits.
3. Protocol worker: LiveTranslateService, protocol reducers and MockWebServer tests, fake capture/playback. Read `phase-d-translate-continuation.md`. No UI/preferences edits.
4. Translation UI worker: ViewModel, two screens, dedicated `values/strings_translate.xml` and zh equivalent, ViewModel tests with fake service seam if necessary. Do not edit Home/Nav; report public signatures. Parent wires routes.
5. Camera worker: camera/preview/timer and dedicated `strings_camera.xml` resources plus tests. May fix LeanEat/Vision screen/viewmodel input/disposal methods; parent owns Nav and shared WearablesViewModel, coordinate required helper there.
6. Parent: navigation/Home/Settings integration, QuickVisionService + QuickVisionScreen TTS unification preserving C lifetime, locale repair, protocol/interface review, all builds/adb, docs and closeout. No other Gradle runners. Include the handoff's Phase B carry-over: RTMP authentication failure must tear down the attempt and permit retry, with production callback seams covering retired callbacks and error-preserving cleanup (B ledger line83 / I6b).
7. Verification: both JVM suites, both APKs, full instrumented suite; emulator camera/timer/preview with MDK, phone translation permission/settings/missing-key/error lifecycle, system-TTS stop/drain. Cloud HTTP/WS verified with local fixtures; real speech/translation model/endpoint/SCO/glasses checks explicitly pending if keys/hardware unavailable. Keep new test counts minimums, not exact constraints.

Research facts and conflicting current/historical protocol evidence live in `docs/superpowers/research/phase-d-{tts,translate}-continuation.md`. Record decisions and fresh evidence in `docs/superpowers/reviews/phase-d/`; do not replace historical A/B/C/E results with inferred passes.

## Execution status — 2026-09-11

Tasks1–7 software implemented and locally verified. Final both-variant JVM totals522/510, both APKs built, final installed-APK suite56 passes with the existing explicit SDK-stress ignore. Parent owns all executed results; worker-authored tests are not independently counted. See the Phase D ledger and task7 report for earlier failures, scope reviews and final transcripts. Real glasses/third-party/cloud acceptance remains pending in the hardware checklist; no main merge or release.

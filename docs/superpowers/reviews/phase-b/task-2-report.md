# Task 2 report — OpenClaw models, settings store and Ed25519 device identity

Branch `android-v2`, base HEAD `32d6990` (Task 1), commit **`0473885`**
`feat(android): OpenClaw protocol models, encrypted settings store and Tink Ed25519 device identity with v3 signature`

Status: **DONE** (no deviations from the brief; the Tink API in the brief's snippet matched the
real jar exactly, confirmed by `javap`).

---

## 1. What was implemented

All 8 brief steps, in order, TDD (RED -> implement -> GREEN).

1. **Gradle** — `tink = "1.20.0"` version anchor and `tink-android` library entry added to
   `gradle/libs.versions.toml` right after `androidx-security-crypto`; `okhttp-mockwebserver`
   library entry added right after `okhttp-logging`. `app/build.gradle.kts` gained
   `implementation(libs.tink.android)` after `androidx.security.crypto`, and
   `testImplementation(libs.okhttp.mockwebserver)` / `androidTestImplementation(libs.okhttp.mockwebserver)`
   after their respective coroutines-test / test-rules lines.
2. **`services/openclaw/OpenClawModels.kt`** (new) — `OpenClawProtocol` object (all wire constants
   and error codes), `OpenClawErrorReason` / `OpenClawConnectionState` sealed classes,
   `OpenClawChatEvent`, `OpenClawNodeInvokeRequest`, `OpenClawError`, `OpenClawNodeInvokeResult`
   (with `success`/`failure` factories), `CameraSnapParams` (with iOS-matching defaults and
   malformed-field tolerance via `from(JsonObject?)`), `OpenClawChatMessage`, `OpenClawClientInfo`.
3. **`services/openclaw/OpenClawSettingsStore.kt`** (new) — `OpenClawSettingsStore` interface and
   `SecureOpenClawSettingsStore(context)`, which resolves `APIKeyManager.getInstance` via a lazy
   delegate so constructing the store never opens `EncryptedSharedPreferences` until first use.
4. **`utils/APIKeyManager.kt`** — five new key constants (`KEY_OPENCLAW_HOST/PORT/SCHEME/TOKEN/DEVICE_SEED`)
   next to `KEY_RTMP_URL`, and ten new methods (`get/saveOpenClawHost`, `get/saveOpenClawPort`,
   `get/saveOpenClawScheme`, `get/saveOpenClawToken` + `deleteOpenClawToken`,
   `get/saveOpenClawDeviceSeed`) appended after `getRtmpUrl()`, before the class's closing brace.
5. **`services/openclaw/OpenClawDeviceIdentity.kt`** (new) — `OpenClawDeviceIdentity` (Tink
   `Ed25519Sign` over the 32-byte seed; `deviceId` = lowercase hex SHA-256 of the raw public key;
   `publicKeyBase64Url` = base64url-no-pad of the raw public key; `sign(payload)` and
   `signConnect(...)` per the v3 layout; `seedCopy()` internal accessor for persistence) and
   `OpenClawDeviceIdentityStore.loadOrCreate(store)` (reuses a valid 32-byte seed, regenerates and
   persists once on a missing/invalid seed).
6. **Tests** — `InMemoryOpenClawSettingsStore` (test fixture), `OpenClawDeviceIdentityTest` (9
   cases: RFC 8032 §7.1 TEST 1 vector, deviceId hashing, base64url public key, the exact v3
   signature-string layout with and without token/deviceFamily, `normalizeForAuth`, a
   sign-then-`Ed25519Verify` round trip, and the identity-store create-once/reload/replace-invalid
   behaviours), `OpenClawModelsTest` (4 cases: snap-param defaults, provided fields, malformed
   fields, all protocol constants) — all copied verbatim from the brief.

---

## 2. Exact public API produced

### `com.smartview.glassai.services.openclaw` (OpenClawModels.kt)

```kotlin
object OpenClawProtocol {
    const val PROTOCOL_VERSION = 3
    const val CLIENT_ID = "openclaw-android"
    const val CLIENT_MODE = "node"
    const val PLATFORM = "android"
    const val DISPLAY_NAME = "Ray-Ban Meta Glasses"
    const val ROLE = "operator"
    val SCOPES: List<String>       // ["operator.read", "operator.write"]
    val CAPS: List<String>         // ["camera"]
    val COMMANDS: List<String>     // ["camera.snap", "camera.list", "device.status", "device.info"]
    const val SESSION_KEY = "turbometa-chat"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 18789
    const val SCHEME_WS = "ws"
    const val SCHEME_WSS = "wss"
    const val ERROR_NOT_PAIRED / ERROR_UNSUPPORTED / ERROR_NO_ROUTER / ERROR_UNKNOWN_COMMAND /
        ERROR_NOT_READY / ERROR_STREAM_FAILED / ERROR_NO_FRAME / ERROR_ENCODE_FAILED /
        ERROR_PERMISSION_REQUIRED / ERROR_TIMEOUT / ERROR_INTERNAL: String
}

sealed class OpenClawErrorReason {
    data class MaxRetries(val attempts: Int) : OpenClawErrorReason()
    data class Transport(val detail: String) : OpenClawErrorReason()
    object InvalidUrl : OpenClawErrorReason()
}

sealed class OpenClawConnectionState {
    object Disconnected; object Connecting
    data class Reconnecting(val attempt: Int)
    object WaitingForPairing; object Connected
    data class Error(val reason: OpenClawErrorReason)
}

data class OpenClawChatEvent(val text: String, val isFinal: Boolean)
data class OpenClawNodeInvokeRequest(val id: String, val command: String, val params: JsonObject?, val timeoutMs: Long?)
data class OpenClawError(val code: String?, val message: String?)
data class OpenClawNodeInvokeResult(val id: String, val ok: Boolean, val payload: JsonObject?, val error: OpenClawError?) {
    companion object { fun success(id, payload): OpenClawNodeInvokeResult; fun failure(id, code, message): OpenClawNodeInvokeResult }
}
data class CameraSnapParams(val maxWidth: Int = 1600, val quality: Double = 0.8, val format: String = "jpg") {
    companion object { fun from(json: JsonObject?): CameraSnapParams }
}
data class OpenClawChatMessage(val id: String = UUID..., val role: String, val text: String, val image: Bitmap? = null, val timestampMs: Long = ...) {
    companion object { const val ROLE_USER = "user"; const val ROLE_ASSISTANT = "assistant" }
}
data class OpenClawClientInfo(val version: String, val modelIdentifier: String, val nodeId: String)
```

### `com.smartview.glassai.services.openclaw` (OpenClawSettingsStore.kt)

```kotlin
interface OpenClawSettingsStore {
    var host: String; var port: Int; var scheme: String
    fun loadToken(): String?; fun saveToken(token: String?)
    fun loadDeviceSeed(): ByteArray?; fun saveDeviceSeed(seed: ByteArray)
}
class SecureOpenClawSettingsStore(context: Context) : OpenClawSettingsStore
```

### `com.smartview.glassai.services.openclaw` (OpenClawDeviceIdentity.kt)

```kotlin
class OpenClawDeviceIdentity private constructor(seed: ByteArray, publicKey: ByteArray) {
    val publicKey: ByteArray
    val deviceId: String                 // sha256Hex(publicKey), 64 lowercase hex chars
    val publicKeyBase64Url: String        // base64Url(publicKey)
    fun sign(payload: String): String
    fun signConnect(clientId, clientMode, role, scopes: List<String>, signedAtMs: Long,
                    token: String?, nonce: String, platform: String, deviceFamily: String?): String
    internal fun seedCopy(): ByteArray
    companion object {
        const val SEED_LENGTH = 32
        fun generate(): OpenClawDeviceIdentity
        fun fromSeed(seed: ByteArray): OpenClawDeviceIdentity
        fun buildSignaturePayload(deviceId, clientId, clientMode, role, scopes: List<String>,
                                  signedAtMs: Long, token: String?, nonce: String,
                                  platform: String, deviceFamily: String?): String
        fun normalizeForAuth(value: String?): String
        fun base64Url(bytes: ByteArray): String
        fun sha256Hex(bytes: ByteArray): String
    }
}
object OpenClawDeviceIdentityStore {
    fun loadOrCreate(store: OpenClawSettingsStore): OpenClawDeviceIdentity
}
```

### `APIKeyManager` (additions)

```kotlin
fun getOpenClawHost(): String; fun saveOpenClawHost(host: String)
fun getOpenClawPort(): Int; fun saveOpenClawPort(port: Int)
fun getOpenClawScheme(): String; fun saveOpenClawScheme(scheme: String)
fun getOpenClawToken(): String?; fun saveOpenClawToken(token: String); fun deleteOpenClawToken()
fun getOpenClawDeviceSeed(): ByteArray?; fun saveOpenClawDeviceSeed(seed: ByteArray)
```

All names/signatures match the brief's Interfaces block and its Step 2.4/2.5/2.6 code verbatim.

---

## 3. TDD evidence

**RED** (`./gradlew :app:testDebugUnitTest --tests "com.smartview.glassai.services.openclaw.*"`,
run before any production file existed):

```
e: ... Unresolved reference 'OpenClawProtocol'.
e: ... Unresolved reference 'OpenClawDeviceIdentityStore'.
e: ... Unresolved reference 'OpenClawDeviceIdentity'.
e: ... Unresolved reference 'CameraSnapParams'.
BUILD FAILED in 37s
```

**GREEN** (same command, after `OpenClawModels.kt` / `OpenClawSettingsStore.kt` /
`OpenClawDeviceIdentity.kt` were added, plus the `APIKeyManager` extension):

```
BUILD SUCCESSFUL in 6s
```

Per-suite JUnit XML:
- `TEST-...OpenClawDeviceIdentityTest.xml`: `tests="9" skipped="0" failures="0" errors="0"`, empty `<system-err>`.
- `TEST-...OpenClawModelsTest.xml`: `tests="4" skipped="0" failures="0" errors="0"`, empty `<system-err>`.

13 new tests total (brief's stated minimum was 13: 9 identity + 4 models) — met exactly.

---

## 4. Build results

- `./gradlew :app:testDebugUnitTest` (full suite): **BUILD SUCCESSFUL**. Aggregated across all 7
  `TEST-*.xml` files under `app/build/test-results/testDebugUnitTest/`: **70 tests total, 0
  failures, 0 errors, no non-empty `<system-err>` in any file** (57 pre-existing from Task 1 + 13
  new).
- `./gradlew :app:assembleDebug`: **BUILD SUCCESSFUL** in 50s. The "Unable to strip ... .so"
  notice is pre-existing native-lib packaging behavior (mwdat/porcupine/etc. libs), unrelated to
  this task's files.
- `./gradlew :app:assembleRelease`: **BUILD SUCCESSFUL** in 1m 18s, R8/minify ran cleanly — no
  missing-class or consumer-ProGuard-rule warnings from Tink appeared in the build log, and no
  `missing_rules.txt` was produced under `app/build/intermediates/`. No ProGuard change was needed.
  All `w:` compiler warnings in the release log are pre-existing (deprecated `EncryptedSharedPreferences`
  / `MasterKey` APIs already used by `APIKeyManager`'s constructor and `init` block before this
  task, plus unrelated deprecations in `BluetoothAudioManager`, `QuickVisionService`,
  `RTMPStreamingService`, `ModeSettingsScreen`, `RecordsScreen`, `Theme.kt`). None are new.

---

## 5. Files changed

- Modified: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`,
  `android/app/src/main/java/com/smartview/glassai/utils/APIKeyManager.kt`
- Created:
  `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawModels.kt`
  `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawSettingsStore.kt`
  `android/app/src/main/java/com/smartview/glassai/services/openclaw/OpenClawDeviceIdentity.kt`
  `android/app/src/test/java/com/smartview/glassai/services/openclaw/InMemoryOpenClawSettingsStore.kt`
  `android/app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawDeviceIdentityTest.kt`
  `android/app/src/test/java/com/smartview/glassai/services/openclaw/OpenClawModelsTest.kt`

Commit `0473885` — 9 files changed, 581 insertions(+), 0 deletions.

---

## 6. Deviations

**None.** The two ambiguity points flagged in the task were both resolved in favor of the brief
as written:

- **Tink API**: `javap` against the real
  `~/.gradle/caches/modules-2/files-2.1/com.google.crypto.tink/tink-android/1.20.0/.../tink-android-1.20.0.jar`
  confirms every API the brief uses exists with the exact signature used:
  - `public com.google.crypto.tink.subtle.Ed25519Sign(byte[]) throws GeneralSecurityException`
    (seed-based constructor, used by `OpenClawDeviceIdentity`'s `signer`)
  - `public static Ed25519Sign$KeyPair Ed25519Sign$KeyPair.newKeyPair() throws GeneralSecurityException`
  - `public static Ed25519Sign$KeyPair Ed25519Sign$KeyPair.newKeyPairFromSeed(byte[]) throws GeneralSecurityException`
  - `Ed25519Sign$KeyPair.getPublicKey()` / `.getPrivateKey()` (Kotlin-visible as `.publicKey` / `.privateKey`)
  - `public Ed25519Verify(byte[])` and `public void verify(byte[], byte[]) throws GeneralSecurityException`

  No substitution was needed — the brief's snippet compiles against the real jar as written.

- **Base64**: the brief's `OpenClawDeviceIdentity.kt` already uses `java.util.Base64` (not
  `android.util.Base64`), so the JVM-stub problem never arises. No change made.

---

## 7. Self-review

- **Completeness**: all 8 brief steps done in order; all Interfaces-block types/signatures present
  verbatim (see §2).
- **No secrets logged**: `sign()`'s catch block logs only `e.message`; `seedCopy()` is `internal`
  and documented "never log it"; `APIKeyManager`'s seed get/save catch blocks log only
  `e.message`, never the seed or token bytes/string.
- **Strings**: no user-facing strings were introduced by this task (models/store/identity are pure
  data-layer types with no UI); nothing needed adding to `values/strings.xml` or
  `values-zh-rCN/strings.xml`.
- **Gradle anchors**: verified `libs.versions.toml` and `app/build.gradle.kts` anchors existed
  exactly as the brief specified before editing; diffs land at the specified insertion points only.
- **Git**: working tree was clean before this task (Task 1's commit `32d6990` was already the
  current HEAD — the stale `git status` in the session's initial context predates that commit);
  after this task's commit the tree is clean again, single commit, correct message, no
  `local.properties` touched or printed.

## 8. Concerns

None. The task is self-contained data/crypto plumbing with no service wiring yet (that's Task 3),
so there is no behavior to manually smoke-test beyond the unit tests and the two assemble builds.

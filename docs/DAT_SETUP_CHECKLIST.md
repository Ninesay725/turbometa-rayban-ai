# DAT development setup checklist — turbometa-rayban-ai (Windows host)

Verified 2026-09-10 against the DAT docs v0.9 export, the mwdat-android/mwdat-ios 0.9.0 plugin skills, `search_dat_docs`, the repo, and the local toolchain. Ten adversarial verifier agents plus a completeness critic reviewed every item.

## Platform verdict

- The repo targets **both** platforms: iOS (`CameraAccess/`, DAT 0.5.0 exact, deployment target 17.0) and Android (`android/`, DAT 0.4.0, minSdk 31).
- On this Windows 11 host only the **Android** app can be built and run. iOS needs a Mac with **Xcode 16+** (the project file uses `objectVersion = 70` and file-system-synchronized groups, which Xcode 15 cannot open; the README's "Xcode 15+" is stale).

## Tooling

| Item | State |
|---|---|
| mwdat-android plugin 0.9.0 | installed, enabled (user scope) |
| mwdat-ios plugin 0.9.0 | installed, enabled (user scope) |
| Wearables MCP `wearables` → https://mcp.developer.meta.com/wearables (HTTP) | configured at local project scope, `claude mcp list` = Connected |
| `search_dat_docs` | answered live (first-app setup + plugin/MCP setup queries) |
| Android Studio | installed (`%LOCALAPPDATA%\Programs\Android Studio`) |
| JDK 21 (Corretto), JAVA_HOME | set |
| Android SDK platform 35, adb 36 | installed |
| Gradle 8.7 wrapper | works from **Git Bash only** (no `gradlew.bat`; `gradlew` is a custom POSIX script) |
| Xcode / xcodebuild | not available on Windows |
| ffmpeg | not installed (only needed for H.265 mock videos) |

Nothing needs to be installed for Claude Code. Checking the MCP into the repo (`--scope project`, creates `.mcp.json`) is optional and only helps collaborators.

## SDK prerequisites (gaps to close)

1. **Create `android/local.properties`** (gitignored, absent today). Use forward slashes:
   ```
   sdk.dir=C:/Users/Lee_L/AppData/Local/Android/Sdk
   github_token=<GitHub personal access token with read:packages>
   ```
   Without it Gradle has no SDK location and DAT artifacts fail to resolve from GitHub Packages. Verified: `:app:dependencies` shows `mwdat-core:0.4.0 FAILED` / `mwdat-camera:0.4.0 FAILED`. Create the token yourself; do not paste it into chat.
2. **First build downloads build-tools 34.0.0** (AGP 8.6.0 default; only 35/36 are installed). Needs network once.
3. **iOS (on a Mac):** `Info.plist` references `$(META_APP_ID)` and `$(CLIENT_TOKEN)` but nothing in the project defines them, so they resolve to empty strings. In Developer Mode pass `META_APP_ID=0 CLIENT_TOKEN=0` via an xcconfig or build settings, and set `DEVELOPMENT_TEAM` (feeds `MWDAT.TeamID`). The README still tells builders to paste literal values into the plist; that is out of date since commit 78ea2f3. Note Debug and Release use different bundle IDs and teams.
4. **SDK pins lag the docs:** Android 0.4.0 and iOS 0.5.0 vs docs 0.9.0 (plugin skill text uses 0.8.0). Plugin code samples (e.g. `addCamera`, `pairGlasses`, `mwdat-display`) may not compile against the pins. Treat an upgrade as a separate task.

## Meta AI app and glasses versions

Minimums for the SDK versions this repo pins (Version Dependencies table):

| Build | Meta AI app | Ray-Ban Meta | Meta Ray-Ban Display |
|---|---|---|---|
| Android (DAT 0.4.0) | V254 | V20 | V21 |
| iOS (DAT 0.5.0) | V254 | V22 | V21 |
| Current docs (DAT 0.9.0) | V282 | V126 | V125 |

- Verify glasses firmware: Meta AI app → **Devices** tab → select glasses → gear icon (**Device settings**) → **General** → **About** → **Version**.
- Android test phone must be **Android 12+** (app minSdk 31) with the Meta AI app installed. None is attached right now.
- Known issue from the docs: newer Wearables Developer Center versions may not be compatible with apps not on the latest SDK. If your Meta AI app auto-updated, a 0.4.0/0.5.0 build may still register in Developer Mode, but the only documented remedy for incompatibility is upgrading the SDK.

## Developer Mode

- Enable: Meta AI app → **Settings** → **App Info** → tap the app version number **5 times** → toggle developer mode on → **Enable**. The app then appears under **Meta AI settings → App connections → Developer mode apps**. (The plugin skills' older "Settings > Your glasses > Developer Mode" path is stale; follow the docs.)
- `APPLICATION_ID = "0"` with no `CLIENT_TOKEN` in the Android manifest is acceptable in Developer Mode. Release-channel/production builds need both values from Wearables Developer Center.
- **Only one third-party app stays registered in Developer Mode.** Registering TurboMeta unregisters Meta's CameraAccess sample (and vice versa). Registration needs internet.

## Mock Device Kit fallback

- **Android: not available yet.** `libs.mwdat.mockdevice` is commented out in `android/app/build.gradle.kts:86` and the app has zero MockDeviceKit code. Enabling it means: uncomment the dependency, add a debug entry point that calls `MockDeviceKit.getInstance(context).enable()`, `pairGlasses(GlassesModel.RAYBAN_META)`, `powerOn()/unfold()/don()`, and `device.services.camera.setCameraFeed(uri)`. That is an app-code change and is deferred. Once enabled it runs on AVD `Pixel_5` (API 31) without glasses or the Meta AI app. Phone-camera feed needs `android.permission.CAMERA` (not declared). Video feeds must be H.265; the docs' ffmpeg command is macOS-only (videotoolbox), on Windows use `-c:v libx265 -tag:v hvc1`. The AVDs are x86_64 while the app's ABI splits are ARM-only, so install the universal APK.
- **iOS:** `MWDATMockDevice` is linked and the view models exist, but the debug sheet/overlay is commented out in `CameraAccess/TurboMetaApp.swift:60-67`. Re-enabling is a code change; it also needs a Mac.

## Repo-native Claude config (decision needed, not changed)

- `.claude/rules/dat-conventions.md` is loaded every session and teaches the 0.5-era iOS API (3 modules, `StreamSession`, `StreamSessionConfig`), which contradicts the enabled mwdat-ios 0.9 plugin (4 modules, `DeviceSession`, `Stream`, `StreamConfiguration`). The seven flat `.claude/skills/*.md` files are not registered as skills (Claude Code expects `skills/<name>/SKILL.md`), and `.claude/commands/build.md` is unedited boilerplate (`YourScheme`, iOS 16).
- Because the repo pins iOS 0.5.0, the repo rules match the code today. Either keep them and ignore the plugin's newer API guidance until you upgrade, or upgrade the SDK and delete the stale `.claude/skills` and rules. There are no Android repo-native skills; the plugin covers Android.

## Local verification steps (no code edits)

1. Done: `claude mcp list` → `wearables … ✔ Connected`; `claude plugin list` → both mwdat plugins enabled.
2. Done: from Git Bash in `android/`, `./gradlew --version` → Gradle 8.7 on JDK 21; `./gradlew help` passes with `ANDROID_HOME` injected; `./gradlew :app:dependencies --configuration debugCompileClasspath` → both DAT artifacts FAILED (token gap confirmed).
3. After creating `local.properties`:
   ```bash
   cd android && ./gradlew :app:dependencies --configuration debugCompileClasspath | grep mwdat
   ```
   Expect resolved versions with no `FAILED`.
4. Build and install on a real phone:
   ```bash
   cd android && ./gradlew :app:assembleDebug
   adb shell getprop ro.build.version.sdk          # must be >= 31
   adb shell dumpsys package com.facebook.stella | grep versionName   # Meta AI, must be >= 254
   ./gradlew :app:installDebug
   ```
   Then register from the app and confirm it appears under Meta AI settings → App connections → Developer mode apps.
5. Emulator smoke test (no glasses features until MockDeviceKit is enabled):
   ```bash
   "C:/Users/Lee_L/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_5
   ```
6. iOS: no local verification possible here. On a Mac with Xcode 16+: `xcodebuild -project CameraAccess.xcodeproj -scheme TurboMeta -destination 'generic/platform=iOS' DEVELOPMENT_TEAM=<team> META_APP_ID=0 CLIENT_TOKEN=0 build`.

## Other findings worth knowing (not setup blockers)

- `Wearables.initialize` in `MainActivity.kt` only runs after all runtime permissions including RECORD_AUDIO are granted; a user who denies the microphone never initializes DAT and later calls return NOT_INITIALIZED.
- Android `signingConfigs.release` points at `~/.android/debug.keystore`; fine for Developer Mode, not for release channels.
- iOS `Info.plist` lacks `NSLocalNetworkUsageDescription`, `NSBonjourServices`, and `NSCameraUsageDescription`, which the v0.9 docs add for Wi-Fi streaming and mock phone-camera feeds. Impact at 0.5.0 is unverified; needed after an SDK upgrade.
- README/CHANGELOG say Android 8.0+ and DAT 0.3.0; the build is minSdk 31 and DAT 0.4.0.

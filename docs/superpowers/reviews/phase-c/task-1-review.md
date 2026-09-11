# Phase C Task 1 — focused review

Read-only review of the current Task 1 changes against S1–S3 and retained Phase A/B lifecycle contracts. Inspected `DatGateway`, `WearablesDatAdapter`, `GlassesSessionManager`, session/display state, `FakeDat`, `DisplaySessionLifecycleTest`, existing manager tests, and the initialization call paths. No Gradle, tests, production edits or Git writes were performed; findings are based on source/control-flow inspection.

**Final snapshot: no unresolved important defects.** The initialization defect below was corrected by a concurrent workspace update while this review was being written; this reviewer did not edit either test.

## Important defect found and resolved during review

### P2 — First manager initialization ran off Main in both instrumented setups

**Original locations:** `android/app/src/androidTest/java/com/smartview/glassai/glasses/GlassesSessionManagerInstrumentedTest.kt:96–97`; `android/app/src/androidTest/java/com/smartview/glassai/services/openclaw/OpenClawNodeServiceInstrumentedTest.kt:99–100`. The newly enforced call is `GlassesSessionManager.kt:82` → `startMonitoring()` at `:206–207` → `checkMain()` at `:616–621`.

Both `@Before` methods originally called `GlassesSessionManager.getInstance(targetContext)` on the instrumentation thread, then switched to Main for the added `startMonitoring()` call. On a cache miss, `getInstance()` itself synchronously calls `created.startMonitoring()` before assigning the singleton, so the new debug guard would throw `IllegalStateException` before execution could reach the Main block.

This is a reachable fresh-install case: `TurboMetaApplication.onCreate()` calls `OpenClawIntegration.install()`, which deliberately skips manager creation when Bluetooth permission has not yet been granted (`OpenClawIntegration.kt:45–50`, retained Phase B I2c). These tests grant permissions later, inside `setUp()`. With no previously created singleton, the first test therefore fails during setup. An already-permitted/warm process masks the failure by returning the cached manager. JVM tests cannot expose it because the Looper guard is inert there.

**Resolution verified by final source inspection:** manager retrieval now uses `onMain { GlassesSessionManager.getInstance(targetContext) }` in `GlassesSessionManagerInstrumentedTest.kt:96`, and `runBlocking(Dispatchers.Main) { GlassesSessionManager.getInstance(targetContext) }` in `OpenClawNodeServiceInstrumentedTest.kt:93`. Both monitoring restarts also run on Main. This removes the identified off-Main initialization path while retaining the production guard and permission-gated startup. Runtime verification should include a cold process whose Application started before Bluetooth permission was granted, not only a warmed singleton.

No other concrete important defect found in the requested Task 1 paths. The released-owner checks precede creation after both STOPPING and retry waits; cancellation is not caught in those waits. Attach/detach guards, late metadata/resume handling, and removal before session stop match the inspected S1–S3 requirements. This review does not claim executable or hardware verification.

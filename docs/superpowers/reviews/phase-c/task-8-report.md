# Task 8 — debug display preview and mock state

Implemented in the assigned debug/release entry, preview, samples, state and ViewModel files, with three targeted `testDebug` test files. The user subsequently expanded scope to `MockDeviceKitScreen.kt`, unique mock-state labels and `display_preview_actions_hint` in both locales, then to `DisplayCard.kt`, `DisplayNode.kt`, `DisplayPagination.kt` and their tests for Task 5 review finding 5. Those corrections are integrated directly. No navigation, Settings, dependency, toolchain or other worker-owned code was edited. No Gradle invocation or commit was made. Existing untracked instructions, handoff and plan files were preserved.

## Behavior and decisions

- `DisplayNodePreview` renders the real S4 node tree (Column, Row, Text, Icon, Button, ButtonGroup), including padding overrides, spacing, alignment, flex weights, card backgrounds and button actions. Its black, clipped viewport is 600×600 logical units: local density scales layout, typography and hit targets together to phone width, with fixed preview font scale. Following finding 5, heading/body/meta font sizes are 40/28/22 sp with line heights 48/36/28, and buttons/groups are 88 units high, matching the pinned SDK metrics. Material icons and SansSerif approximate the SDK artwork/typefaces; unsupported or outline glyphs use the prescribed labelled box. This is a phone approximation, not evidence of hardware rendering fidelity.
- The screen scrolls, offers all eight sample card types, bounds local page stepping, dispatches actual actions through the S6 singleton router, reports actions in a snackbar, and observes S5 `currentCard` so real paging/status actions update the preview. Sample fixture content is literal; screen chrome references resources. The added action hint makes clear that buttons operate real app features.
- `MockDeviceState` stores optimistic flags for the lifetime of the process. Successful commands update the store and current screen; re-entry restores them. Fresh pairing records the pinned SDK's initial off/unworn/folded state. Successful unpair/disable removes/clears records; a failed unpair retains both the device and its flags.
- **Intentional S9 refinement required by the user's unknown-state requirement:** all three properties in `MockDeviceFlags` and `MockDeviceInfo` are `Boolean?`, defaulting to `null`. An externally recovered device with no record is unknown, never implicitly off. No SDK commands run merely to recover its state.
- `MockDeviceKitScreen.ToggleRow` accepts nullable state. Unknown rows show an explicit localized Unknown label with equally styled On/Off buttons; known rows show the switch. Each row supplies separate ViewModel actions: powerOn/powerOff, don/doff, or unfold/fold. No default, inversion of null or automatic recovery command is used. Labels include the controlled field for accessibility; rows grow with content and controls have at least 48 dp height. A failed command leaves the value unknown and displays the existing ViewModel error.
- Commands transform the latest UI record, so a callback holding an older `MockDeviceInfo` cannot revert a different toggle or resurrect an unpaired device. A public Application-only ViewModel constructor is retained; an internal constructor accepts the existing SDK interface for focused tests.
- Pinned DAT 0.9 bytecode confirms `don()` unfolds first, `fold()` doffs first, and `powerOff()` changes **only power**. The previous power-off UI reset incorrectly cleared worn/hinge flags; this implementation preserves them. No SDK internals are imported or invoked by app code. Commands performed outside this screen remain outside the optimistic store's observability.

## Required controller integration

Task 4/7 owns preview navigation and Settings below. The nullable mock-state consumer, its three resource keys and the preview action hint are now integrated by Task 8. The title/subtitle resources have also arrived from Task 4/7; no mock-toggle or preview-resource integration is left for the controller.

1. `Navigation.kt`: import `GlassesDisplayPreviewEntry`, add `Screen.GlassesDisplayPreview` with route `glasses_display_preview`, pass `onNavigateToGlassesDisplayPreview` to Settings, and add the guarded destination calling `GlassesDisplayPreviewEntry.Screen(onBackClick = { navController.popBackStack() })`. Retain the Task 4 navigation-request collector; preview actions use that real router.
2. `SettingsScreen.kt`: add `onNavigateToGlassesDisplayPreview: () -> Unit = {}` and a Developer item guarded by `GlassesDisplayPreviewEntry.isAvailable`, using the title/subtitle below and a divider beside the existing MockDeviceKit item. Release has the matching unavailable stub.
3. The following keys are present in both resource files (first two are S11 rows 19–20; the third is the real-action hint). Listed for reference only; do not duplicate them.

| Key | English | Simplified Chinese |
| --- | --- | --- |
| `display_preview_title` | Glasses display preview | 眼镜显示预览 |
| `display_preview_subtitle` | Render display cards on the phone (debug only) | 在手机上预览眼镜卡片（仅调试版） |
| `display_preview_actions_hint` | Preview buttons run real app actions. | 预览按钮会执行真实的应用操作。 |
| `mock_state_unknown` | Unknown | 未知 |
| `mock_state_on` | On | 开启 |
| `mock_state_off` | Off | 关闭 |

The preview also references the planned `display_prev`/`display_next` keys and existing `back`. The S4 follow-up keeps existing call sites source-compatible and adds optional localized strings to `pageCount(strings: DisplayStrings? = null)`; preview stepping now passes its actual strings. S5/S6 use the exact plan signatures: `ResourceDisplayStrings(context)`, `displayManager(application).currentCard`, `router(application).dispatch(action)`.

## Finding 5 follow-up: layout budgets and plan deviations

The reported fixture (`List(20) { "中".repeat(13) }.joinToString("\n")`, 279 UTF-16 units) was reproduced in two new tests before changing production code. Both failed: Quick Vision treated it as one page and Notice/fallback exceeded its line budget. The cached DAT 0.9 `TextStyle` and `NovaRendererProfile` bytecode confirms the font/line-height pairs and 88-unit button/group height above.

`DisplayLayout` is pure and reserves root padding (48 total), two heading lines (96), two user META lines when present (56), one META page indicator for paged cards (28), buttons (88), and every 12-unit root gap. Body space is divided by the real 36-unit BODY line height. Current conservative budgets are:

| Card/block | BODY lines available |
| --- | --- |
| Quick Vision, LeanEat details, OpenClaw without user text | 8 |
| OpenClaw with user text | 6 |
| Live AI without/with user text | 9 / 7 |
| Notice and text-only fallback | 12 |
| WeChat / Music text | 9 |
| Status's two detail blocks | 4 each |
| LeanEat summary: calories / macros / score | 2 / 3 / 2 |

The width model uses one em cell per Unicode code point, four cells for tabs, and whole-word wrapping before a word that does not fit. It counts LF, CRLF, CR, NEL and Unicode line/paragraph separators. Root content is 552 units wide; BODY/META budgets are 19/25 cells per line, and heading text gets 12 after reserving icon/gap space. This deliberately overestimates many Latin/combining sequences; it is a bounded conservative model, **not glyph measurement or proof of dense Chinese hardware fit**. Device font fallback, shaping, icon sizing and SDK button-label behavior still require hardware checks.

Deviations from the original 280-character-only plan are intentional:

- Paged content obeys **both** 280 UTF-16 units and the card's line budget. Quick Vision, OpenClaw and LeanEat detail page counts/actions all use the same pagination helpers as rendering. Page counts can increase; `pageCount(strings)` also avoids the preview's former canonical/localized LeanEat mismatch.
- For nonblank input, concatenating pages reconstructs the exact original string, including whitespace and line breaks. Pagination no longer trims/skips boundary whitespace. Whitespace-only input retains the original one-empty-page policy. Surrogate pairs and CRLF pairs are not split; code points are retained in order. This is not a guarantee of identical grapheme shaping across page boundaries.
- Nonpaged titles, user previews, Notice/fallback, status/summary and placeholder text receive line-bounded prefixes with an ellipsis. Live AI retains its newest bounded tail with a leading ellipsis. The existing character ceilings remain secondary limits; source card text is unchanged. Already bounded Notice fallbacks retain identity.
- LeanEat detail construction avoids manufacturing an empty first/last line when one of its food/suggestion blocks is absent; actual nonblank detail content is paged without omission.

Four-button **phone preview** check: at 600 units, 24-unit padding on both sides leaves 552. Previous/Next are icon-only 88×88 controls with their full localized labels in `contentDescription`. Three 8-unit gaps leave `(552 - 2×88 - 3×8) / 2 = 176` units each for Again and Done. Those controls stay 88 high; labels are one line with ellipsis and retain their full accessibility descriptions. Actions are unchanged. This is a constrained-layout/arithmetic source check, not a screenshot or measured glyph-fit result. `DisplayCards.kt` and SDK `alwaysShowText` behavior were not changed without device evidence. **Hardware pending:** inspect all four controls, label visibility, taps and width on real Display glasses in both locales, including dense Chinese pages.

Validation after this follow-up: isolated cached Kotlin 2.2.21/JVM 17 + JUnit ran 66 tests successfully: `DisplayCardTest` (12), `DisplayNodeTest` (28), `DisplayPaginationTest` (19), plus the seven preview-sample/state tests. The old 280-only/trimmed-whitespace assertions were replaced with the intended tighter limits and exact reconstruction assertions, not removed or weakened. Added coverage includes explicit 20-line overflow, CRLF/blank lines/tabs/Unicode separators, conservative word wrapping, surrogate boundaries, localized page counts, complete paged-content reconstruction, original nonpaged source retention, and assembled model height ≤600 with header/user/indicator/buttons reserved. The manager queued-tap fixture remains parent-owned and was not edited. No Gradle, Android/Compose build, emulator, or hardware run was performed by this worker.

## Validation

Added 11 focused tests:

- `DisplayPreviewSamplesTest` (3): subtype coverage/order, nonempty real Column roots, realistic multipage samples including three foods/two suggestions.
- `MockDeviceStateTest` (4): unknown recovery, partial updates preserving unknown values, re-entry/device isolation, remove/clear returning unknown.
- `MockDeviceKitViewModelTest` (4): stale callbacks plus new-screen restoration, pinned power/fold semantics, failure preserving unknown state, failed/successful unpair and disable cleanup. Uses the real ViewModel/store with fake SDK boundary objects.

**Actually run:** the first two test classes, compiled with cached Kotlin 2.2.21 targeting JVM 17 and executed via JUnit 4.13.2 in a unique OS temporary directory: `OK (7 tests)`. This included the real S4 card/node/pagination sources and `FixedDisplayStrings`. No Gradle process, download, dependency change or shared build output was involved. Focused whitespace checks passed for every Task 8 file, accepting Windows CRLF; the tracked ViewModel also passes the repository's normal `git diff --check`.

After the scope expansion, both updated resource files were parsed as XML and every `mock_state_*` key was verified present exactly once and nonempty in each locale. `git diff --check` passed for the screen and resource files. The nullable branch, three explicit ViewModel action pairs and non-null switch branch were reviewed in the final diff. No additional test or Android build run was made for this UI integration.

**Not run by this worker:** the four Android ViewModel unit tests, Compose/debug/release compilation, full suites, APK installation, emulator or hardware checks. The controller must run its integrated build/tests after the above wiring. No full TDD red/green or Android build success is claimed.

SDK evidence: the Android MockDeviceKit skill and a successful `mcp__wearables__search_dat_docs` query were read. The pinned `mwdat-mockdevice-0.9.0.aar` public `MockDevice`/`MockGlasses` interfaces expose commands and identifiers but no power/don/fold getters. `javap` inspection of its implementation confirms the command semantics described above. The controller's prerequisite documentation fetch remains authoritative; no secret properties file was read.

Suggested integrated checks: run the three debug test classes, compile both variants, verify Settings visibility by variant and both locales, inspect first/middle/last pages on a narrow phone, tap live routing actions, and leave/re-enter MockDeviceKit after power/don/fold changes. Include an externally paired device without a state record to verify the unknown UI.

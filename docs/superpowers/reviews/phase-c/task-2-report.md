# Phase C Task 2 — pure display cards and LeanEat mapping

Implemented the S4 public interfaces in the shared `android-v2` workspace. Only the nine assigned Kotlin files and this report were written by this worker. No Gradle invocation, dependency/toolchain changes, Git writes/commits, or access to `android/local.properties`. Existing `.agents`, `.codex`, `AGENTS.md`, handoffs and plan were preserved; concurrent workers' files were not edited.

## Files and interfaces

- `DisplayCard.kt`: all eight `DisplayCard` variants, `LeanEatFood`, five `LiveAIPhase` values, the 35 exact S4 `DisplayIcon` names, all ten actions, text limits, `plainText()`, `fallbackNotice(strings)`, `pageCount()`.
- `DisplayNode.kt`: all six node types, five node enums, 26-member `DisplayStrings`, and `toNode(strings)` for Status, Notice, LiveAI, QuickVision, LeanEat, OpenClaw and the WeChat/Music placeholders. Layouts preserve the root/header/footer structure, localized labels, button order/styles/icons/actions, page clamping, transcript limits and zero root `flexGrow`.
- `DisplayPagination.kt`: sentence/newline → comma → space → hard-cut pagination, trimmed pages, blank-input fallback and surrogate-pair-safe boundaries. Internal prefix/suffix helpers apply the same safety to transcript and fallback truncation.
- `LeanEatCardMapper.kt`: `FoodNutritionResponse.toLeanEatCard(page)` maps totals, food fields and suggestions; macro doubles use `toInt()`. This is the only new production file importing the nutrition model. No color/rating getters are accessed.
- Tests: `FixedDisplayStrings.kt`, `DisplayPaginationTest.kt`, `DisplayCardTest.kt`, `DisplayNodeTest.kt`, `LeanEatCardMapperTest.kt`.

No public signatures differ from S4; only internal/private derivation helpers were added. Tasks 3/4/8 and string resources remain with their assigned workers.

## Tests added versus run

Added 54 JUnit tests: 15 pagination, 12 card/fallback, 24 node/layout/action and 3 mapper tests. They cover multilingual boundaries, surrogate pairs, invalid page budgets, unchanged raw transcripts, localized labels, all phase icons, pagination navigation/clamping, blank nutrition details, localized nutrition page boundaries, placeholders and whole-number mapping.

**JVM tests run by this worker: none.** The controller owns Gradle and integrated testing. No compile, debug/release test pass, or hardware-rendering claim is made.

Actually run: a scoped static audit passed for all nine files (no trailing whitespace), the three pure files (no platform or model references), all 26 fixed-string defaults, and exact icon-name/order parity with S4. Read-only Git inspection confirmed branch `android-v2`. Manual review checked constructor signatures and card layouts against S4.

## Decisions and integration concerns

- Character budgets count UTF-16 units, preserving existing `String.length`/`take` limits while never splitting valid surrogate pairs. A cutoff through an emoji backs off by one unit. `paginate` requires a positive limit; limit 1 supports BMP text but rejects a supplementary character because it cannot fit without violating either the budget or Unicode safety. This is surrogate-pair safety, not grapheme-cluster segmentation.
- S4's parameterless `LeanEat.pageCount()` cannot know `strings.kcal`. It uses canonical `kcal` detail text; `toNode(strings)` independently computes localized detail pages and uses those for every indicator and Page action. They can differ at a translated unit's boundary; a dedicated test records this. Consumers should follow emitted Page actions rather than clamp them with the parameterless count.
- `plainText()` uses the main body for each card; LeanEat includes numeric totals, health score, foods and suggestions with canonical units because the signature takes no strings. Fallback feature titles are localized through `DisplayStrings`.
- The plan's `noticeFallbackIsItself` requirement is followed literally: an existing Notice is returned unchanged. Other cards become bounded `I_CIRCLE` Notices. The manager still owns the single fallback-attempt policy.
- Gradle compilation and both variant suites remain required controller verification before committing Task 2. Actual rendering remains hardware-only verification.

# Phase E Task 3 — WeChat/music cards and album art

Implemented in the assigned card/node/SDK renderer, debug preview/samples, card/layout/sample tests and new `DisplayImageInstrumentedTest`. `DisplayPagination.kt` did not need changes. No resource, navigation, runtime/router, dependency or toolchain edits. Other workers' changes and untracked instructions/handoffs are preserved. No Gradle, adb, test execution or commit by this worker.

## Behavior and layout

- Added the exact defaulted WeChat (`count=1`, `timestamp=0`, `page=0`) and Music (`app=""`, `artJpeg=null`) fields. Old constructors remain usable. Android-free `DisplayNode.Image(jpeg, size=240)` requires a positive size no larger than 240.
- WeChat uses the existing content-preserving pagination and `Page(card.copy(page=…))`/Done actions. Page count and render use the same helper. Full merged preview text, explicit newlines, boundary whitespace and surrogate pairs are retained; only the sender heading is bounded. The inbox/runtime remains responsible for selecting three messages; this layer never drops text based on count.
- Metadata uses the locale-neutral numeric notation `×3 · HH:mm`, with local-zone 24-hour time. Unknown/nonpositive timestamps are omitted; display count is clamped to 1–3. Default single-message cards without time omit metadata. **No new resource keys or ResourceDisplayStrings integration are required.**
- WeChat reserves a metadata line when present, two heading lines, page indicator, gaps, padding and 88-unit controls: seven BODY lines with metadata (modeled maximum 588/600), eight without. Existing Phase C pagination, line metrics, character ceilings and action fencing are unchanged.
- Music retains title, artist, optional one-line app name and the original previous/play-pause/next actions. A 240-unit art slot allows one artist line with app name (584/600 modeled height), or two without (580/600). Without art, the artist gets eight/nine lines respectively. Nonpaged metadata uses bounded prefixes with ellipsis; source fields remain intact.
- Missing/empty art produces the text card. Invalid, non-JPEG, >240-pixel or >256 KiB art is omitted at decoding; surrounding text and controls still render. Invalid nonempty bytes conservatively retain the shorter artist budget chosen by the pure tree. No exception-driven manager fallback is needed for these decode failures.

## SDK and bitmap boundary evidence

Read the Android display-access/dat-conventions skills and successfully queried `search_dat_docs`. Generic docs emphasize URI images; the pinned `mwdat-display:0.9.0` AAR was inspected with `javap` instead of assuming an overload. Extracted class-directory inspection completed successfully after the archive-based invocation encountered the known Windows jar-close access error.

`FlexBoxScope.image` accepts nullable URI/Bitmap alternatives followed by ImageSize, CornerRadius and flex/alignment parameters; bytecode requires exactly one image source. Production uses `image(bitmap = bitmap, sizePreset = ImageSize.FILL)`. The default-argument implementation confirms the optional URI and the parameter names. The SDK stores the Bitmap in `ImageNodeToResolve` for deferred resolution; production must not recycle it immediately after the builder call.

`FILL` emits no fixed size style and its resolver derives aspect ratio from the bitmap. The music tree therefore has a stretched Column with 156-unit side insets inside the 552-unit content area. The decoder letterboxes the image, retaining its aspect ratio, into a square no larger than 240 pixels. This prevents portrait art from using a taller aspect ratio than the reserved slot. Only temporary source bitmaps are recycled. SDK-owned output is left alive for deferred resolution. No SDK internals are imported or invoked by production or tests.

Decoding performs a bounds/MIME check before allocating pixels and runs on the existing sender IO path. The debug preview uses the same decoder on Default, renders the real image node, resets image state when the node changes, and supports WeChat page stepping. Samples now include a three-message bilingual preview and a generated local blue/gold JPEG; no real notification or album content is used.

## Validation and handoff

**Authored, not executed by this worker:** eight new pure/debug tests covering constructor defaults, exact three-message reconstruction including CRLF/emoji/whitespace, WeChat metadata/page clamps/navigation copies/height, music art/app combinations and line budgets, absent/empty art, image size bounds, and sample JPEG presence. Existing exhaustive node-tree and height checks include Image; existing C tests remain in place.

Four new Android boundary tests cover valid JPEG dimensions/source-byte retention, portrait/landscape letterboxing at 120/240, malformed/non-JPEG/oversized rejection, and text/control retention when art is absent or undecodable. They exercise our decoder directly. The initial fixture attempted `ContentScope()`; the parent compile established that this constructor is Kotlin-internal despite its JVM-public signature. That fixture was removed, with no reflection or production bypass. The public image call is reached only through the SDK-supplied production scope.

**Actually checked by this worker:** pinned SDK bytecode/signatures, exhaustive node consumers, source layout arithmetic, and scoped `git diff --check` (passed). No JVM/Android tests or builds ran locally. Parent reported production compilation clean during integration; the final test changes still require its coordinated run. Suggested targets: DisplayCardTest, DisplayNodeTest, unchanged DisplayPaginationTest, DisplayPreviewSamplesTest, DisplayImageInstrumentedTest, plus the parent's integrated regression suites.

Hardware pending: actual lens image size/aspect/placement, dense Chinese/font fallback, long title/artist truncation, metadata legibility, three/four-button widths and taps, and real WeChat/music sessions. Conservative model budgets and phone previews are not measured hardware fit. The Phase C SDK restart issue remains unchanged.

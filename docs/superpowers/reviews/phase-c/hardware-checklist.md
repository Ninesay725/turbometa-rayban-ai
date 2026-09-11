# Phase C — actual glasses verification

Status on 2026-09-11: **not run on physical hardware**. The connected-device inventory initially contained no phone or glasses. The Pixel_5 emulator was subsequently started for MockDeviceKit and phone UI checks; it cannot prove Display rendering.

Before testing: pair the Android phone and Meta Ray-Ban Display in Meta AI; check the DAT 0.9.0 version matrix (Meta AI V282+, Display firmware V125+); wear the glasses and install the DAT Wearables App from Developer Mode. In Meta AI, Settings → App Info → tap App Version five times enables Developer Mode. Only one developer app should be registered at a time. The MCP's generic installation section lists Meta AI V272+ for installing DAT, which does not supersede the SDK-specific V282 requirement.

| Item | Expected / measurement | Observed | Status |
|---|---|---|---|
| H1 Attach | Feature session STARTED, then Display Preparing → Ready; no second session | — | Pending hardware |
| H2 Cards | Status, Notice, Live AI, Quick Vision, LeanEat and OpenClaw text legible; long replies page correctly | — | Pending hardware |
| H3 Buttons | Status starts features; Quick Vision Prev/Next/Again/Done; Live AI End; OpenClaw Snap/Done | — | Pending hardware |
| H4 Quick Vision | Camera stops after capture; result remains through speech plus 15 s; menu restored before final claim release | — | Pending hardware |
| H5 LeanEat | Nutrition totals/details visible while an existing camera session runs; automatic camera start belongs to Phase D | — | Pending hardware |
| H6 Bandwidth | Record camera FPS before/after card sends and rendering failures. Keep camera quality unless measurements justify changing it | — | Pending hardware |
| H7 Sleep | Wait 25 s; record whether Display remains STARTED or becomes STOPPED; send next card and record wake behavior | — | Pending hardware |
| H8 L0 Back | Record display/session/error transitions; leaving with the glasses gesture must not automatically restart the experience | — | Pending hardware |
| H9 Update | DAT-app-update-required surfaces the existing update action | — | Pending hardware |
| H10 Hinges | Fold ends session; unfold + re-enter feature creates a fresh usable session | — | Pending hardware |
| H11 Cadence | Partial Live AI cards at most once per 600 ms; final reply delivered without throttle delay | — | Pending hardware |
| H12 Toggle | Turning Display off removes only that capability; camera continues; turning on reattaches on a started session | — | Pending hardware |
| H13 Stop latency | Record debug `camera.stop() took N ms` / `session.stop() took N ms`; any ≥1,000 ms triggers asynchronous-stop assessment | — | Pending hardware |

If H7 shows a self-reported STOPPED display cannot wake on a later send, apply the plan's explicit reattach-on-new-card fallback and rerun H7/H8. Do not introduce speculative auto-restart before observing it. The debug preview is a layout aid, not a substitute for H1–H13. Phase C intentionally drops navigation taps when no foreground Activity receives them.

## Carry-over checks on the owner's phone

- [ ] Beijing and Singapore Fun-ASR: one real utterance each; confirm availability and transcript.
- [ ] First real OpenClaw gateway pairing/approval; preserve credentials outside reports.
- [ ] Quick Vision analysis failure shows the inline error.
- [ ] Fresh install → deny Bluetooth → grant later → device visible without restarting.
- [ ] RTMP failure → retry → connect attempt within 10 s; Stop does not ANR.
- [ ] Failed glasses microphone route → leave chat → normal media audio mode restored.
- [ ] Rapid camera start/stop: record any DAT 0.9 native decoder race. Existing SDK issue remains a local, unpublished draft.

Record device/app/firmware versions, action sequence, observed states, timing, and sanitized log excerpts for each result. Leave unchecked entries pending; historical A/B emulator passes do not count as these physical-device checks.

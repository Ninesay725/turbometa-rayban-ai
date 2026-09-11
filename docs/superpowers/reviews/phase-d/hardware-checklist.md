# Phase D — physical device and cloud-account acceptance

All entries below are **pending**, not implied by JVM fixtures or emulator results. No physical Android phone/Display glasses or real Alibaba API call was used in this continuation. Keep credentials out of reports.

- [ ] Camera: real glasses → hub → capture → preview/share/AI/nutrition; selected 1/5/10/15-minute stop; Back, rotation, background and rapid retry. Confirm no stale image or old-page cleanup affects the new page.
- [ ] Nutrition Display: capture ends camera streaming while the result's session stays alive; actual analysis and paged lens result; leave page releases the claim.
- [ ] Cloud speech: one real Beijing and Singapore `qwen3-tts-flash` utterance; Chinese Cherry and other-language Ethan; final syllable audible, long text segmented, system fallback on missing key/failure, Stop produces no late/fallback speech.
- [ ] Translation: exact `qwen3-livetranslate-flash-realtime` entitlement and acknowledged `pcm16` input/`pcm24` output profile in each region. Record sanitized event names and acknowledged settings, not request headers or speech bodies. A live incompatibility must be surfaced, not silently changed to a newer model.
- [ ] Languages: English↔Chinese, Cantonese/Kiki, another supported audio target; source-only language selection, voice repair, speech/text consistency and latency.
- [ ] Microphone/audio: phone input, glasses SCO success, SCO timeout fallback, route loss, incoming call/audio-focus changes and exit. Confirm actual input/output route and restoration of ordinary media volume.
- [ ] Optional image enhancement: disabled sends no images; enabled has permission gating, at most2fps and500kB JPEG; concurrent Display/camera bandwidth, fold/pause and permission denial. Audio may continue with a visible camera-unavailable notice.
- [ ] Lifecycle/network: Stop, background, open Settings, revoke microphone, Wi-Fi loss, configuration rejection/timeout and restart; no lingering mic indicator, playback, camera or stale transcript callback.
- [ ] App language: Chinese/English cold start and change on the owner's Android version, including API33+ framework locale behavior.
- [ ] RTMP: authentication rejection → preserved error → explicit retry; Stop during connection/codec setup, delayed callbacks, no lingering camera or encoder. Measure Main-thread Stop waiting for serialized IO setup; JVM callback tests do not measure physical codec/network latency.

Also complete [Display hardware checks](../phase-c/hardware-checklist.md) and [WeChat/music hardware checks](../phase-e/hardware-checklist.md). The DAT0.9 immediate session-restart SDK lock/decoder stress issue in Phase C remains a release concern; ordinary emulator passes do not close it. No main merge or release is asserted until this evidence is reviewed.

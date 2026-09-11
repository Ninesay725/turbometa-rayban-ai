# Phase E physical-device checks — pending

No physical phone or glasses was attached for this run. Emulator fixtures validate our Android integration, not third-party app behavior or optical rendering.

- [ ] On the owner's phone, open Notifications & Music; both features start off. Enable Android notification access explicitly, then enable the desired feature.
- [ ] With a running Display session, receive a real WeChat text/group notification. Verify sender, latest-three ordering, paging, time and Done. Incoming messages outside a session must not start a session or appear when a later session opens.
- [ ] Turn off WeChat's notification details; verify the next same-key update removes former details, including on the phone page. Check summary, image/voice placeholders, notification removal and Do Not Disturb limitations.
- [ ] Disable previews/revoke access; verify current/accepted private content is forgotten. Done or switching to another feature releases the temporary notification owner without clearing another feature's card.
- [ ] Confirm installed NetEase and Qishui package names. Defaults are `com.netease.cloudmusic` and `com.luna.music`; adjust the editable allowlist if necessary.
- [ ] Play each music app. Verify title/artist, embedded art, previous/play-pause/next and unsupported-control behavior. With both active, verify playing-first and recent-state priority.
- [ ] Switch tracks while disconnected and reconnect; only current metadata should appear. An AI or notification card must not be repeatedly replaced by metadata updates.
- [ ] Leave/background/reenter the phone page; no camera owner is created, and the music session claim ends on STOP. Test while Live AI/OpenClaw/Quick Vision owns the camera.
- [ ] Inspect 240-pixel art, dense Chinese text, paging and button taps on the actual lenses.
- [ ] Recheck Phase C's SDK immediate-session-restart issue on physical hardware. The emulator stress hang remains unresolved; do not merge/release based only on the passing ordinary tests.

Cloud TTS/translation and other Phase D checks are separate and not implied by these items.

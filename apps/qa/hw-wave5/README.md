# PULSE — WAVE 5-HW · REAL DEVICE VOICE VALIDATION KIT

**Status ledger:** every case in [`RESULTS.md`](RESULTS.md) starts as
`NOT TESTED — BLOCKED: NO HARDWARE`. Nothing becomes PASS without device
evidence. Simulated results are never marked as hardware PASS (directive
rule).

Code under test: **`v0.7.0-native` (versionCode 17)** — the Wave 5 release
(native voice rooms + stage + space; release asset
`Pulse-v0.7.0-native.apk`, 35,164,554 bytes, sha256
`28067e88705daface172ef1d973b189eb0fc2aa483752b918ada66c7de8282f5` — verified
against the GitHub API asset digest 2026-09-12). No feature work, no
redesign, no refactoring happens in this phase — hardware proof only.

## What this phase is

The W5-HW directive requires **physical Android + physical iPhone +
public gateway** validation of voice rooms, stage, space and captions.
The sandbox has **zero** physical capability (fresh proof in
`RESULTS.md` §0, 2026-09-12): no adb, no USB subsystem, no macOS/iOS
toolchain, no capture hardware, no public ingress. The kit therefore makes
the hardware phase executable by any operator with two devices, with the
EXPECTED column pre-filled from the actual Wave 5 implementation
(`docs/WAVE-5-COMPLETION-REPORT.md`, `docs/WAVE5-VOICE-SPACES-PARITY-SPEC.md`).

## Execution order

1. [`01-infrastructure.md`](01-infrastructure.md) — public gateway
   (REST + WSS) reachable from both devices; manifest/`Profile → Connection`
   setup; TURN determination (why W5 room media does NOT need coturn).
2. [`02-android-device.md`](02-android-device.md) — install release APK,
   mic permission drills, logcat capture, FGS/privacy-indicator checks.
3. [`03-ios-device.md`](03-ios-device.md) — install to physical iPhone,
   Console capture, permission drills, AVAudioSession/route checks,
   documented background limitation.
4. [`04-voice-rooms.md`](04-voice-rooms.md) — VR-1…VR-9 (join/leave, PTT
   hold, PTT latch, real mic capture, real remote playback, mute/unmute,
   reconnect, rejoin after speaker leaves, mic release).
5. [`05-stage.md`](05-stage.md) — ST-1…ST-9 (host, listener, raise hand,
   approve, speaker promotion, host mute, end stage, remote state
   propagation, real audio both directions).
6. [`06-space.md`](06-space.md) — SP-1…SP-6 (join/leave, avatar movement,
   realtime position propagation, proximity, reconnect, background/
   foreground).
7. [`07-captions.md`](07-captions.md) — CAP-1…CAP-5 (mic capture, WAV
   window generation, transcription request, caption display, failure
   behaviour).
8. [`08-environment.md`](08-environment.md) — ENV-1…ENV-7 (permission
   denial/re-grant, audio route changes, background/foreground, screen
   lock/unlock, network interruption/recovery, duplicate socket/session,
   mic release after leaving).
9. [`09-report-template.md`](09-report-template.md) — the completion
   report skeleton; fill every field, then the acceptance gate below.

## Topologies (used in every case file)

| ID | Topology |
|----|----------|
| T1 | Same network: both devices on one Wi-Fi/LAN; gateway reached over the public internet |
| T2 | Cross-network: Android on Wi-Fi, iPhone on cellular (or any split) — media relayed by the public gateway |
| T3 | Degraded: one device has its network interrupted mid-session (drill cases) |

Devices: **A = physical Android**, **B = physical iPhone**, two accounts
(Acct-A, Acct-B), same target conversation. A third device/account (C) is
optional and only adds realism to stage/space audience counts.

## FINAL ACCEPTANCE GATE (from the W5-HW directive)

- [ ] Voice rooms: join/leave, PTT hold, PTT latch, real mic capture, real remote playback — Android AND iPhone
- [ ] Mute/unmute verified
- [ ] Voice reconnect + rejoin after speaker leaves verified
- [ ] Microphone release after leaving verified (both OSes)
- [ ] Stage: host, listener, raise hand, approve, promotion, host mute, end stage, state propagation, two-way audio
- [ ] Space: join/leave, movement, realtime position propagation, proximity, reconnect, background/foreground
- [ ] Captions: capture → WAV window → transcribe → display, plus failure behaviour
- [ ] Permission denial/re-grant verified
- [ ] Audio route changes verified
- [ ] Background/foreground and screen lock/unlock verified
- [ ] Network interruption/recovery verified
- [ ] Duplicate socket/session behaviour verified
- [ ] Every case recorded with device, topology, expected/actual, PASS/FAIL, evidence

Only when all gate items are ✅ **with device evidence** may Wave 5
hardware validation be declared complete. Any failure: fix only the failing
voice-path issue, rebuild both platforms, repeat the hardware gate. **No
Wave 6 regardless of outcome.** Wave 3-HW remains OPEN and is a separate
gate.

## FINAL STATUS VOCABULARY (mandatory four tiers)

| Label | Meaning | Claimable from sandbox? |
|-------|---------|--------------------------|
| CODE/CI VERIFIED | Unit/JVM/XCTest + CI green | ✅ already recorded |
| RELAY VERIFIED | Server/realtime behaviour proven against the live relay (20/20 `apps/qa/wave5-runtime-e2e.js`) | ✅ already recorded |
| HARDWARE VERIFIED | Executed on physical devices with evidence | ❌ requires operator |
| HARDWARE BLOCKED | Gated on missing physical hardware/hosts | ✅ current state, documented |

Until the operator's run replaces them, every device case stays
`NOT TESTED — BLOCKED: NO HARDWARE`. **Wave 5 is NOT COMPLETE until real
microphone/audio behaviour has been proven on physical devices.**

# RESULTS — WAVE 5-HW (the honest ledger)

> Rule in force: **an untested case is never converted to PASS; simulated
> results are never marked as hardware PASS.**
> Current verdict: **CODE/CI VERIFIED + RELAY VERIFIED + HARDWARE
> VERIFICATION REQUIRED — WAVE 5 NOT COMPLETE (hardware).**

## §0 — Why every device case below is NOT TESTED (sandbox proof, 2026-09-12)

Fresh probe in the execution sandbox (Debian container):

```
$ which adb fastboot xcodebuild xcrun simctl idevice_id arecord
                              → (none found — no adb, no macOS/iOS toolchain,
                                 no audio capture tooling)
$ lsusb                       → command not found
$ ls /sys/bus/usb/devices     → No such file or directory   ← NO USB SUBSYSTEM AT ALL
$ ls /dev/snd /dev/video*     → No such file or directory   ← NO capture hardware
$ ip -brief addr              → lo + dummy0 + eth0 (private /32), egress-only NAT
                                 (no inbound ports — cannot host a public gateway)
```

Consequences, each a hard blocker:
1. No physical Android device can be attached (no adb, no USB subsystem).
2. No physical iPhone can be attached (no macOS, no USB subsystem).
3. No microphone/speaker exists (no /dev/snd) — real capture/playback is
   categorically impossible here.
4. A publicly reachable production gateway cannot be deployed from the
   sandbox (no inbound port exposure).
5. TURN cannot be hosted from the sandbox (needs a public IP + UDP relay
   range) — and per `01-infrastructure.md` §1, TURN is not on the Wave 5
   room-media path anyway (relay-fanout, not P2P WebRTC).

Therefore: 0 of the 36 device cases are claimable here. The kit in this
directory makes every case executable by an operator with two devices.

## §I — Infrastructure verification (fill from 01-infrastructure §4)

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 5.1 | REST over public HTTPS | NOT TESTED — BLOCKED: NO HARDWARE/HOST | |
| 5.2 | WSS handshake | NOT TESTED | |
| 5.3 | TURN UDP allocation (optional — W3-HW only, NOT on the W5 path) | NOT TESTED (optional) | |
| 5.4 | In-app endpoint adoption (both devices) | NOT TESTED | |
| 5.5 | Install proof versionCode 17 (both devices) | NOT TESTED | |

## §VR — Voice rooms

| ID | Case | Result |
|----|------|--------|
| VR-1 | Join / leave | NOT TESTED — BLOCKED: NO HARDWARE |
| VR-2 | PTT hold | NOT TESTED — BLOCKED |
| VR-3 | PTT latch | NOT TESTED — BLOCKED |
| VR-4 | Real microphone capture | NOT TESTED — BLOCKED |
| VR-5 | Real remote audio playback | NOT TESTED — BLOCKED |
| VR-6 | Mute / unmute | NOT TESTED — BLOCKED |
| VR-7 | Reconnect | NOT TESTED — BLOCKED |
| VR-8 | Rejoin after speaker leaves | NOT TESTED — BLOCKED |
| VR-9 | Microphone release | NOT TESTED — BLOCKED |

## §ST — Stage

| ID | Case | Result |
|----|------|--------|
| ST-1 | Host (first joiner) | NOT TESTED — BLOCKED: NO HARDWARE |
| ST-2 | Listener downlink | NOT TESTED — BLOCKED |
| ST-3 | Raise hand | NOT TESTED — BLOCKED |
| ST-4 | Approve (host-only gate) | NOT TESTED — BLOCKED |
| ST-5 | Speaker promotion (real uplink) | NOT TESTED — BLOCKED |
| ST-6 | Host mute (demote + forced ptt-off + seat re-arm) | NOT TESTED — BLOCKED |
| ST-7 | End stage (two-tap confirm) | NOT TESTED — BLOCKED |
| ST-8 | Remote state propagation | NOT TESTED — BLOCKED |
| ST-9 | Real audio both directions | NOT TESTED — BLOCKED |

## §SP — Space

| ID | Case | Result |
|----|------|--------|
| SP-1 | Join / leave | NOT TESTED — BLOCKED: NO HARDWARE |
| SP-2 | Avatar movement (tap + drag, clamp, throttle) | NOT TESTED — BLOCKED |
| SP-3 | Realtime position propagation | NOT TESTED — BLOCKED |
| SP-4 | Proximity behaviour (≤ 0.18) | NOT TESTED — BLOCKED |
| SP-5 | Reconnect (6-attempt honest error + retry) | NOT TESTED — BLOCKED |
| SP-6 | Background / foreground | NOT TESTED — BLOCKED |

## §CAP — Captions

| ID | Case | Result |
|----|------|--------|
| CAP-1 | Microphone capture feeding captions | NOT TESTED — BLOCKED: NO HARDWARE |
| CAP-2 | WAV window generation (4 s / 64 000 samples, 16 000 tail) | NOT TESTED — BLOCKED |
| CAP-3 | Transcription request round-trip | NOT TESTED — BLOCKED |
| CAP-4 | Caption display + persistence | NOT TESTED — BLOCKED |
| CAP-5 | Failure behaviour (honest silence, mic unaffected) | NOT TESTED — BLOCKED |

## §ENV — Environment battery

| ID | Case | Result |
|----|------|--------|
| ENV-1 | Permission denial / re-grant | NOT TESTED — BLOCKED: NO HARDWARE |
| ENV-2 | Audio route changes | NOT TESTED — BLOCKED |
| ENV-3 | Background / foreground | NOT TESTED — BLOCKED |
| ENV-4 | Screen lock / unlock | NOT TESTED — BLOCKED |
| ENV-5 | Network interruption / recovery (voice, stage, space) | NOT TESTED — BLOCKED |
| ENV-6 | Duplicate socket / session behaviour | NOT TESTED — BLOCKED |
| ENV-7 | Mic release after leaving (all paths) | NOT TESTED — BLOCKED |

## What WAS verified from this sandbox (code-side, this phase)

| Item | Result |
|------|--------|
| Android implementation | `:protocol:test :data:testDebugUnitTest :feature-voice:testDebugUnitTest` BUILD SUCCESSFUL — 65 new JVM tests, 0 failed; `:app:compileDebugKotlin` SUCCESS |
| iOS implementation | 46 new XCTest (Wire 15 + Machine 31) green in CI; full suite + xcarchive green |
| CI | main Android CI SUCCESS + main iOS CI SUCCESS; tag `v0.7.0-native` Android + iOS SUCCESS |
| Release asset | `Pulse-v0.7.0-native.apk` 35,164,554 bytes — GitHub API asset digest sha256 `28067e88705daface172ef1d973b189eb0fc2aa483752b918ada66c7de8282f5` re-verified 2026-09-12 == CDN manifest pin |
| Relay realtime (RELAY VERIFIED) | `apps/qa/wave5-runtime-e2e.js` vs live pulse-socket: **20 PASS / 0 FAIL** — voice join/roster/ptt-echo/chunk-not-to-sender/spoof-gate/transcript/leave+ptt-off; stage first-joiner-host/listener/raise-FIFO/listener-approve-rejected/host-approve/mute-demote+seat-enforcement/stage:ended; space join@center/clamp/throttle-swallow/accepted-move/leave-prune |
| TURN determination | W5 room media = PCM chunks fanned out by the public socket relay (no P2P) → coturn NOT required for W5-HW; remains a W3-HW-only dependency (`01-infrastructure.md` §1) |
| Kit executability | capture script reuse (`../hw-wave3/android-capture.sh`, syntax-checked in W3 phase); all 36 cases have pre-filled EXPECTED from the shipped implementation |

CI/release commits: `529572e` (native W5) → `9379d26` (W5 report + E2E) → `6aa3db6`/tag `v0.7.0-native` (release) → `caf2733` (CDN pin) → this kit.

Update this file only with real device evidence; every
"NOT TESTED — BLOCKED" stays until a physical run replaces it.

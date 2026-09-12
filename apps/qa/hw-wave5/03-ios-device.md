# 03 — PHYSICAL IPHONE RUNBOOK (W5-HW)

App: build `apps/ios` from `main` (the W5 code, Xcode → Pulse target →
physical iPhone; TestFlight also acceptable). NO simulator, ever. Record
the exact iOS version.

## Device prep

- Install via Xcode (`xcodebuild -project … -destination 'id=<UDID>'` or
  IDE Run), trust the developer profile, sign in, set the server origin if
  the manifest hasn't propagated: Profile → Connection.
- First PTT must trigger the mic permission prompt (NSMicrophoneUsage
  description already shipped). Denying it later is case ENV-1.

## Console capture (mandatory for every case)

```bash
# Mac with the device connected:
log stream --device --process Pulse --level debug --style compact \
    > pulse-ios-w5-$(date +%H%M%S).log
```

Plus Control-Center screen recording per case (or QuickTime device movie).

What proves what (grep the capture):
- Voice rooms engine lines — AVAudioEngine start/stop, tap install, converter
  config (16 kHz mono Int16), per-peer `AVAudioPlayerNode` scheduling.
- AVAudioSession wrapper lines — playAndRecord + voiceChat configuration,
  route changes, save/restore on leave (restoration proof: play Music after
  leaving — audio returns to the pre-session route).
- Socket lines — join/leave/ptt/chunk/transcript emits + S→C events.
- Adoption line — `PulseEndpoints`/manifest adoption at launch.

## OS-level mic evidence

- Orange/green dot in the Dynamic Island / status bar while the engine
  runs; dot must clear after leaving (screen recording = evidence).
- Settings → Privacy → Microphone — Pulse toggle state for ENV-1.

## Voice-specific drills

| Drill | How |
|-------|-----|
| PTT hold | long-press PTT ≥260 ms, speak, release (holdThresholdMs = 260) |
| PTT latch | tap <260 ms → latch, tap again → unlatch |
| Mute | mute toggle mid-session — latch must force-drop |
| Interruption | Siri / Timer alarm mid-session — interruption began/ended logged; session resumes honestly (record WHICH) |
| Audio route | BT pair/unpair, wired headset plug/unplug during session |
| Screen lock | lock mid-session, unlock |
| Network drop | Airplane toggle 10 s → recovery |

## DOCUMENTED LIMITATION — iOS background (do NOT hide, record ACTUAL)

Wave 5 shipped **no `UIBackgroundModes audio`**: backgrounding suspends
the AVAudioEngine by OS policy (documented in the Wave 5 completion
report §7; web parity — closing the tab also stops everything). For
ENV-3 / SP-6 on iPhone the EXPECTED is therefore:
transmission stops while backgrounded, honest state on return (re-sync,
no corrupt roster, no ghost transmitting). Record the actual behaviour;
if the engine instead keeps transmitting or returns corrupted, that is a
FAIL finding. Android (FGS-mic) is the device expected to keep capture
alive in background.

## Caption drills

- Voice surface → captions toggle on (persisted `voice.captions` /
  UserDefaults) → PTT talk → captions strip fills from
  `/api/voice/transcribe` round-trip.
- Failure drill: point the app at an origin with the transcribe route
  disabled OR break TLS mid-test → honest silence/copy, mic keeps working,
  no crash.

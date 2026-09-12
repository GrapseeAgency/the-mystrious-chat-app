# Pulse v0.7.0-native — Wave 5: Native Voice Rooms / Stage / Space

Ship date: 2026-09-12 · versionCode 17 · tag `v0.7.0-native`

## What ships

Native (Android Kotlin + Jetpack Compose · iOS Swift + SwiftUI) implementations of
the three live-room families, behaviour-matched to the Web implementation
(`voice-room-sheet.tsx` / `stage-room-sheet.tsx` / `space-sheet.tsx`) and the
pulse-socket relay, per `docs/WAVE5-VOICE-SPACES-PARITY-SPEC.md`:

- **Voice rooms** — walkie-talkie PTT (hold ≥260 ms / tap-to-latch), 16 kHz mono
  Int16 250 ms chunks over `voice:chunk`, per-peer 85 ms jitter playhead with
  stale-seq drop, roster wholesale-replace + prune, mute with software-gate
  truth, live ASR captions (4 s WAV windows → `/api/voice/transcribe` →
  `voice:transcript`, 7 s TTL strip, persisted toggle), reconnect/resync,
  honest failure states, mic foreground service (Android) for backgrounding.
- **Stage** — host/speakers/hands/listeners hierarchy from `stage:state`, raise
  hand (listener-only), host approve/decline, host mute with real voice-seat
  enforcement, two-tap end stage, claim host, role-keeping reconnect resync.
- **Space** — 0..1 normalized map, tap-to-move + drag with the server-matched
  80 ms throttle, optimistic self target with idle reconcile, proximity ring
  (≤0.18), participant count, honest connecting/error states.

## Web defects fixed natively (never copied)

1. Web transmit path never armed its capture buffer — native chunkers are
   unit-proven to emit (Android 7 chunker tests / iOS chunker tests).
2. Rejoin seq blackhole — receivers reset per-peer playback state when the
   roster drops a peer.
3. Stage audience heard nothing — native keeps a voice seat for EVERY joined
   stage role (re-armed after forced removal on demote).
4. Space throttle mismatch (web 90 ms client vs 80 ms server) — native matches
   the server and reconciles after 300 ms finger idle.
5. Space had no error state — native surfaces honest errors after exhausted
   reconnect attempts.

## Verification levels (honest)

- CODE VERIFIED — 65 new Android JVM tests (chunker/WAV/scheduler/voice/stage/
  space machines/captions) + 46 new iOS XCTest cases (wire decode tolerance,
  emit builders, all state machines). Android main CI + iOS main CI green.
- RELAY RUNTIME E2E — voice/stage/space event flows exercised against the live
  pulse-socket service (see worklog W5-E2E record).
- PHYSICAL DEVICE: **PENDING** — two-device PTT audio perception, mic permission
  UX, speaker routing, background mic capture are hardware-sensitive and join
  the open hardware gate (Wave 3-HW remains OPEN). Nothing here claims hardware
  verification.

## Install

Install `Pulse-v0.7.0-native.apk` from this release (or the CDN manifest
channel — the updater gates on the pinned sha256). iOS rides `main` CI archives.
EOF
echo notes written
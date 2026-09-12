# PULSE — WAVE 5 COMPLETION REPORT
## Native Voice Rooms / Stage / Space (Android + iOS)

Date: 2026-09-12 · Scope: Voice Rooms (walkie-talkie PTT), Stage Rooms, Spatial Space Rooms on Android + iOS.
Behavioural truth: Web implementation + relay (`mini-services/pulse-socket`) + `packages/protocol`.
Spec: `docs/WAVE5-VOICE-SPACES-PARITY-SPEC.md` · Waves: 1–4 closed · **Wave 3-HW remains OPEN** · No Wave 6 started.

---

## 1. Android implementation (Kotlin + Jetpack Compose + native audio)

New module `:feature-voice` (+ additive plumbing in protocol/data/domain/app):
- **Wire layer** — `protocol/VoiceRoomDtos.kt`: 14 C→S payload builders matching relay validation exactly (`voice/stage/space` join/leave/ptt/chunk/transcript/hand/approve/mute/end/move), tolerant `StageRoomState` / `SpaceBoardState` S→C parsers, `VoiceTranscriptResultDto`; `PulseSocketClient` +14 typed emit methods; S→C decode already existed (Wave-0 contract) and is now FORWARDED: `PulseRepositoryImpl` + `domain PulseEvent` gained the 7 voice/stage/space cases (were silently dropped).
- **Audio engine** — `audio/VoiceRoomAudioEngine.kt`: `AudioRecord` 16 kHz mono PCM-16 capture gated by transmitting && !muted (software gate = truth), `VoicePcmChunker` 250 ms / 4000-sample blocks, seq continuous per room session, proportional partial flush on release; playback = per-peer `AudioTrack` (USAGE_VOICE_COMMUNICATION) with 85 ms pre-roll, `seq <= lastSeq` dedupe and **roster-drop reset**; `RoomAudioRouter` (audio focus, MODE_IN_COMMUNICATION, speaker toggle); `VoiceForegroundService` (FGS type microphone) keeps the mic alive while backgrounded; RECORD_AUDIO runtime flow via the house `rememberLauncherForActivityResult` pattern.
- **Pure machines (JVM-tested)** — `VoiceRoomStateMachine` (optimistic JOINING→roster-confirmed JOINED, ptt echo, mute force-stops transmit, disconnect stops transmission, roster replace + speaking prune), `StageRoomStateMachine` (role derivation, FIFO hands, claim-host eligibility, 2500 ms missing-me resync, two-tap end with 2600 ms reset, `needsVoiceSeat` re-arm = defect-fix #3), `SpaceBoardStateMachine` (80 ms throttle = server budget, clamp01, optimistic target + 300 ms-idle reconcile, proximity ≤0.18, honest error after 6 failed reconnects), `CaptionWindowAccumulator` (64 000-sample windows, ≥16 000 tail rule, single-flight, clear-on-off), `WavEncoder` (exact 44-byte RIFF header), `PlaybackScheduler` (max(now+85, nextAt) chaining).
- **Surfaces** — `VoiceRoomScreen` (PTT button with hold/latch semantics via `PttGesture.kt`, speaking glow, mute banner, captions strip + persisted toggle, honest status line + error/Try-again, "Syncing roster…"), `StageScreen` (host card/speaker tiles/hand queue with Approve+Decline/listeners row + count, Raise hand, two-tap End, Claim host, "Syncing stage…"), `SpaceScreen` (4:3.4 map, tap+drag with throttle+clamp, self halo + reconcile, NEARBY chips, count pill, connecting/error states), `VoiceRoomsOverlay` hosted at MainActivity beside the call overlay; chat-room header mic entry + "Voice · N live" pill via the same trigger mechanism calls use. Home untouched. Captions REST: `PulseApi.transcribeVoice` with 60 s per-request timeout (Ktor `timeout {}` extension; all other calls unchanged).

## 2. iOS implementation (Swift + SwiftUI + AVFoundation)

New `Pulse/Features/VoiceRooms/` (+ additive plumbing):
- **Wire layer** — `WireDtos.swift`: `PulseSocketEvents` constants + `WireVoicePeer/Roster/Ptt/Chunk/TranscriptEvent/TranscriptResult/StagePerson/StageState/SpacePlayer/SpaceState` (tolerant decode, malformed roster entries dropped, `listenerCount` fallback) + `VoiceRoomWire` payload builders; `PulseSocketClient` +14 typed emit helpers; S→C `voice:ptt` decode fixed to the relay's `on` field (with legacy `active` tolerance — pre-existing bug caught during the wave).
- **Audio engine** — `VoiceRoomAudioEngine`: `AVAudioEngine` input tap → `AVAudioConverter` (input block passed per `convert(to:error:withInputFrom:)`) → 16 kHz mono Int16 → chunker; per-peer `AVAudioPlayerNode` with 85 ms prime + host-time scheduling + stop/detach reset per roster drop; dedicated slim AVAudioSession wrapper (save/restore, playAndRecord+voiceChat); mic permission per house pattern; decode of corrupt base64 drops silently.
- **Machines** — `VoiceRoomModels.swift`: `VoiceRoomModel` (status line precedence: Connecting→(joined) Reconnecting→Syncing roster→Connected, playhead bookkeeping, roster prune), `StageModel` (roles, optimistic hand reconciled by every stage:state, voice-seat re-arm, two-tap `StageEndConfirm`, claim host, 2500 ms resync), `SpaceModel` (80 ms throttle, clamp, reconcile after 300 ms idle, proximity, error after 6 retries), `VoicePcmChunker`, `WavEncoder`, `CaptionWindowAccumulator` — all pure, `Equatable` value types.
- **Surfaces** — `VoiceRoomView` (PTT long-press/tap-latch, glow, mute, captions, status), `StageView` (full hierarchy + host controls), `SpaceView` (tap+drag map, halo, NEARBY rail, count, honest retry), `VoiceRoomsSurfaceHost` (fullScreenCover; closing never leaves the room; PTT force-stops on close), `VoiceRoomChatEntry` (header launcher + "Voice · N live" pill), mounted beside the call overlay in `RootView`; `PulseSession.startVoiceRooms` + all 7 signals routed; `PulseAPIClient.transcribeVoice` (60 s, per-call timeout param); `PulsePrefs.voiceCaptions`.

## 3. Realtime verification (RUNTIME E2E — live relay)

`apps/qa/wave5-runtime-e2e.js` (socket.io-client, 3 clients) against the live pulse-socket service: **20 PASS / 0 FAIL**, covering the acceptance ledger: voice join→roster→2-peer broadcast→ptt echo→chunk to peer NOT sender→spoofed-chunk identity gate→transcript w/ server stamp→leave + forced ptt-off; stage first-joiner-host→listener join→raise hand FIFO→listener-approve rejected→host approve→mute demote with voice-seat enforcement roster broadcast + ptt-off→stage:ended; space join@center→out-of-range clamp→80 ms throttle swallow→accepted move→leave prune. One server behaviour was re-confirmed during E2E authoring: when a demoted speaker was the LAST voice peer, `removeVoicePeerByUser` correctly skips the roster broadcast (room becomes empty) and only the forced `voice:ptt off` is emitted — the E2E asserts both paths with an observer client.

## 4. Tests (111 new)

- **Android (JVM, `./gradlew :protocol:test :data:testDebugUnitTest :feature-voice:testDebugUnitTest` BUILD SUCCESSFUL)** — 65 new: chunker 7 (WEB DEFECT FIX #1 proof: chunks actually emit, seq continuity, partial flush, reset), WAV 6 (byte-exact header + b64 round trip incl. 44-byte header), captions 10, playback scheduler 7 (stale/dup drop, chaining, roster-drop reset = FIX #2), voice machine 10, stage machine 16 (FIX #3 re-arm, two-tap end, claim, resync), space machine 9 (FIX #4/#5), plus protocol builder/parser tests. CI gate extended: android-ci runs `:feature-voice:testDebugUnitTest`.
- **iOS (XCTest via CI)** — 46 new in `VoiceRoomWireTests` (15) + `VoiceRoomMachineTests` (31): event-name parity, payload builders, tolerance decoding, all machine semantics, WAV bytes. Full suite green in CI (build-test job).

## 5. CI evidence

- main: Android CI #… SUCCESS (round 5 series: r1 domain-test fakes missing the 15 new repo members → fixed; r2-r4 iOS Swift fixes) · iOS CI SUCCESS (r5) — archive job green from r3 onward.
- tag `v0.7.0-native`: Android CI SUCCESS, iOS CI SUCCESS (build-test + xcarchive).
- Android artifact verified: sha256 `28067e88705daface172ef1d973b189eb0fc2aa483752b918ada66c7de8282f5`, `aapt2` badging `app.pulse.chat` versionCode 17 / versionName 0.7.0-native, `apksigner` v1/v2/v3 with the committed Pulse Live Update cert.

## 6. Release artifacts

- Release page: **v0.7.0-native** (latest) with `Pulse-v0.7.0-native.apk` (35,164,554 bytes), published by tag CI.
- CDN channel consistent: `download/update-manifest.json` (versionCode 17, sha256 28067e88…82f5) == raw CDN == release asset == `download/Pulse.apk` mirror. LiveUpdater hash gate will pass.

## 7. Hardware limitations (HONEST)

- **PHYSICAL DEVICE: PENDING for all audio-perceptual behaviour** — two-device PTT audio quality/latency, mic permission UX on real OS builds, speaker/earpiece routing, Bluetooth paths, background mic capture under real power management, real-network (TURN) behaviour. These join the open hardware gate; **Wave 3-HW remains OPEN** and no hardware claim is made here.
- CODE VERIFIED (unit) and RELAY RUNTIME VERIFIED (E2E above) are the only levels claimed.
- iOS background mic: no `UIBackgroundModes audio` was added — backgrounding suspends the engine by OS policy (documented limitation, honest states shown; Web parity: closing the browser tab also stops everything).
- ASR captions depend on the hosted speech service (`/api/voice/transcribe`, participant-gated); failures are honest silence by design (never blocks the mic).

## 8. Remaining gaps / notes

- Realtime chunk transport is base64-over-socket.io (web wire contract); binary frames or Opus encoding would cut bandwidth ~25–60% but would break wire parity — out of scope for behavioural parity, noted for a future protocol wave.
- No adaptive jitter buffer (fixed 85 ms pre-roll, web parity); no PTT auto-stop timer (web parity).
- Stage/space/voice identity remains client-claimed display data bound to socket identity gates (existing relay trust model, unchanged — server-side auth hardening belongs to the server, not the native wave).
- Local persistence: rooms are ephemeral by design (nothing stored); only the captions toggle persists (Android DataStore `voice.captions` / iOS UserDefaults). No DB migration needed.

**STOP — Wave 6 not started.**

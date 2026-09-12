# WAVE 5 — NATIVE VOICE ROOMS / STAGE / SPACE — PARITY SPECIFICATION

Behavioural truth: Web `src/components/chat/voice-room-sheet.tsx` (R21-b + R48),
`stage-room-sheet.tsx` (R24-c), `space-sheet.tsx` (R24-c) + relay
`mini-services/pulse-socket/index.ts` + `packages/protocol/src/contracts.ts`.
Native implements BEHAVIOUR, not Web DOM/CSS. Web defects are documented and fixed,
never copied. Wave 3-HW remains OPEN; this wave does not touch calling code except
additive shared plumbing noted below.

---

## 0. WIRE CONTRACT (authoritative, unchanged)

Transport: socket.io relay, dedicated to rooms. Native reuses the app's existing
relay connection (PulseSocketClient / PulseSocketClient.swift) — the relay keys
voice/stage/space seats per socketId independently, and native apps are single-window.
NO presence `join` side effects (voice:join is NOT presence join).

C→S (14 events, all already registered in `SocketEvents.kt` / contracts.ts):
- `voice:join`   `{ conversationId, user: { id, name, username, color } }`
- `voice:leave`  `{ conversationId }`
- `voice:ptt`    `{ conversationId, userId, on: Boolean }`
- `voice:chunk`  `{ conversationId, userId, seq: Long (starts at 1), data: base64(Int16LE PCM 16kHz mono, 250ms = 4000 samples ≈ 10672 b64 chars) }`
- `voice:transcript` `{ conversationId, userId, text }` (server stamps name/color/at; 280-char cap; server rate-limit 700ms/socket)
- `stage:join`   `{ conversationId, user: { id, name, username, color }, asHost: Boolean }`
- `stage:hand`   `{ conversationId, user: { id }, raised: Boolean }`
- `stage:approve` `{ conversationId, byUserId, targetUserId }`
- `stage:mute`   `{ conversationId, byUserId, targetUserId }`
- `stage:end`    `{ conversationId, byUserId }`
- `stage:leave`  `{ conversationId }`
- `space:join`   `{ conversationId, user: { id, name, username, color } }`
- `space:move`   `{ conversationId, x: Double, y: Double }`
- `space:leave`  `{ conversationId }`

S→C (7 events):
- `voice:roster` `{ conversationId, peers: [{ id, name, username, color }] }` (sorted by joinedAt)
- `voice:ptt`    `{ conversationId, userId, on }` (echoed to sender too)
- `voice:chunk`  `{ conversationId, userId, seq, data }` (relayed to room EXCEPT sender)
- `voice:transcript` `{ conversationId, userId, name, color, text, at }`
- `stage:state`  `{ conversationId, host: {id,name,color}|null, speakers: [{id,name,color}], hands: [{id,name,color}] (FIFO by raisedAt), listeners: [{id,name,color}], listenerCount: Int }`
- `stage:ended`  `{ conversationId }`
- `space:state`  `{ conversationId, players: [{ id, name, color, x, y }] }` (0..1, rounded 4dp, sorted by userId)

Server gating native clients must respect: identity-gated ptt/chunk/transcript
(roster entry must be bound to this socket); 96KB chunk cap; one voice/stage/space
room per socket (switch = silent leave); stage host seat immune to stage:mute;
stage:mute on a speaker force-removes their VOICE seat (`removeVoicePeerByUser`).
Space: server throttle 80ms, clamp 0..1, idle prune 5min, previous position kept
on re-join while room alive. Stage: first joiner of a fresh room = host; re-join
keeps prior role; `asHost:true` honored only while host seat empty.

REST: `POST /api/voice/transcribe` `{ conversationId, requesterId, audioBase64 }`
→ `{ transcript }` (≤280 chars); 400 missing fields, 403 non-participant, 413
>512K b64 chars, 422 empty, 502 service failure. Participant-gated server-side.

---

## 1. CAPABILITY PARITY MAP

Legend per row: WEB behaviour → ANDROID → iOS → SOCKET → API → LOCAL STATE →
OFFLINE/RECONNECT → PERMISSIONS → ACCEPTANCE TEST.

### 1.1 Voice room (walkie-talkie PTT)
| # | Capability | Spec (all platforms) |
|---|---|---|
| VR-1 | Entry/exit | Chat room header mic button opens room surface; "Voice · N live" pill when member+closed; explicit Join/Leave; membership survives surface close; engine lives in session scope; leaving conversation leaves room. TEST: join→pill→reopen→leave. |
| VR-2 | Join roster | On join emit `voice:join`; `voice:roster` replaces roster wholesale; prune speaking set to live peers. TEST: 2 devices see 2 peers. |
| VR-3 | PTT hold/latch | Press ≥260ms = hold (stop on release); tap <260ms = latch (tap again stops); keyboard/accessibility = pure toggle; disabled when muted/not-joined/disconnected; `voice:ptt` echo lights self ring. NO auto-stop timer (web parity). TEST: hold talks, tap latches, re-tap stops. |
| VR-4 | Capture | 16kHz mono Int16, 250ms blocks (4000 samples) gated by transmitting && !muted; linear resample from device rate; seq starts 1, increments per chunk; partial block flushed proportionally on release; chunks dropped (not queued) while disconnected. **WEB DEFECT FIX #1: capture path MUST actually emit (web never armed its block buffer) — proven by unit test on the chunker.** TEST: speaking peer hears audio (emulator loopback via two clients on relay fixture). |
| VR-5 | Playback | Per-peer state {lastSeq, nextAt}; drop `seq <= lastSeq`; schedule at `max(now + 85ms, nextAt)`, chain `nextAt = at + duration`; corrupt chunk dropped silently. **WEB DEFECT FIX #2: when a peer disappears from `voice:roster`, reset that peer's playback state (lastSeq→0, clear queue) so rejoin restarts audio immediately.** TEST: dup/stale seq dropped; after peer rejoin audio resumes. |
| VR-6 | Mute | Software gate is source of truth; muting while transmitting force-stops PTT; UI banner; rejoin resets mute. TEST: mute stops chunks at gate. |
| VR-7 | Captions | Toggle persisted (pref key `voice.captions`; web parity `pulse-voice-captions`). While transmitting+on: accumulate 16kHz samples; ≥64000 samples (4s) → flush (skip <16000 tail on final); single-flight (busy → keep accumulating); 44-byte WAV header mono/16k/16bit; POST /api/voice/transcribe; on success emit `voice:transcript {conversationId,userId,text}`; keep last 3 captions, 7s TTL sweep (1s timer); turning off clears pending audio; failures = honest silence (no retry of window). TEST: unit-test WAV encoder + windowing + TTL. |
| VR-8 | Reconnect | On relay reconnect: re-emit `voice:join` if still joined; roster-missing-me resync rate-limited 2s; connection status line (connected/connecting/reconnecting/standby). TEST: simulated drop → re-join emitted. |
| VR-9 | Teardown | Leave → emit leave, reset seq=1, clear captions+playback, close audio; conversation switch leaves room; app teardown leaves room. TEST: state cleared. |
| VR-10 | Failure states | Mic denied/busy/absent mapped copy + inline error + retry; relay unreachable error + "Try again"; empty roster "Syncing roster…". TEST: permission-denied path (deny in instrumented flow / unit on state). |
| VR-11 | Lifecycle | Android: mic foreground service while joined (FGS type microphone) so backgrounding keeps mic; stop on leave. iOS: audio session `.playAndRecord/.voiceChat`; background audio only if entitlement present — otherwise honest: leaving background stops transmission (documented limitation). TEST: background/foreground keeps roster, audio intent preserved (code-verified; hardware-pending). |

### 1.2 Stage
| # | Capability | Spec |
|---|---|---|
| ST-1 | Entry/join | Tray/command entry opens stage; join ALWAYS as listener (`asHost:false`); server: first joiner of fresh room = host. Optimistic `joined` status; "Syncing stage…" until first `stage:state`. TEST: first client = host. |
| ST-2 | State render | `stage:state` → host card (amber, crown), speaker tiles (speaking glow from `voice:ptt`), hand queue (FIFO numbered), listeners row (first 4 avatars + names + "+N more"), header listenerCount. Roles derived: host if host.id==me; speaker if in speakers; else listener; handRaised if in hands. TEST: pure state-machine unit tests. |
| ST-3 | Hand/approve | Listener-only raise/lower (`stage:hand`); host-only approve (`stage:approve` byUserId+targetUserId) moves hand→speaker; decline = `stage:mute` on a hand (dismiss only). TEST: listener raise→host approve→speaker. |
| ST-4 | Mute/demote | Host-only `stage:mute`: speaker→listener + server force-removes voice seat; host seat immune; muted speaker stops transmitting. TEST: demote reflected in next state. |
| ST-5 | End stage | Host-only two-tap confirm (reset 2600ms) → `stage:end`; all clients get `stage:ended` → full local teardown + surface close + toast. TEST: end → both clients land idle. |
| ST-6 | Voice seat for ALL (audio fix) | **WEB DEFECT FIX #3: Web stage listeners never `voice:join`, so audience hears nothing. Native: every joined stage member (host/speaker/listener) holds a voice seat; on demotion (voice seat force-removed) re-emit `voice:join` so the demoted member keeps hearing; speakers additionally arm mic on PTT.** Glow pruned to live speaker ids on each `stage:state`. TEST: listener receives chunks (fixture round-trip). |
| ST-7 | Claim host | When `host==null` (host left; NO auto-promotion): "Claim host" button → `stage:join {asHost:true}` while joined. Reopen after close = plain listener (close emits leave). TEST: claim host works. |
| ST-8 | Reconnect/resync | Reconnect → re-`stage:join` with remembered wasHost; missing-me resync rate-limited 2500ms; connection status line. TEST: drop → rejoin keeps role. |

### 1.3 Space (spatial presence)
| # | Capability | Spec |
|---|---|---|
| SP-1 | Join/leave | Open surface → `space:join` on connect; leave on close/unmount. TEST: join→state→leave. |
| SP-2 | Map | `space:state.players` positioned by normalized 0..1 coords; self highlighted + halo; count pill "N in room". TEST: two clients see two dots. |
| SP-3 | Move | Tap-to-move AND drag on the map (not per-avatar); clamp 0..1 client-side; **client throttle = 80ms (WEB DEFECT FIX: was 90 vs server 80)**; emit `space:move`; optimistic self target. **WEB DEFECT FIX #4: reconcile — server self-position overwrites optimistic target when no local move in the last 300ms (finger idle).** TEST: positions converge across clients. |
| SP-4 | Proximity | Euclidean distance ≤0.18 → "nearby" ring + NEARBY chip rail; empty/none-nearby copy. TEST: unit test on proximity predicate. |
| SP-5 | States | Connected subtitle vs "Connecting to the room…"; **WEB DEFECT FIX #5: honest error state after reconnect attempts exhausted (web spun forever)**; stale players self-heal via full-state replace (server prunes 5min idle). TEST: error path reachable in unit test. |

### 1.4 Persistence
Rooms/captions are EPHEMERAL (server in-memory; nothing stored — footer contract
"never recorded or stored"). Only persisted artefact on native: captions toggle
pref. NO Room/GRDB schema migration in Wave 5. Offline: rooms unavailable
(honest reconnecting/error states); chat unaffected.

---

## 2. PLATFORM IMPLEMENTATION MAP

### Android (Kotlin + Jetpack Compose, module `:feature-voice`)
- `protocol`: C→S payload builders (mirrors Wave-3 call builder style) for all 14 events; constants already exist in `SocketEvents`.
- `data/PulseSocketClient`: typed `emitVoiceJoin/Leave/Ptt/Chunk/Transcript`, `emitStageJoin/Hand/Approve/Mute/End/Leave`, `emitSpaceJoin/Move/Leave` (S→C decode exists).
- `domain/PulseEvent` + `PulseRepositoryImpl`: forward the 7 S→C signals (currently dropped `-> Unit`).
- `data/PulseApi`: `transcribeVoice(conversationId, requesterId, audioBase64)` with per-call 60s timeout (HttpTimeout plugin as required — additive, no behaviour change to other calls); `VoiceTranscriptResultDto` in protocol; `isConfigured` guard parity.
- `data/PulsePrefsStore`: `voice.captions` boolean key (DataStore).
- `:feature-voice`:
  - `VoicePcmChunker` (pure): 16k/250ms/4000-sample blocks, seq, proportional partial flush — JVM-tested (proves WEB DEFECT FIX #1).
  - `VoiceRoomAudioEngine`: `AudioRecord` (16kHz mono Int16, Mic source) + per-peer `AudioTrack` (STREAM_VOICE_CAPABILITY-era API via AudioTrack.Builder, USAGE_VOICE_COMMUNICATION) with 85ms pre-roll + seq dedupe + roster-reset; slim `RoomAudioRouter` (focus + MODE_IN_COMMUNICATION + speaker toggle) — own file, feature-calls untouched.
  - `VoiceRoomEngine` / `StageRoomEngine` / `SpaceEngine`: pure, testable state machines (status, roster, speakingIds, hands, roles, players, proximity) — JVM-tested.
  - `VoiceForegroundService`: FGS type microphone while voice-joined.
  - UI: `VoiceRoomScreen`, `StageScreen`, `SpaceScreen` + session-level `VoiceRoomsOverlay` host (CallOverlay pattern) + ChatRoom entry (header mic button + "Voice · N live" pill) — Home untouched.
- Permissions: RECORD_AUDIO already in manifest; request flow via `rememberLauncherForActivityResult` (house pattern).

### iOS (Swift + SwiftUI, `Pulse/Features/VoiceRooms/`)
- `WireDtos.swift`: `WireVoicePeer/WireVoiceRoster/WireVoicePtt/WireVoiceChunk/WireVoiceTranscript/WireStagePerson/WireStageState/WireStageEnded/WireSpaceState` (tolerant optionals) + `PulseSocketEvents` constants enum.
- `PulseSocketClient`: typed emit helpers (generic emitter exists) for all 14 events.
- `PulseAPIClient`: `transcribeVoice(...)` with per-call 60s timeout (additive optional param on transport).
- `PulsePrefs`: `voice.captions`.
- `VoiceRoomEngine.swift`: `AVAudioEngine` input tap (bus 0) → convert to 16k mono Int16 (AVAudioConverter) → 250ms chunker (shared pure type) → base64; per-peer `AVAudioPlayerNode` playback with 85ms playhead + seq dedupe + roster-reset; `PulseCallAudioSession`-style category handling (own slim session wrapper or reuse existing type read-only); mic permission via existing `requestMicPermission()` pattern.
- `VoiceRoomSessionModel.swift`: session-scoped (PulseSession `startVoiceRooms`), drives voice+stage+space machines, consumes `session.signals`.
- Views: `VoiceRoomView`, `StageView`, `SpaceView` + fullScreenCover host + ChatRoom entry (mic button + live pill) — Home untouched.
- Tests (`PulseTests`): chunker, WAV encoder, viewer-agnostic state machines, wire decode, proximity, reconcile logic, socket round-trip vs existing relay fixture where feasible.

---

## 3. VERIFICATION CLAIM LEVELS (HARDWARE RULE)
- CODE VERIFIED — unit tests (chunker, machines, wire builders, WAV, proximity).
- SIMULATOR/EMULATOR VERIFIED — Android instrumented/relay-fixture round-trips where runnable; iOS tests via CI (macOS).
- PHYSICAL DEVICE VERIFIED — NOT CLAIMABLE in this sandbox: real two-device PTT audio, mic permission UX, speaker routing, background mic. All audio-perceptual items stay marked PHYSICAL DEVICE: PENDING and roll into the Wave 3-HW style hardware gate. Never reported as verified.

## 4. ACCEPTANCE TEST LEDGER (both platforms)
VOICE: join → speak → receive → mute → unmute → captions → leave.
STAGE: host → listener → raise hand → approve → speaker → mute → demote → end.
SPACE: join → move → realtime position sync → leave.
EXTRAS: reconnect; permission denial; background/foreground; stale participant
cleanup (roster drop resets playback/space prune); duplicate socket events
(seq dedupe, idempotent ptt, wholesale roster replace); teardown.

# PULSE — WAVE 3 COMPLETION REPORT
## NATIVE CALLS (Android + iOS)

**Verdict: CODE/CI VERIFIED + HARDWARE VERIFICATION REQUIRED.**
Every gate executable in this sandbox passed (compile, unit, instrumented, archive, artifacts, tagged release, live signalling E2E). The physical-device media gates explicitly CANNOT be claimed from here and are marked REQUIRED below. Per the wave directive, final completion is therefore NOT claimed.

---

## 1. SCOPE COVERAGE (the 22 calling items)

| # | Item | Android | iOS | Evidence |
|---|------|---------|-----|----------|
| 1 | Outgoing call | ✅ | ✅ | CallEngine.startOutgoing / PulseCallEngine.startOutgoing → machine startOutgoing → offer; contacts call button (mic-permission gated); DM resolved/created server-side |
| 2 | Incoming call | ✅ | ✅ | call:offer → incomingRinging; full-screen overlay with caller identity from payload |
| 3 | Ringing state | ✅ | ✅ | outgoing/incoming ringing surfaces; 30s server ring timeout (authoritative) + 40s defensive client timer |
| 4 | Accept | ✅ | ✅ | accept → AcquireMedia → MediaReady → ApplyRemoteOffer+CreateAnswer → connecting |
| 5 | Decline | ✅ | ✅ | call:reject → caller gets call:reject, callee gets call:cancel (relay-proven) |
| 6 | Cancel | ✅ | ✅ | caller abort while ringing → call:cancel to BOTH sides (E2E-proven) |
| 7 | Busy/rejected | ✅ | ✅ | relay callByUser map: second caller gets call:cancel reason=busy (E2E-proven); client-side busy guard also rejects |
| 8 | Timeout | ✅ | ✅ | 30s server ring timeout cancels for BOTH parties (E2E-proven, real timer) |
| 9 | Missed call | ✅ | ✅ | callee sees Missed (cancel/timeout pre-answer); caller row per single-writer mapping |
| 10 | Connected state | ✅ | ✅ | peer-connection state → CONNECTED; duration ticker starts |
| 11 | Mute/unmute | ✅ | ✅ | track enabled toggle; UI mirror + status line "· muted" |
| 12 | Speaker/audio-route | ✅ | ✅ | Android: setCommunicationDevice (API 31+) / setSpeakerphoneOn; iOS: AVAudioSession overrideOutputAudioPort |
| 13 | Call duration | ✅ | ✅ | connectedAt→end tick (m:ss / h:mm:ss), relay-computed durationSec on hangup |
| 14 | End call | ✅ | ✅ | call:hangup + ReleaseMedia (pc close, mic release, focus abandon, FGS stop, session restore) |
| 15 | Reconnect/recovery | ✅ | ✅ | machine keeps media on signaling loss; disconnected 10s grace; stale-state 45s cleanup; offline queue flush on reconnect |
| 16 | Call history/log | ✅ | ✅ | single-writer REST /api/calls + local cache + offline queue; CallsView/CallsHistoryView render all 8 cases |
| 17 | Permission handling | ✅ | ✅ | RECORD_AUDIO runtime gate (Android launcher per entry point); engine-owned mic permission on iOS with honest denied state |
| 18 | Audio-session lifecycle | ✅ | ✅ | Android: MODE_IN_COMMUNICATION + AUDIOFOCUS_GAIN acquire/abandon; iOS: .playAndRecord+.voiceChat with SAVE & RESTORE of prior session |
| 19 | WebRTC offer/answer | ✅ | ✅ | SDP round trip E2E-proven through the real relay |
| 20 | ICE candidate exchange | ✅ | ✅ | trickle + early-candidate queue drained after remote desc; flat triple on the wire (E2E-proven both directions) |
| 21 | Connection-state handling | ✅ | ✅ | ICE/PC state → machine (connected/disconnected/failed); idempotent at the machine |
| 22 | Failure/recovery UX | ✅ | ✅ | honest error cards (mic denied, media failure), ended-card summaries, disconnect teardown (E2E-proven) |

**Scope decision (documented, not a simplification):** `kind='voice'` is fully native. `kind='video'` is accepted on the wire end-to-end but the native UI renders the audio-call surface — camera capture/UI is not among the 22 wave items.

---

## 2. SIGNALLING VERIFICATION (socket)

Contract: `packages/protocol/src/contracts.ts` call:* family (22 C→S / 27 S→C include the six call events; the contracts.ts `durationMs` drift is documented — native clients follow the WIRE `durationSec`, as the relay and web client do).
Backend: **NO backend changes were needed** — the relay (`mini-services/pulse-socket`, R33-a) already implements the complete identity-gated call session logic.

**Live gate — `apps/qa/wave3-call-gate.js`: 17/17 PASSED** through the REAL edge (:81, XTransformPort=3003), REAL relay, REAL REST API:
offer (identity intact) · answer (SDP round trip) · ICE flat triple (B→A) · hangup (server-computed durationSec) · reject routing (caller→call:reject, callee→call:cancel) · caller cancel→BOTH · busy · offline · identity-gate anti-spoof (spoofed `from` dropped) · 30s ring timeout→BOTH · disconnect teardown · caller POST /api/calls→201 · caller GET (outgoing=true+duration) · callee GET (outgoing=false+resolved peer) · non-participant POST→400 · seeds/joins.

**Real-device state machines proven at three levels:** pure state machine (Android 31 JVM tests / iOS 31 XCTest, fake clocks) · relay behaviour (live E2E above) · wire fidelity (DTO round-trip tests 19 incl. all six call payloads).

## 3. WEBRTC VERIFICATION

- Android: `io.getstream:stream-webrtc-android:1.3.10` (verified on Maven Central; AAR inspected — PeerConnection.IceServer, org.webrtc.audio.JavaAudioDeviceModule). UNIFIED_PLAN, GATHER_CONTINUALLY, 2× Google STUN, JavaAudioDeviceModule with HW AEC/NS.
- iOS: `stasel/WebRTC` SPM pinned `from: "125.0.0"` (resolved exactly 125.0.0; the delegate conformance was verified against the PINNED xcframework's own headers — the nine pre-@optional methods are REQUIRED).
- Peer-connection lifecycle (offer→answer→ICE→connected→media→hangup) is CI-verified at the state-machine and wire level. **Audio actually flowing between two physical devices is a HARDWARE gate and is explicitly NOT claimed** (see §6). The sandbox has no mic, no peer device, and no TURN — the UI saying "Connected" is NOT treated as proof, per directive.

## 4. CALL LOG (all 8 directive cases)

Persistence is SERVER-side via REST `/api/calls` (web R33-a contract, single-writer rule: the CALLER writes every row; wire statuses completed|missed|declined × outgoing flag = the 8 cases):
outgoing accepted → (outgoing, completed, duration) · outgoing declined → (outgoing, declined) · outgoing cancelled → (outgoing, missed) rendered "No answer" · incoming accepted → (incoming, completed, duration) · incoming declined → (incoming, declined) · timeout → caller-side missed · missed → callee-side missed · completed → any connected call that ended normally with duration.
Native caches mirror the endpoint offline (Android Room v7 `callLogCache`, iOS GRDB v5 `callLogCache`) with an offline single-writer queue (UNIQUE payload dedupe, FIFO, stop-at-first-network-failure, flush on start/reconnect). Verified: REST E2E rows (12a–12d), store round-trips, migration survival, queue dedupe/attempts.

## 5. TESTS + CI EVIDENCE

| Gate | Result |
|---|---|
| Android JVM (local, Temurin 21) | **79 tests, 0 failures** — CallStateMachineTest 31/31, SocketContractsTest 19/19, SocketRoundTripTest 5/5, LiveGatewayParity 2/2, FlushOutbox 5/5, SendMessage 4/4, PollPick 5/5, ChatMessageDtoParity 7/7, Wave2Dto 6/6 |
| Android CI (tag run) | ✅ build (JVM + signed release APK) + ✅ instrumented (API-30 emulator: launch smoke, Room v3→v7 migrations incl. NEW v6→v7 case, outbox/draft/callLog DAO round-trips) |
| iOS CI (7bb59b5 + tag) | ✅ **92 tests, 0 failures, 3 env-skips** (state machine 31, mapper, store, REAL-relay signaling round-trip, all prior suites) + ✅ unsigned xcarchive + artifacts |
| Live signalling E2E | ✅ 17/17 (real edge + relay + REST) |
| Release | ✅ tag `v0.5.0-native` → GitHub Release `Pulse-v0.5.0-native.apk` (34,951,074 B, zip-integrity OK, libwebrtc JNI present, sha256 `46d296f6…cf59`) → CDN commit (download/Pulse.apk + update-manifest.json versionCode 13, four-source hash equal) |

15 CI rounds were driven to green; every fix is documented in the worklog (nested types, WebRTC package paths, ObjC→Swift delegate label imports verified against the pinned headers, continuation typing, migration chaining, relay-room join races).

## 6. HARDWARE VERIFICATION REQUIRED (explicitly NOT claimed)

The sandbox has no physical devices. The following REQUIRE physical evidence for final acceptance:
1. Physical Android device: mic permission → capture → remote audio → mute → unmute → speaker/Bluetooth route → hangup → mic release (full directive loop).
2. Physical iPhone: the same loop + AVAudioSession configuration AND restoration under real routes (earpiece/speaker/Bluetooth), interruptions (Siri/call), route changes mid-call.
3. Cross-platform: Android ↔ iPhone device-to-device call with two-way audio (A calls B, B accepts, audio both directions, A mutes, B verifies, B ends, both idle, call log persists) — the directive's minimum live script.
4. Android audio-focus arbitration vs other apps; foreground-service (microphone type) behaviour on-device; incoming-call notification surfaces.
5. Deployment prerequisite for cross-network calls: the manifest `gateway`/`socket` keys are still empty and there is NO TURN — STUN-only NAT traversal works on permissive networks (same Wi-Fi expected OK); a production relay/TURN deployment is required for the hardware phase.

## 7. KNOWN LIMITATIONS (honest)

- Voice-only native UI (video accepted on the wire; not in the 22-item scope).
- No CallKit on iOS (in-app overlay only; no VoIP push) — recommended as the first hardware-phase follow-up.
- STUN-only ICE (no TURN) — cross-network traversal not guaranteed.
- iOS incoming ring only while the app is foregrounded (no CallKit/push path yet).
- Web single-writer quirk preserved on the wire (caller-cancel logs 'missed'); native labels render it honestly ("No answer"/"Missed").

## 8. EXACT NEXT-WAVE RECOMMENDATION

**WAVE 3-HW (hardware verification pass, ~1 focused session):** deploy/reach a relay + TURN for the devices, set the manifest gateway/socket keys, then execute the §6 list on the physical devices (Android ↔ iPhone cross-platform loop, mute/route/session-restore checks, call-log persistence on device) — no new feature work. Only after that does Wave 3 earn "complete". THEN Wave 4 per the roadmap (Stories / Voice Rooms / Hub / Games family) — not started here, per the directive.

**STOP.** No Wave 4 work was started.

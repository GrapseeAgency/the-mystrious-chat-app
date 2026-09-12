# 07 — CAPTIONS (CAP-1…CAP-5)

Live ASR captions: 4 s (64 000-sample) WAV windows at 16 kHz, ≥16 000
tail flush, single-flight request, `POST /api/voice/transcribe`
(participant-gated, 512 KB cap, 60 s timeout), server-stamped
`voice:transcript`, 7 s TTL keep-3, persisted toggle
(`voice.captions`). Expected values from the shipped implementation.

---

### CAP-1 — Microphone capture feeding captions
TEST: Enable captions in the voice surface; transmit.
→ A: captions toggle ON → PTT-hold and speak two clear sentences.
→ B: captions toggle ON; listens.
→ Topology: T1.
→ Expected: capture keeps flowing to the caption accumulator while transmitting (mic path unchanged — captions never block or delay the voice chunks); OS mic indicators behave exactly as VR-4.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### CAP-2 — WAV window generation
TEST: Talk >8 s continuously; then a <1 s tail.
→ A: hold PTT 10 s of speech; release. Repeat with a 0.5 s blip.
→ Topology: T1.
→ Expected: windows cut every 64 000 samples (4 s at 16 kHz) with valid 44-byte RIFF headers; on release a ≥16 000-sample tail flushes (the 0.5 s blip still produces one request); no request is emitted for empty capture.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence: (logcat/Console lines + optional mitm/charles capture, or server-side transcribe logs)

### CAP-3 — Transcription request round-trip
TEST: Observe the network round-trip.
→ A: speak with captions on.
→ B: receives the transcript event.
→ Topology: T2.
→ Expected: `POST /api/voice/transcribe` (participant-gated) succeeds → server-stamped `voice:transcript` broadcast → B sees A's caption strip update; sender-gated so only room participants receive it; requests are single-flight (no pile-up while one is in flight).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### CAP-4 — Caption display + persistence
TEST: Watch captions live; toggle off; restart app.
→ A: read captions on both devices during speech; toggle captions OFF; kill + relaunch app; rejoin.
→ Topology: T1.
→ Expected: captions render with ~7 s TTL, max 3 kept (old ones fade out); toggle OFF stops accumulation immediately (clear-on-off); the toggle preference persists across restart (`voice.captions`).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### CAP-5 — Failure behaviour
TEST: Break transcription on purpose.
→ A: captions ON; transcribe route disabled server-side (or TLS broken after windowing) → speak.
→ B: watches A's captions.
→ Topology: T1 (route broken), then T3 (network drop mid-flight).
→ Expected: honest silence — no caption renders, NO crash, mic/voice path completely unaffected (captions never block the mic by design); single-flight does not queue unbounded retries; after the failure clears, the next window round-trips normally.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

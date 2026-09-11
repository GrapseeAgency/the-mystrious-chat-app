# 04 — TEST MATRIX (mandatory calls + 16-case failure matrix)

Every case uses the mandated template and gets its own row in `RESULTS.md`.
EXPECTED is pre-filled from the actual Wave 3 implementation (relay semantics
in `mini-services/pulse-socket`, state machine in `CallStateMachine`, server
ring timeout 30 s, single-writer call-log contract: caller-cancel → caller
(outgoing, missed) rendered "No answer", callee (incoming, missed)).

Template (copy per case into RESULTS.md):

```
TEST <id> <name>
→ DEVICE A: <model / OS / role / network>
→ DEVICE B: <model / OS / role / network>
→ NETWORK:  <A: wifi|mobile-data carrier> × <B: wifi|mobile-data carrier>
→ EXPECTED: <pre-filled below>
→ ACTUAL:   <observed>
→ PASS/FAIL:
→ EVIDENCE: <log file:line / screenshot / screen video / dumpsys excerpt>
```

## MANDATORY CALL TEST — run in topology 1, then topology 2 (per §6 of infra)

**M1 Android → iPhone.** Steps and expected:
1. A (contacts → call button) → B receives full-screen incoming overlay with
   A's real identity (name/username, not a placeholder).
2. B taps accept → mic permission if not yet granted → B transitions
   ringing → connecting → connected; A same.
3. **Two-way audio:** each operator speaks a random number sequence; the
   other repeats what was heard. Both directions verified by a human, not an
   indicator. (Zero audio / one-way audio = FAIL with recording attached.)
4. A mutes → B confirms A's audio stops within ~1 s (B's UI "· muted" mirror
   is secondary; the EAR is the evidence). A unmutes → B confirms return.
5. Audio route switch on B (speaker ↔ earpiece) → route audibly changes and
   UI reflects it.
6. B ends the call → both sides show ended summary with duration; both return
   to idle; NO audio continues on either device.
7. Mic release: Android `dumpsys media.audio_flinger` shows no Pulse input;
   iOS: another app can record immediately (session restored).
8. Call log: A outgoing-completed with duration; B incoming-completed with
   duration; server `/api/calls` rows match both.

**M2 iPhone → Android.** Same 8 steps, roles swapped. Same evidence rules.

## FAILURE MATRIX (F1–F16, both devices where the case is symmetric)

| ID | Case | EXPECTED (from implementation) |
|----|------|--------------------------------|
| F1 | Mic permission denied | Attempt shows honest mic-denied card on the caller surface; no offer leaves the device; no call row written |
| F2 | Permission granted after denial | Re-attempt connects normally; no stale denial state |
| F3 | Caller cancels while ringing | Caller gets ended (cancel); callee ring stops via call:cancel; history: caller (outgoing, missed "No answer"), callee (incoming, missed) |
| F4 | Callee declines | Caller receives call:reject → ended (rejected); callee returns idle; history: caller (outgoing, declined), callee (incoming, declined) |
| F5 | Callee busy (second incoming while active) | Second caller is terminated with busy (relay callByUser map → call:cancel reason=busy); callee's active call is untouched |
| F6 | Ringing timeout (no answer 30 s) | Server ring timeout cancels BOTH sides; history as cancelled/missed per mapping |
| F7 | Network interruption during ringing | Offender's socket drops; 45 s stale-state cleanup ends the call on both; no zombie ring |
| F8 | Network interruption during active call | Media path survives brief signaling loss (machine keeps media); >10 s disconnected grace → ended honestly; no silent dead call |
| F9 | Temporary Wi-Fi loss (off→on, same call) | ICE recovers or call ends with explicit state; UI never stuck at "connecting" |
| F10 | Mobile-data transition (if available) | Call ends honestly or reconnects; record actual behaviour per platform |
| F11 | App background → foreground mid-call | Call continues, audio continues, duration intact on return |
| F12 | Screen lock → unlock mid-call | Same as F11; Android mic FGS keeps capture alive (verify no OS mic kill) |
| F13 | Incoming call while app backgrounded | Record ACTUAL behaviour — known iOS limitation: in-app ring requires foreground (no CallKit). If B never sees it, that is a FAIL/finding, honestly recorded |
| F14 | Audio route change mid-call (headset/BT plug) | Route switches; audio continues on the new route; no capture drop |
| F15 | Microphone interruption (Siri/voice-recorder/Timer) | Focus arbitration: interruption handled, then resume or honest end — no permanently muted ghost call |
| F16 | Call terminated from either side | Both sides idle, media torn down, focus/session restored, history written by the single writer |

Rule: if ACTUAL ≠ EXPECTED, the case is FAIL, gets a screenshot/log excerpt,
and only the failing call-path issue is fixed (directive). Then the whole
hardware gate repeats for the affected platform.

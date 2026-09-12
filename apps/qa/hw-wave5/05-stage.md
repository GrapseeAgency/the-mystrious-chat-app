# 05 — STAGE (ST-1…ST-9)

Stage = host / speakers / listeners with hands. Expected values from the
shipped implementation (first-joiner host, FIFO hands, voice seat for ALL
stage roles = fixed web defect #3, two-tap end 2600 ms).

---

### ST-1 — Host
TEST: A opens Stage first; B joins after.
→ A: mic entry → Stage.
→ B: joins the same stage.
→ Topology: T1.
→ Expected: A (first joiner, no host present) becomes HOST automatically; B lands as LISTENER (listener count visible); stage:state on both devices matches (host=A, speakers=[], listeners=[B]).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-2 — Listener
TEST: B as listener receives real downlink audio.
→ A: host speaks via PTT.
→ B: listens.
→ Topology: T2.
→ Expected: B hears the host (voice seat for all stage roles — audience audio/glow works, unlike the copied web defect); B has no PTT transmit while listener (attempt is gated/honest).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-3 — Raise hand
TEST: B raises hand.
→ B: Raise hand.
→ A: observes the hand queue.
→ Topology: T1.
→ Expected: optimistic raised state on B; A sees B in hands (FIFO order); every stage:state reconciles the optimistic hand to server truth (unconfirmed raise does not glow forever).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-4 — Approve (host-only gate)
TEST: B (listener) attempts approve; then A approves.
→ B: attempts to approve another hand (needs a third participant C, or B tries A's self-controls) — expect rejection.
→ A: approves B's hand.
→ Topology: T1.
→ Expected: non-host approve is rejected (server gate — relay-verified: listener-approve rejected); host approve promotes B to speaker; hands queue shrinks FIFO-first.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-5 — Speaker promotion (real uplink)
TEST: B as speaker transmits real audio.
→ A: approves B → B PTT-holds and speaks.
→ Topology: T2.
→ Expected: B's mic becomes live (host auto voice-join for speakers); A hears B; B's glow shows for A; B demotes… no — B stays speaker until muted/ended.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-6 — Host mute
TEST: A mutes speaker B mid-transmission.
→ B: latch/speak.
→ A: host-mute B.
→ Topology: T1.
→ Expected: B demoted to listener; forced `voice:ptt off` from the server; B's voice seat re-arms (B still hears A — seat enforcement roster broadcast); B's glow clears for everyone.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-7 — End stage
TEST: A ends the stage with the two-tap confirm.
→ A: End → confirm within 2600 ms.
→ B: observes.
→ Topology: T1.
→ Expected: `stage:ended` tears the stage down on BOTH devices (no stale stage UI); voice room membership ends with it (or shows honest post-end state — record actual); mic released per VR-9 evidence path.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-8 — Remote state propagation
TEST: Full role churn observed identically on both devices.
→ Sequence: B raises → A approves → A mutes → B raises again → A approves.
→ Topology: T1.
→ Expected: every stage:state propagates host/speakers/hands/listeners/listenerCount identically to both screens within a network RTT; reconnect resync = 2500 ms missing-me rule restores B's membership after a network blip (ST-9/T3 overlap acceptable as separate evidence).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ST-9 — Real audio both directions
TEST: Two-speaker conversation.
→ A (host-speaker) and B (promoted speaker) converse ~60 s.
→ Topology: T2.
→ Expected: both hear each other with conversational latency; listener audience (optional C) hears both; no echo/feedback loops beyond device echo (record hardware echo separately if the device lacks AEC).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

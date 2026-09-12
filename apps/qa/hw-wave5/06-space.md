# 06 — SPACE (SP-1…SP-6)

Spatial rooms: tap+drag map, 80 ms throttle, clamp 0…1, proximity ≤ 0.18,
300 ms-idle reconcile, honest error after 6 failed reconnect attempts,
server 5-min idle prune. Expected values from the shipped implementation.

---

### SP-1 — Join / leave
TEST: A and B open Space; then both leave.
→ A: mic entry → Space. B: same.
→ Topology: T1.
→ Expected: join lands self at center (0.5, 0.5); both see both avatars; "N in room" pill correct; leave prunes the leaver on the other device.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### SP-2 — Avatar movement
TEST: Move own avatar both interaction ways.
→ A: tap-to-move to a far corner; then drag continuously (slow + fast flicks).
→ B: same.
→ Topology: T1.
→ Expected: self avatar shows the optimistic target immediately (halo ring); positions clamp inside 0…1 (dragging past the edge cannot escape); fast flicks are throttled to the 80 ms server budget without freezing the UI.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### SP-3 — Realtime position propagation
TEST: A moves; B observes; then idle.
→ A: drag across the map in one gesture, then stop.
→ B: watches.
→ Topology: T2.
→ Expected: B sees A's dot track the gesture in near-realtime (≤ ~150 ms per accepted update); when A's finger idles ≥300 ms A's dot snaps/reconciles to the server-confirmed self position; 4-decimal precision (no jitter loops).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### SP-4 — Proximity behaviour
TEST: Gather and separate.
→ A: move onto B's position; then move away (euclidean > 0.18).
→ B: watches the NEARBY rail.
→ Topology: T1.
→ Expected: euclidean ≤ 0.18 → emerald NEARBY ring + both appear in the NEARBY chip rail (self excluded); moving apart clears the ring/rail; the chip copy matches web parity ("N people in the space — move closer to gather.").
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### SP-5 — Reconnect
TEST: Network drop mid-space.
→ A: in space; airplane ON 10 s; OFF.
→ B: stays connected.
→ Topology: T3.
→ Expected: A attempts reconnect (up to 6 failed attempts → honest error state with Retry; Retry resets attempts and re-joins); on recovery A's position resyncs from the server; no ghost duplicate of A on B's map.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### SP-6 — Background / foreground
TEST: Background the app in a space; return.
→ A (iPhone): home key 20 s → return. Record ACTUAL (documented limitation: no UIBackgroundModes audio — engine suspends by OS policy; expect honest re-sync on return, no corrupt state).
→ B (Android): home key 20 s → return; record ACTUAL.
→ Topology: T1.
→ Expected: both return to the space with correct positions/counts (state re-synced from server); server may prune a participant after 5 min idle — if a long background prunes A, the return re-joins honestly (record which behaviour occurred; no crash, no stale avatar).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

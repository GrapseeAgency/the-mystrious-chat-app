# 06 — SECURITY / IDENTITY CHECKS (on-device)

The relay already enforces identity (E2E-proven in Wave 3: spoofed `from`
dropped, non-participant POST → 400). The device phase re-verifies what the
user can actually observe:

| # | Check | Method | Expected |
|---|-------|--------|----------|
| S1 | Caller identity correct | B receives A's real display identity, not a stale/wrong peer | exact match |
| S2 | Callee identity correct | A's call targets the user A picked; B is that user | exact match |
| S3 | callId consistency | grep callId in A and B logs for the same call | identical on both sides + server |
| S4 | No cross-user call leakage | Two accounts on each device across the phase; every ring/offer is addressed to the signed-in viewer | never a ring for another viewer |
| S5 | Rejecting spoofed identities | (relay-level re-run) `apps/qa/wave3-call-gate.js` against the DEPLOYED gateway with a forged `from` | relay drops it; devices unaffected |
| S6 | Terminal state idempotent | Re-send/observe duplicate hangup/reject (airplane-mode the ending side before its socket flushes, restore) | second terminal event is a no-op; no second history row, no UI regression |
| S7 | History is single-writer | After every call, GET `/api/calls` for both accounts | caller's row exactly once per call; no duplicate rows from the callee |

Evidence: log excerpts + server JSON (jq output) per case, appended to
`RESULTS.md` §S.

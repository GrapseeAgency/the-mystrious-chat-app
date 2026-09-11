# 07 — CALL HISTORY VERIFICATION (physical calls → persistence)

Wire contract: statuses `completed | missed | declined` × `outgoing` flag =
the 8 rendered cases; single writer = the CALLER (`POST /api/calls`); native
caches mirror the endpoint offline (Android Room v7 `callLogCache`, iOS GRDB
v5 `callLogCache`) with the offline single-writer queue.

For each mandatory/matrix call, after BOTH devices return to idle:

```bash
curl -s "https://pulse.example.com/api/calls?viewerId=<A>" | jq .
curl -s "https://pulse.example.com/api/calls?viewerId=<B>" | jq .
```

| Call scenario | Caller row (expected) | Callee row (expected) | On-device check |
|---------------|------------------------|------------------------|-----------------|
| Accepted, normal hangup | outgoing · completed · durationSec>0 | incoming · completed · same duration | Calls tab on both devices shows the row + label |
| Declined (F4) | outgoing · declined | incoming · declined | labels correct both sides |
| Cancelled while ringing (F3) | outgoing · missed → rendered "No answer" | incoming · missed → rendered "Missed" | both rows exist, no "completed" |
| Timeout (F6) | caller-side missed | callee-side missed | server rows match mapping |
| Missed (F13 / ring-while-away) | caller-side missed | callee missed row | as mapping |

Offline-persistence drill (extra, ties to Wave 1/3 queue): end a call with
the writer side offline → row must flush on reconnect (queue UNIQUE dedupe,
stop-at-first-failure, flush on start/reconnect) — verify exactly ONE row
lands, timestamps sane.

Also verify the local cache matches the server after airplane-mode reopen
(cache-first read, then server reconcile) on both platforms.

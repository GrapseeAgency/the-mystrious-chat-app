# Seen / unread / read-ordering workstream

Scope: the nine items selected by the user, Android + iOS + backend. This is not a completion certificate.

## First repair: explicit manual intent versus delayed viewport receipts (2026-10-26)

A message timestamp is not an intent revision. Previously an equal/newer read receipt could clear a manual-unread marker written while that request was in flight.

- `ReadIntentState` holds one opaque revision per **membership**, not per user-provided clock. The read-intent GET checks exact owner/current membership and returns a private/no-store snapshot. Manual marker writes rotate that revision, including repeated values/ABA. An explicit mark-all rotates it too.
- Version-2 native read/marker writes carry the captured revision and validate its echo. A stale visible receipt can advance legitimate read progress but cannot clear the newer manual marker. A stale explicit mark-all/on/off returns 409 without changing the state. A marker write cannot be impersonated through the old missing-token bypass.
- Android primary/mounted room and thread readers retain the prepared token per boundary for their surface lifetime, including disconnected/connected effect restarts. Swift's session read gate retains it across failed ACKs/reconnect dwell restarts and rejects a retired preparation. They do not silently acquire a newer revision to replay an old visible write. New room/session lifetime permits a fresh capture. Removed/rejoined membership cannot reuse its previous fence.
- The repository's default visible-read implementation no longer falls back to mark-all. Unsupported routes/envelopes fail closed rather than claiming broad reading.
- Legacy `/read` requests can advance the historical timestamp but cannot clear a manual dot without the new fence. New `/read-state` and marker mutations require the new intent. This intentionally requires coordinated client/server rollout; there is no silent unsafe fallback.

### Exact limits

This orders requests relative to the server snapshot they prepared against, **not absolute physical tap time across devices**. A request that has not yet obtained its initial snapshot has not reserved an earlier server intent. An explicit user retry after a conflict is a new attempt and may capture current state; there is no automatic mutation retry that guesses what won.

Read timestamps remain cumulative and timestamp-only. This patch does not solve equal-millisecond message identity, unseen gaps, per-thread/topic read ranges, durable offline receipt replay, stale cross-device UI projection, or every visibility/device scenario. Prepared native tokens are in-memory, not a durable offline read journal. A lost ACK for an explicit action is reported as uncertain/conflicting rather than fabricated success.

### Rollout

1. Apply `prisma/patches/20261026_read_intent_state.sql` (additive/rerunnable, no changes to existing read watermarks/markers). Membership deletion cascades its fence.
2. Deploy the matching backend/Prisma client and native protocol changes together, with web read callers migrated deliberately. Old unfenced marker clearing is not preserved as a bypass.
3. Verify latest-candidate native/runtime/device results. No deployment or merge is claimed.

Prisma's checked-in client was regenerated from the actual schema with the same 6.19.2 generator; it also catches up declarations for previously added schema models. No engine binary replacement is included.

## Nine-item status

| # | Item | Current status / next closure evidence |
|---|---|---|
| 1 | Manual-unread versus delayed reads | Server + both native implementations repaired for prepared-request/retry ordering; exact-candidate native/device verification pending. |
| 2 | Equal-timestamp boundaries | Open. Needs identity-bearing progress consistently in server counts, native storage, receipts and Seen rendering. |
| 3 | Unseen gaps | Open. Cumulative timestamps still imply progress across undisplayed rows; must not call this individually read-message proof. |
| 4 | Thread/topic granularity | Open. Requires a consistent per-scope/range model rather than a client-only counter patch. |
| 5 | Durable offline read intent | Open. Current boundary token lifetime is not persistent. Replay must retain its original intent and authority, not acquire fresh permission after reconnect. |
| 6 | Cross-device read ordering | Explicit manual/all mutations now have a server conflict fence. Broader read progress, late UI projection and multiple-device acceptance remain open. |
| 7 | New-member history | Open review. Existing join-time timestamps remain unchanged; same-time/backfill/history policy needs explicit coverage with item 2. |
| 8 | All obscuring surfaces | Existing lifecycle/lock/dwell/thread ownership gates retained; full OS/modal/call/accessibility acceptance not established. |
| 9 | Badge convergence | Owner read events still trigger authoritative inbox refresh, not blind zeroing. Paired-device/app badge convergence is not certified. |

## Evidence

Actual route + SQLite tests cover tokenless/foreign owner rejection, missing intents, equal/newer delayed receipts, lost-ACK visible retry, repeated desired values/ABA, stale explicit-all/on/off, fresh reopen, leave/rejoin, legacy unfenced behavior and additive/rerunnable/cascading migration. Kotlin transport/production retry-holder and Swift transport/production read-gate regressions cover receipt identity and retained token ownership. Syntax/TypeScript checks do not constitute native/device execution.

Validation at this publication: **419 full backend tests / 407 top-level PASS**, **64 targeted actual-route/SQLite tests PASS**, strict affected TypeScript graph PASS and **17 changed/new native syntax parses PASS**. Native compiler/unit/device execution for this change is pending; earlier green checks are not applied to this patch.

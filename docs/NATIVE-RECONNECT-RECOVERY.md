# Native reconnect and history recovery

## Behavior repaired

A `since=newestCreatedAt` request returned only the newest window. With more than one page of missed messages, both native clients could skip the middle permanently; cached edits and soft deletions were not revisited. Timestamp-only history paging also skipped rows when a page boundary fell inside a group sharing a timestamp.

The native clients now reconcile from the **oldest cached server row**, inclusively, following `nextSyncCursor` until completion. Each page is ordered by `(createdAt, id)` ascending and bounded by the upper position captured on the first page. The existing first-load newest window remains bounded. Older-history queries pass `beforeId` alongside `before` to disambiguate timestamp ties.

- Matching server IDs are updated, including edited rows and tombstones. Identical text in different operations is never deduplicated.
- Local pending operations survive refresh.
- Android now persists live deletion envelopes rather than only broadcasting a UI event.
- Live events received after a page request began protect those message IDs from being overwritten by the stale page. Kotlin checks/applies under a shared cache mutex; Swift checks the session's realtime revision on the main actor.
- Foreground/reconnect refreshes are owned by room lifecycle. Swift cancels on disappearance/background and ignores replaced session/refresh responses. Kotlin cancels collection outside RESUMED and rechecks on reconnection.
- Invalid/repeated/missing paging cursors fail visibly instead of looping or claiming synchronization completed.
- Old servers that ignore the new query do not masquerade as supporting reconciliation: native clients require `syncVersion: 1` for a range sync and retain their existing cache on failure.

Deploy the route update **before** native builds. No new server database table is needed for this paging change. The separate message-operation receipt migration remains required for reliable send retries.

## Verification

`node --experimental-transform-types --test tests/server/*.test.mjs` passes 10 SQLite-backed/contract cases locally. New coverage includes 450 rows with identical timestamps across multiple pages, bounded concurrent arrival, cached edit/tombstone refresh, timestamp-tied backward history, and malformed/mismatched cursors. Native tests cover query encoding, optional version decoding, pending-operation retention, edited/tombstoned rows, topic changes and stable ordering. Native CI/device results must be checked for the exact candidate.

The concurrency send test was corrected: SQLite may roll back **all** simultaneous contending transactions. The first uncontended retry can create the original row, followed by a replay. Assertions still require one message/receipt, equal IDs for successful attempts, correct replay, and only recognized transaction/contention failures.

## Remaining limits

This is cached-range reconciliation, not a durable server change log or a cross-request transactional snapshot. It does not infer hard deletions from absent rows, import unvisited older history, or reconcile conversation membership removal. Mutations to already-traversed rows during the scan converge on a subsequent refresh. Export's existing bounded history sweep also needs separate completeness handling. Paired-device and network/process-death acceptance scenarios remain unrun.

Account sign-in/recovery work is deferred at the user's direction; none of these changes claims to resolve server authentication.


## Additional fetched-page ownership guards

- Android history, search, pinned-message and thread fetches now merge under the same live-event mutex. If a socket edit/delete occurred after the GET began, callers receive the newer cached row rather than the stale response as well as avoiding a cache overwrite.
- Swift older-history and bounded jump expansion capture client/store/topic/session generation, use cancellable room tasks, and reject stale responses after navigation/topic changes. Topic reload preserves cached/pending rows and applies the same live-event revision check.
- These guards do not cover every ancillary fetch/mutation. They are not a cross-request server snapshot or a durable changelog. Sparse history consisting only of hidden thread replies still needs independent raw-page cursor handling; the rendered oldest-row anchor can otherwise repeat a page.

# History, search, mentions and saved content — implementation work

Deployment and physical-device auditing are deferred by the owner. Completion in
this workstream means implemented native/backend paths plus code-level validation,
not a guarantee that no undiscovered defects exist. Autumn styling is unchanged.

## Work ledger

1. **Old-message jump boundary:** replacing fourteen-page scans with indexed,
   authorized exact-message windows in Android primary/Mirror and the shared iOS
   room model. Sparse jumps do not move the ordinary contiguous history cursor.
2. **Server anchor/window lookup:** `/message-window` supports exact anchors and
   both tuple-cursor directions; parent/membership/expiry checks share a snapshot.
   Additive navigation indexes are in `20261028_history_navigation.sql`.
3. **Very large threads:** Android primary/Mirror and iOS thread screens now
   request and replace bounded windows, with earlier/later navigation. Exact
   reply jumps no longer assemble the complete thread. Android observes only the
   selected cached replies plus pending local sends and a single root; offline
   tuple slices preserve saved-history browsing. iOS replaces its cached page
   rather than accumulating it, with both older/newer offline navigation.
   Underlying Android primary/Mirror rivers now exclude unselected thread rows;
   reply counts use a live SQLite aggregate with expiry deadlines, not decoded
   reply arrays. Exact cached jumps use single-row observations. The new paths
   still require native SDK/instrumented validation.
4. **Cross-page reconciliation:** displayed windows are refreshed, rather than
   claimed to be a single immutable full-thread snapshot. Position-based centers
   survive deleted anchors and include same-time backfills. Reconnect/event and
   foreground periodic refreshes retain the current reader center; existing
   cache/session/revision fences remain. Mounted viewport behavior still needs
   validation in the native runners; deferred device auditing is separate.
5. **Streaming exports:** implemented bounded authorized export pages and direct
   temporary-file writers on Android/iOS. No whole-conversation array/string or
   50,000-row export ceiling. Scan positions advance through expiring pages;
   ownership, write errors, cancellation and incomplete completion delete the
   unshared partial file. Destructive clear keeps its separate bounded policy.
6. **Exhaustive global search:** implementation work remains.
7. **Exhaustive mentions:** implementation work remains.
8. **Exact mention entry points:** Android primary/Mirror callbacks and the iOS
   mention sheet now pass the message identity through the room-navigation path.
   Exact reply navigation now uses the bounded thread window too.
9. **Mention badge freshness:** implementation work remains.
10. **Saved pagination:** implementation work remains.
11. **Durable ordered saved state:** implementation work remains.

Window tests use real Prisma/SQLite, 16,000-reply traversal, equal-time rows,
deleted centers/cursors, backfills, thread/root checks, and authorization/expiry
failures. Missing reply errors are separate from bound root refusal, preventing
thread-denial poisoning. Native window decoders reject duplicates, wrong order,
scope and malformed continuation metadata. Added native transport and bounded
Room observation tests; they have not yet completed in the SDK runners.

Latest full server run: **481 passed, 0 failed, 0 skipped** (469 top-level tests).
Production Android SQL is also exercised against 12,000 cached replies,
selected-row observations, both cursor directions, expiry, and root denial.
Streaming export/mention commit: `8d112ac`; its iOS archive succeeded while other
native jobs were still running at the last inspection. Native SDK validation is
still required for this new implementation. No item is closed merely because a
historical backlog label was removed.

Android SDK compilation of `e57c580` caught a missing physical expiry column
(the earlier cache stores expiry in reserved payload keys). Room v19 now adds
and backfills an explicit projection in 200-row batches without SQLite JSON
extensions, registers the migration for every account, and adds cache ordering
indexes. Added native migration coverage for multi-batch/malformed expiries.
Native SDK validation must be rerun for this correction; earlier SQL fixture
passes alone did not establish compatibility with the actual Room schema.

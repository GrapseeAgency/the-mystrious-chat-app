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
6. **Exhaustive global search:** actor/query-scoped fixed-upper tuple pages;
   at most 1,000 scanned rows and 30 results per response. Empty scan windows
   retain continuation. Primary/Mirror Android and Swift replace bounded pages,
   retain navigation cursors, hide expired/changed hits, and retire old-account
   results. No historical scan/result ceiling ends the search.
7. **Exhaustive mentions:** all-history scan cursors replace the 14-day/per-room
   omission. Both native apps navigate bounded pages, including empty windows;
   profile-name changes invalidate the scoped query rather than reuse its cursor.
8. **Exact mention entry points:** Android primary/Mirror callbacks and the iOS
   mention sheet now pass the message identity through the room-navigation path.
   Exact reply navigation now uses the bounded thread window too.
9. **Mention badge freshness:** live message/access/reconnect/profile hints
   immediately invalidate stale numeric counts. Coalesced authoritative scans
   traverse empty pages until exhaustion or the 99+ display cap, retaining at
   most 100 identities. Expiry deadlines invalidate without waiting for polling;
   errors stay unknown, and owner/scope cancellation fences late completions.
   Constant-memory cycle detection prevents a malformed gateway cursor loop.
10. **Saved pagination:** online tuple pages and offline SQLite keyset pages
    replace the 100-item boundary. Both apps observe only current-page IDs,
    explicitly label cached pages/current-page filters, and preserve markers
    outside partial responses. Offline traversal covers all cached pages,
    including timestamp ties and windows whose bodies expired.
11. **Durable ordered saved state:** Android Room and Swift GRDB atomically
    journal explicit desire plus the local projection before HTTP. Immutable
    action identities and causal predecessors survive restart/lost responses;
    retry order is separate from the causal head. Server compare-and-set
    decimal commit revisions and retained receipts prevent old devices/replays
    undoing newer writes. Both clients retain canonical state behind optimistic
    markers; commit order beats HTTP-start order without restoring visibility
    from an older equal-revision response. Conflicts/rejections are owner/action-bound, persisted and surfaced;
    they are not silently rebased. Logical bookmark state is separate from
    preview visibility, so revoked/expired content cannot be restored by sync.
    Reconnect/foreground/background replay, owner-only live hints and bounded
    reconciliation repair cross-device state, including markers outside the
    current feed page. Partial reads preserve later queued intent.

Window tests use real Prisma/SQLite, 16,000-reply traversal, equal-time rows,
deleted centers/cursors, backfills, thread/root checks, and authorization/expiry
failures. Missing reply errors are separate from bound root refusal, preventing
thread-denial poisoning. Native window decoders reject duplicates, wrong order,
scope and malformed continuation metadata. Added native transport and bounded
Room observation tests; they have not yet completed in the SDK runners.

## Validation and rollout ledger

Latest executed full server run: **497 passed, 0 failed, 0 skipped**
(485 top-level tests; `/home/user/saved-commit-order-full.log`). Real SQLite
coverage includes 6,000 tied feed rows, empty scan windows, historical route
results, 230 saved markers, response-loss replay, save/remove/save causal chains,
conflicting devices, legacy writes, root expiry and membership revocation.

Native regressions include bounded thread/cache windows, 50,100-row streaming
exports, mention scan/expiry/lifetime behavior, strict receipts, durable saved
journals and 2,400-row offline saved traversal. `b414cf0`, `03d4e22` and `0344d10` iOS
archives succeeded. Their Android unit jobs exposed outdated test adapters and
an event-registry expectation; those fixtures are corrected. Current-candidate
SDK/unit/instrumented completion is still being checked. Syntax parsing and
backend passes alone are not native SDK validation.

Android history storage is Room v19, with a physical expiry projection and a
200-row additive/backfill migration registered for every account. Saved journals
reuse durable account-owned metadata and are excluded from cache clearing; no
new native schema version is required for them.

Deployment prerequisites (not applied here):
- `prisma/patches/20261028_history_navigation.sql` for indexed history traversal.
- `prisma/patches/20261028_saved_intents.sql` for causal states/replay receipts.
- Matching API, relay (`saved:changed`, `private-feeds:changed`) and native builds.

No deployment, release replacement, PR merge, or physical-device audit is
claimed by this ledger. The full 16-section app audit is not certified complete.

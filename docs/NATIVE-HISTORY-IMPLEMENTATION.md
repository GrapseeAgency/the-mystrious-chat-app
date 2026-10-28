# History, search, mentions and saved content — section 2 complete

Deployment and physical-device auditing are deferred by the owner. Completion in
this workstream means implemented native/backend paths plus code-level validation,
not a guarantee that no undiscovered defects exist. Autumn styling is unchanged.
All eleven items below are implementation-complete in that scope. The final seek
repair is `e058e51`; its Android/iOS/protocol source trees are unchanged from
`078035f`, whose native validation results are recorded below.

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
   reply arrays. Exact cached jumps use single-row observations. Native
   SDK/unit/instrumented validation has completed as recorded below.
4. **Cross-page reconciliation:** displayed windows are refreshed, rather than
   claimed to be a single immutable full-thread snapshot. Position-based centers
   survive deleted anchors and include same-time backfills. Reconnect/event and
   foreground periodic refreshes retain the current reader center; existing
   cache/session/revision fences remain. Code-level reconciliation regressions
   are covered; comprehensive physical-device viewport auditing remains deferred.
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
   Matching normalizes canonical Unicode spelling and respects letter/number/mark
   boundaries instead of treating non-Latin name suffixes as punctuation.
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
    current feed page. Partial reads preserve later queued intent. Unreadable
    journal rows retain their exact bytes and pending status but rotate behind
    other work instead of blocking every message for the account. Foreground
    first-page repair discovers bookmarks whose live hint was lost; older pages
    stay in place. Access-change hints fence in-flight pages and hide the prior
    projection pending authorization repair. Both clients bound full-cache
    reconciliation passes rather than holding transport behind the entire shelf.

Window tests use real Prisma/SQLite, 16,000-reply traversal, equal-time rows,
deleted centers/cursors, backfills, thread/root checks, and authorization/expiry
failures. Missing reply errors are separate from bound root refusal, preventing
thread-denial poisoning. Native window decoders reject duplicates, wrong order,
scope and malformed continuation metadata. Native transport and bounded Room
observation regressions are included in the SDK validation below.

## Validation and rollout ledger

Latest executed full server run: **508 passed, 0 failed, 0 skipped**
(496 top-level tests; `/home/user/section2-seek-server.log`). Real SQLite
coverage includes 6,000 tied feed rows, empty scan windows, historical route
results, 230 saved markers, response-loss replay, save/remove/save causal chains,
conflicting devices, legacy writes, root expiry and membership revocation.

Native regressions include bounded thread/cache windows, 50,100-row streaming
exports, mention scan/expiry/lifetime behavior, strict receipts, durable saved
journals and 2,400-row offline saved traversal. Full iOS build/test and archive,
Android JVM/release build and API 30/API 34 instrumentation passed for `490888d`.
Android build and both instrumentation levels also passed for `420d513`.
The stricter missing-list contract exposed one older Wave2 fixture that expected
`{}` to mean an empty library; `36e527b` fixes that expectation without weakening
the production contract. Its Android build and API 34 instrumentation passed.
The latest native source is `078035f`, unchanged by the final backend seek repair:
- Android run [40657629654](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40657629654):
  JVM tests and signed release build **passed**; API 30 and API 34 instrumented
  tests and release-smoke checks **passed**. Publishing was intentionally skipped.
- iOS run [40657629719](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40657629719):
  simulator build, unit suite, install/launch smoke, and unsigned device archive
  **passed**. Existing keychain-environment test skips are not physical keychain
  acceptance.
- The final backend candidate `e058e51` also passed server-contract and web checks.

Historical runner failures remain recorded rather than declared causally fixed:
`b414cf0`, `c429739`, and `36e527b` hit the hosted read-presentation fixture with
an unsettled sheet; `91befc0` timed out waiting for the socket presence snapshot.
The current native run passed without relaxing production reading gates or
those test expectations. This does not establish the cause of those older
failures or substitute for the deferred broader device/lifecycle audit.

### Seek efficiency and cursor boundaries

The original OR-based tuple predicates were logically correct but failed to
seek the complete SQLite index on equal timestamps. Message windows and their
edge flags, export, search/mention/saved feeds, legacy history synchronization,
and legacy thread pages now read disjoint equality/ID and strict-date ranges.
Each side uses at most two ranges; a bounded ascending interval uses at most
three, retaining only the requested page. Cursor dates compare by instant, not
spelling, and reversed/out-of-floor sync/export cursors are rejected.

Real Prisma SQL is captured and checked with `EXPLAIN QUERY PLAN`, not merely
counted. The index patch includes `(conversationId, parentId, createdAt, id)` so
main-history reads also seek past a huge reply population instead of filtering
it from a room-only index. Export fixtures cross timestamp groups and retain
fixed upper fences; model-based tests cover gaps, inclusive centers, both
traversal directions and bounded intervals.

A separate 100,000-row SQLite experiment measured approximately 799,800 VM steps
for an old tied OR boundary versus 100 for its equality/ID seek. A main-history
read among many replies fell from approximately 449,900 to 100 VM steps with the
room/parent/time/ID index. These are synthetic query-work measurements, not
production latency guarantees (`/home/user/section2-seek-measurements.txt`).
Prisma client generation remains 6.19.2. Whole-project TypeScript checking still
reports unrelated existing web/tool errors; no diagnostics named the changed
history/feed/seek files (`/home/user/section2-seek-tsc.log`).

Empty saved and mention pages distinguish other pages from globally empty
libraries; cached/offline and current-page filtering states are explicit. The
old Swift 100-save-library claim is removed. Autumn styling is unchanged.

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

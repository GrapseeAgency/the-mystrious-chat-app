# Seen / unread / read-ordering workstream

## Current candidate — exact observations (2026-10-26)

This section supersedes the timestamp-only limitations in the historical notes below.
It records implemented runtime paths, **not a physical-device acceptance certificate**.
Autumn styling and sign-in/recovery scope are unchanged. No merge or deployment is authorized.

| Selected item | Implemented runtime path | Acceptance still required |
|---|---|---|
| 1. Delayed read versus manual unread | Membership/revision fence; one original fence per mounted read session; retries and durable replay never prepare fresh authority. | Exact-candidate native execution and paired-device races. |
| 2. Equal timestamps | Membership/message observation primary key; Seen uses exact membership IDs, not timestamp comparison. Explicit mark-all snapshots real IDs, including timestamp ties. | Native storage/rendering execution. |
| 3. Unseen gaps | All dwell-qualified viewport identities are reported independently. Unread aggregates exclude only exact observations; the divider uses the exact first-unread ID from the same aggregate snapshot. | Scroll/pagination acceptance on both apps. |
| 4. Thread/topic independence | Exact observations cannot sweep sibling scopes. Authenticated scoped read-state GET, thread unread headers in both Android surfaces and Swift, durable per-topic unread counts/labels. | Rendered scope transitions and live/offline refresh. |
| 5. Durable offline replay | Account-owned SQLite journals survive navigation/process restart. Retries retain their original authority; distinct fresh visits have independent journal identities. Offline membership-only authority cannot clear a manual marker; removed/rejoined memberships fail closed. | Process-death/reconnect and credential-switch device runs. |
| 6. Cross-device order | Serializable explicit intent conflicts; exact monotonic progress; compare-and-delete journal ACKs; socket/cache mutation fences and topic request ownership. | Paired-device acceptance, including response loss. |
| 7. New-member history | Prejoin messages do not inflate a new member's badges and do not become Seen. Equal-to-join-time messages remain unread unless explicitly observed. Membership removal cascades proof. | Product/device acceptance of documented history policy. |
| 8. Obscuring surfaces | Existing lifecycle/lock/modal gates plus missing call/ring/permission/share/invite gates on Android; central call/live-room/lock gates on Swift; measured mirror viewport replaces lazy onAppear; explicit photo/document picker visibility. | OS interruptions, sheet nesting, accessibility, PiP, and rendered geometry. |
| 9. Convergence | One exact unread aggregate backs inbox counts, expiration schedules and first-unread identity; channel badges use it too. Read events update exact cached Seen and invalidate authoritative inbox/topic state, including the reader's other devices with privacy enabled. | Room/inbox/launcher badges across two devices. |

### Durability follow-up

- Visible reads now capture only the membership/fence already known in the cached room snapshot. They do not await a newer network preparation after the observation. Missing membership fails closed until room metadata arrives.
- Android and Swift manual-marker ACKs now trigger a current inbox read instead of repainting the requested flag over a newer device action. Swift history reconciliation also includes the mounted river beyond the 300-row cache window, so older displayed Seen proofs are refreshed.
- Swift attachment menus use native UIControl context-menu lifecycle callbacks to hold read occlusion through dismissal; message context-menu previews hold an independent occlusion lease. All existing commands and autumn colors remain. A thread/primary handoff restarts dwell even when the message identity is unchanged. UIKit lifecycle reference: [7](https://developer.apple.com/videos/play/wwdc2020/10052/).
- Dwell now belongs to each visible message identity: an unrelated row entering or leaving the viewport cannot perpetually restart a stable row. Focus/occlusion still restart unfinished dwell; departing/re-entering rows must qualify again. Swift schedules the earliest remaining deadline and can skip a permanently refused row without starving the others.
- Android shields the short local capture/journal write from navigation cancellation. Swift captures and journals on the main actor without an intervening network await. Durable acceptance is not rendered as server Seen.
- Read replay no longer occupies Android's outgoing-message dispatch lock. Its independent retry gate respects Retry-After.
- Android queue observers follow account hydration/switches, and WorkManager awaits both journals. Empty-to-pending transitions append work without cancelling running delivery or losing the completion/enqueue race. Swift's live and cold BGAppRefresh paths both await an original-intent read-journal pass.
- A current fence can clear a known manual reminder while reading an older thread; message age is no longer mistaken for intent order. Stale fences still cannot clear newer manual choices.
- Native build evidence: iOS `f3f3489` archive and simulator/build-test jobs succeeded. Subsequent durability changes require their own native execution; this does not establish device acceptance.

### Scope and badge follow-up

- Thread readers now retain the full authoritative count/anchor/expiry snapshot. Unloaded replies expire out of scoped badges offline. New/edit/delete/read/reconnect events invalidate these snapshots; permanent scope refusals clear stale count displays instead of leaving a previous thread count.
- Thread dividers use exact entry anchors. Android divider layout is a separate lazy item: showing only the divider cannot qualify its adjacent message as Seen. Topic entry anchors are independent of the whole-room anchor.
- Topic GET authenticates membership and reads all topic counts in one database transaction. Exact anchors and expiry buckets survive Android Room/Swift GRDB cache reopen and project offline without inferring counts from the loaded message window.
- Primary and mirror docks now use the same active/non-self-room policy, including one contribution for a manual-only reminder. Archived counts remain in the archive surface. The Swift icon badge uses a serialized latest-value writer and a fenced full-inbox refresh during OS-granted background work; badge permission denial is not treated as a failed queue drain.
- Android launcher/notification badges and closed-app cross-device push delivery still require separate platform verification; in-app counts are not a promise that the OS will display an identical number. No paired-device or all-obscuring-surfaces completion claim is made.
- The read-queue observer's account-switch reset is typed Boolean, not the router's generic empty-list reset. A regression test covers switching while read receipts remain pending.

### Occlusion and preview follow-up

- Android primary, mirror and thread readers now require the hosting Compose window to be focused, in addition to RESUMED lifecycle. System panels/popups cannot rely on lifecycle alone. This follows the official AndroidX `WindowInfo.isWindowFocused` contract.
- Primary Android collaboration/quick-phrase/safety/detail sheets and Swift packet-detail sheets now participate in read suppression. Thread root headings are separate from the message's lazy item.
- PiP preview/stack surfaces no longer clear their own timestamp-based unread badges. They use canonical inbox counts on both platforms; a truncated preview is not exact Seen. Swift's primary reader is blocked while PiP covers it; a presented thread sheet above PiP retains its own attention lease.
- Root-shell sheets now suspend Swift reading independently of room navigation; Android settings-to-profile-edit and saved-library overlays also keep their underlying room blocked. Added a real Android notification-shade window-focus test alongside controlled Compose viewport/lifecycle tests and Swift attention-lease coverage. These do not substitute for notification-shade, accessibility or paired-device acceptance.
- The actual native compiler caught follow-up integration errors (a duplicate serializer annotation, an obsolete badge variable, and a thread snapshot/intent field-name collision); source fixes are published, with fresh execution still required.

### Cross-device background invalidation follow-up

- Read/manual-unread commits now issue a body-free native invalidation, including when the same user is online on another device. The hint carries neither badge count nor read authority; delayed hints only request current state.
- Explicit registration capability version 1 protects older Android clients from rendering an unfamiliar hint as a generic banner. Registration/removal require owner authentication; capability revocation and registration updates are transactional. Apply `20261026_native_read_push.sql` before releasing this backend.
- APNs hints are collapsible background pushes, and ordinary message alerts request background processing. The Swift delegate awaits the existing owner-fenced replay/full-inbox path with a deadline. Foreground badge publications and newer background fetches invalidate older pending badge snapshots.
- Android hints schedule durable owned work, refresh the complete inbox after replay, and cannot switch the repository away from a foreground-selected account. This does not invent an Android launcher-count API or clear notifications from an empty/partial cache.
- Swift mirror polling no longer supersedes every still-running inbox request on networks slower than its polling interval; explicit intervening refreshes retain a trailing fetch.
- Native execution exposed a topic-cache normalization regression (nil emoji lost the existing `chat` fallback when read metadata JSON was present). The decoder now preserves canonical structural columns alongside the exact read metadata.
- Provider delivery, OS background limits, disabled permissions, legacy already-delivered notifications and Android launcher behavior remain real platform acceptance work—not a claim of synchronized closed-app icons on every device.

### Delivered-alert reconciliation follow-up

- Added authenticated, read-only **notificationStateVersion 1** proofs for one exact message. The indexed query uses current membership, exact observations, join history, deletion and canonical-root expiry; it never creates Seen or changes a manual reminder.
- Android message alerts have exact owner/room/message tags, not room hash identity. Quick-reply actions retain that exact identity, so replying to an old alert cannot dismiss a newer message. The worker removes only alerts with a validated negative-unread proof; an empty inbox, timestamp or malformed response is never sufficient.
- Opted-in Android message pushes are genuinely data-only (no Android notification subobject), including cold starts. Every received alert schedules reconciliation, so a message delivered after an earlier read hint cannot remain permanently stale until the app opens. Cold receive checks persisted ownership without waiting for a screen to start the repository.
- Swift background refresh and foreground/read invalidations reconcile delivered message alerts by their exact notification request identities. Both transports refuse wrong-owner/room/message, legacy or malformed proofs.
- Failed Swift alert proofs remain incomplete and receive foreground retry/backoff, including server Retry-After. Background completion now waits for the server/proof refresh outcome while treating optional OS badge permission as best effort. Android system-enumeration failure likewise remains pending instead of being mistaken for an empty tray.
- An online desktop no longer suppresses message pushes to opted-in Android/iOS devices. Connected foreground native clients keep their existing socket-based quiet-hours/mute/attention path instead of producing duplicate push alerts.
- Android's active-notification query is unavailable on API 21/22; launcher counts/dots, notification permission and OS scheduling remain platform constraints. Legacy alerts without exact identity are not guessed away. No numeric launcher-count parity or physical-device completion is claimed.

### Wire and storage contracts

- Capture response is **readIntentVersion 2**, visible/all acceptance is **readReceiptVersion 3**; manual marker acceptance remains **unreadReceiptVersion 2**. Both native transports reject timestamp-only read acknowledgements.
- `readMembershipId` identifies the current participant row. `readByMembershipIds` on messages contains exact proof for currently eligible readers (plus a viewer's own proof). Rejoining with the same user ID cannot revive proof belonging to the retired membership.
- Read observation timestamps are informational, never cumulative cursors. The retained `lastReadAt` compatibility field is NOT Seen/count evidence in the new native paths.
- Legacy unfenced read entrypoints cannot create exact observations or clear a manual dot. They cannot poison the new Seen model.
- Offline `offline.<membership>` receipts can record visible identities only; they cannot win an explicit marker conflict or mark-all. Replays are bound to the original account, origin, membership, message and intent.
- A genuinely new dwell under a newer cached fence is retained separately from an older pending observation of the same message. Old retries still retain their original fence. Legacy journal keys remain drainable, and their ACKs cannot erase a new visit.
- SQLite journals are protected from disposable cache cleanup. Successful/permanently refused observations are removed using their original payload/intent, so a delayed ACK cannot remove a newer journal entry.
- Android Room **16 → 17 → 18** adds the first-unread identity, topic unread count and durable topic scope/expiry JSON; full member identity is retained in `membersJson`, exact message proof in reserved locally-owned metadata (never trusted from caller payload). Swift GRDB **v13 → v14 → v15** adds topic unread count and durable scope/expiry JSON; message/conversation wireJson already persists additive fields.

### Deployment order (not executed)

1. Back up the database. Apply `20261026_read_intent_state.sql` if not already applied.
2. Apply `20261026_exact_read_observations.sql` as one transaction on one SQLite connection. Its one-time migration marker prevents subsequent reruns from turning new gaps into read progress. Legacy timestamp exclusions preserve badge state but are **not** advertised as exact Seen.
3. Apply `20261026_native_read_push.sql` for explicit native notification capability registration. Deploy the generated Prisma client and matching backend, then the matching native builds. Older clients must not silently fall back to cumulative read semantics. Web read callers require their own coordinated migration; this is the native workstream.
4. Execute the native and paired-device acceptance matrix above. Migration/source checks do not replace that step.

### Executed checks before publication

- Full backend suite: **427 passing** before the additional scoped-route tests; scoped-route plus chat suite: **43 passing** afterwards. Final aggregate rerun is recorded in the PR follow-up.
- Strict TypeScript check of the affected backend graph passed.
- Changed native production/test files syntax-parsed. Native compilation, simulator/emulator results and paired-device acceptance are tracked separately; no previous green build is borrowed as evidence.

## Historical implementation notes — superseded where they describe remaining timestamp-only behavior


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

## Follow-through: stale native Seen after privacy/member refresh

Tracing timestamp consumers exposed another paired projection defect. Swift's server privacy sentinel is epoch zero, not only nil. `RoomViewModel` previously treated that sentinel as a positive timestamp and kept the maximum of it and a previously published Seen value. It now excludes epoch/nonpositive/malformed watermarks during initialization and fresh member adoption; a late socket receipt cannot recreate a reader missing from the current eligible-member projection.

Android's alternate room independently OR'd a cached socket watermark into Seen even when fresh membership hid/removed that reader. Rendering now uses only the current member projection, as the repository already invalidates/reloads it on read events. The mounted room also no longer counts every member as a reader when the message timestamp fails parsing to zero. Both Android room renderers and the info sheet share a pure current-reader filter. Kotlin filter regressions and actual Swift room-model privacy-sentinel regressions were added; seven changed/new native files syntax-parse, native execution remains separate.

This improves privacy/badge projection but does **not** solve equal positive timestamp ties. Following item 2 confirmed the required change crosses `unreadSnapshot`, member serialization, persisted native member metadata, live read events, bubble Seen and message-info sheets. A one-sided `>=` → `>` change would wrongly suppress legitimate Seen for the latest read message; it is not presented as a fix. Item 2 still needs the coordinated message-ID-aware progress contract. Items 3–9 remain at the boundaries stated above.

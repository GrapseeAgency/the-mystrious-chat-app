# Seen / unread / read-ordering workstream

## Current candidate — exact observations (2026-10-28)

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
| 7. New-member history | Messages already present at membership creation, including equal-time identities, are exempt without becoming Seen. Later equal-time arrivals remain unread. Membership removal cascades proof. | Product/device acceptance of documented history policy. |
| 8. Obscuring surfaces | Existing lifecycle/lock/modal gates plus missing call/ring/permission/share/invite gates on Android; central call/live-room/lock gates on Swift; measured mirror viewport replaces lazy onAppear; explicit photo/document picker visibility. | OS interruptions, sheet nesting, accessibility, PiP, and rendered geometry. |
| 9. Convergence | One exact unread aggregate backs inbox counts, expiration schedules and first-unread identity; channel badges use it too. Read events update exact cached Seen and invalidate authoritative inbox/topic state, including the reader's other devices with privacy enabled. Confirmed background snapshots now feed both Swift inbox models and the dock without another fetch. | Room/inbox/launcher badges across two devices. |

### Durability follow-up

- **Overlapping inbox reads (2026-10-28):** Android and the primary Swift inbox no longer discard a useful pending response merely because a newer GET started or failed. They track accepted request order separately from mutation invalidation. Older snapshots cannot overwrite a later-started accepted one; an earlier completion cannot suppress a newer pending response. Android records the accepted sequence only after its Room transaction commits, under a snapshot mutex. Real mutation/event invalidations still retire old GETs immediately (before debounce); missing-membership fetches do not pretend to be mutations. Swift Mirror now listens for owned inbox/live invalidations and uses its existing trailing-request coalescer instead of relying only on polling. A mutation-superseded Android refresh reports incomplete reconciliation so a background worker retains its retry. Added real-repository response-barrier/loaded-observer tests, Swift request-fence tests and source wiring checks. Full local backend/source regression suite: 464 passing; matching native execution and device acceptance remain pending.

- **Confirmed background inbox handoff (2026-10-28):** a successful Swift background badge fetch used to update GRDB and the icon while the loaded primary inbox ignored cache emissions and the Mirror inbox kept its own previous array. If the next list fetch failed, the UI could remain stale. The live engine now synchronously publishes its persisted, owner-fenced full snapshot to both models and the session dock. Each model retires older pending list responses before adopting it; no second fetch or optimistic count is needed. Publication happens before delivered-alert reconciliation, so a later alert-query failure does not hide already-confirmed inbox truth. Cold engines retain the existing persisted-cache startup path. Android already observes canonical Room changes; that wiring is regression-checked rather than rewritten. New Swift tests exercise real API decoding/GRDB persistence, both loaded models, manual/archive/self totals, empty snapshots, stale owner and HTTP/disk failure; native SDK execution is pending. Full local backend/source regression suite: 463 passing; Swift syntax parsing passes. No change to autumn styling or read authority.

- **Proof provenance consistency (2026-10-28):** exact Info and full message serialization now accept only `visible`/`all` observations as Seen. The real serializer also checks that each proof membership belongs to the message's room. Unknown source strings are not treated as read progress or notification exemptions; `legacy` remains an intentional non-Seen unread exemption. A deliberate mark-all can upgrade legacy/unknown entries with a real observation time. This makes malformed/future records fail closed instead of relying on a single `source != legacy` exclusion. Real-serializer history tests now include legacy, unknown and foreign-room evidence; SQLite/Prisma scope/Info/notification tests cover unknown evidence before and after an explicit action. Full local backend suite: 461 passing. No migration was run and no physical acceptance is implied.

- **Uncertain explicit-action settlement (2026-10-28):** Android explicit mark-all and Swift primary single/batch mark-all/manual-reminder paths now invalidate canonical projections on failure/cancellation as well as success, with the original account/session fence. Android also refreshes the retained message window; Swift uses the same fetch-only all-read invalidation as the mirror. No local proof/count is fabricated and no new intent is prepared as an automatic mutation retry. This closes success-only refresh paths when the server committed but the response was lost. The mirror already reconciled uncertain mark-all; it now shares the generation-checked helper. Added real-repository lost-response coverage, Swift retired-session coverage and all-entry-point source regressions. Local backend suite: 460 passing. The first fairness Android build caught a JUnit 4 import in the new JVM-core test; corrected to that module's existing JUnit Jupiter API. Native execution is still required for the corrected candidate.

- **Fair durable read replay (2026-10-28):** an unconfirmed first observation no longer pins the whole native journal. Both stores persist compare-guarded retry ordering without rewriting its owner, message, minimum timestamp or original intent. Request-local refusals permit other observations to settle in the same pass; at most 16 such deferrals are processed per pass. Authentication/rate/network/server failures stop the pass, and cancelled/retired owners do not commit. Swift foreground retries retain their delay and unknown-refusal fallback; cold/live background callers share the bounded policy. Nothing is inferred Seen or discarded from an unbound refusal. Room/GRDB tests cover independent delivery, original payload retention, durable reopen order and bounded refusal batches; executable SQLite tests validate both native SQL statements. Full local backend suite after this change: 458 passing. Matching native SDK execution and real-device acceptance remain separate required evidence.

- Journal retirement now requires a versioned, identity-bound refusal from the authenticated visible-read endpoint. Generic 400/403/404/410/422 responses—including credential rejection, proxy errors and old gateways—are not proof that an observation is obsolete. Both native transports carry the retirement envelope; Android replay and BOTH Swift foreground/background replay paths check owner, room, exact message and original intent before compare-and-delete. Foreground Swift also rechecks cancellation and owner after HTTP before success or refusal can commit. Only server-validated missing membership/message or retired membership refusals issue it. Conflicts and mark-all refusals do not. Refusal never becomes Seen. No account sign-in/recovery behavior changes; an older gateway without this envelope conservatively leaves the original journal pending.

- Send/action acknowledgements no longer replace an existing message's exact read proof with their incidental full-row snapshot. A fast socket read or a newer canonical negative survives a late send/edit/pin/reaction/poll/view-once ACK; action fields still commit. Both outgoing writers now publish the retained committed row, not the old HTTP input, after atomic outbox retirement. This is a distinct ACK merge, not a union: canonical GETs, Info proofs and dedicated read acknowledgements retain their original replacement/observation semantics. Native regressions cover positive and negative cached proof, successful delivery retirement, and the published delivery event.

- Background outgoing work and read-state work now run as independently awaited lanes on both platforms. Swift cold wakes previously drained uploads before read replay/badges; Android's worker deferred exact delivered-alert reconciliation until its outgoing drain completed. A slow upload can no longer consume the entire read-state wake budget, and read outages do not cancel ordinary message delivery. Cancellation still owns both lanes; any unfinished lane keeps the wake incomplete. Swift cold engines additionally recheck their owner/foreground-takeover lease before dispatch and after HTTP, retaining original pending operations instead of committing through a second store after a live engine takes over. Live read/badge callbacks bind the engine-creation session epoch.

- Overlapping full-message GETs now have a separate per-store/repository request-start order. A later-started accepted page protects its IDs from an older history/search/thread/pin/saved response; unrelated IDs in that old response still commit. Starting a request alone does not invalidate anything, and an earlier response committing first cannot suppress the later request. Android checks account/epoch inside the shared cache transaction, including history/search consumers. Swift thread/jump consumers resolve already-committed rows again after suspension instead of writing the old page a second time; search/pins likewise publish the canonical cache rows. Mirror's room GET now honors the existing socket/ACK revision fence as well. These are local response-ordering guards, not a server revision, read authority, or Seen evidence. Mutation ACK ordering remains independent.

- Read-only message-page commits now also fence older exact Info responses on both native platforms. The prior event-only fence covered sockets/ACKs but missed a page that committed while an Info GET was suspended. Android records accepted page/history/thread/pin/saved snapshots under its cache mutex; Swift records page/thread/saved commits in its account store and checks the version inside the proof-only transaction. Unchanged snapshots count too. Swift rechecks the same cache version again before live Info adoption/dismissal. This does not turn canonical GETs into mutation events, union peer evidence, or reorder message content. Barrier-based Android regressions exercise both response orders; Swift storage regressions cover positive, negative, unchanged, unrelated and separate-account cases.

- Returning to an already-open thread now refreshes its content and scoped count even if the relay's connected flag never changed: Swift includes foreground state in its load task key, and both Android thread surfaces refresh at the resumed lifecycle boundary. The Android primary thread coalesces reload requests so a foreground return during an older request is not discarded by a `loading` guard. Thread read authority and the visit's first-unread divider are retained; data catch-up is not a new Seen intent.

- Thread unread-counter refreshes on both native platforms now finish the current canonical request and conflate intervening read/message hints into one trailing pass. Previously Android cancelled its job on every hint and Swift restarted a revision-keyed task, so sustained traffic could prevent any slow HTTP response from reaching the header. Both Android thread surfaces share a scope-owned mailbox; Swift reuses its existing generation-owned coalescer and cancels on disappearance/background. Counts still come only from authenticated scoped snapshots; unknown failures preserve the last confirmed state, access refusals retire it, and no read mutation is introduced. New Android mailbox regressions cover bursts, disposal and retry; Swift's existing coalescer regressions cover trailing-pass and retired-generation behavior. Matching native execution is still pending.

- Android's primary room now reconciles its cached message range on foreground return and relay reconnection, instead of only restarting dwell. Both Android room surfaces share the resumed-lifecycle collector, including canonical membership and topic badge refresh. Disconnection/background cancellation stops obsolete work; repeated identical connection signals do not fetch again. This path never marks read or renews the mounted read authority, and does not reset the scroll/pagination cursor. Swift's existing connected/foreground room observer was inspected separately; this is not a claim of paired-device execution.
- Android paged message reconciliation now captures and rechecks its original account/session inside the Room transaction, so an old response cannot write after retirement. A barrier-based repository regression also checks that unrelated rows in the retired page are rejected, not just the row whose read proof changed.

- Android exact-message read reconciliation now advances its per-message fence even when the proof already equals storage or the row is absent. Otherwise a previously started page could restore stale proof after a canonical empty/unavailable response. Real Room/repository regressions hold the older HTTP page behind a response barrier and check both cases, including preservation of unrelated page rows. These new instrumented cases still require execution on the matching native build.
- Swift public-socket owner-proof retention decodes membership from the cached conversation's wire snapshot; the domain conversation does not expose a `members` property. This corrects the compiler failure in revision `4179cf8`. Cached legacy summaries without membership remain fail-closed, and a rejoined membership cannot inherit the previous membership's proof.

- The alternate Swift room uses the same coalescing refresh owner, independently of message-window tickets. Message traffic cannot continually retire its roster request. It subscribes to scoped read invalidations and conversation updates, and drops reader metadata on a current definitive access refusal.
- Swift receipt-membership refresh now completes one canonical request and coalesces intervening events into a trailing pass, instead of cancelling/restarting on every receipt. Ordinary read traffic no longer prevents canonical privacy/member adoption. Conversation updates invalidate older metadata tickets; a current definitive access refusal removes displayed reader metadata. Leaving/backgrounding cancels the refresh generation, and foreground return catches up.

- On both platforms, a qualified attempt that found no usable membership now permanently limits that mounted visit to non-clearing authority. Later metadata can supply the membership ID for exact progress, but cannot lend it a newer manual-unread fence. A genuine new room/thread visit resets that limitation; cancellation/dwell restarts do not.
- The alternate Swift inbox's explicit mark-all action invalidates canonical read scopes/messages on settlement, including an uncertain response, with the original account epoch check. It no longer depends on socket echo and never invents a local all-read set.
- Swift's alternate room also refreshes its canonical message window on explicit-all events from the current account, rather than ignoring them as ordinary self-read echoes.

- Visible reads now capture only the membership/fence already known in the cached room snapshot. They do not await a newer network preparation after the observation. Missing membership fails closed until room metadata arrives.
- Swift refreshes canonical receipt membership after read events, including a peer’s first-ever read: unknown/hidden timestamps are not promoted using a relay timestamp. Android read/push invalidations also retire in-flight inbox snapshots before queuing refresh, preventing an older GET from briefly reinstalling pre-invalidation badges.
- Validated exact HTTP read ACKs now also persist their membership proof into the current cached message, preserving edits/deletions and other readers. Enqueue, unknown ACKs and permanent refusals do not create proof. Explicit-all ACKs fetch the canonical retained range instead of sweeping local rows.
- Owner-checked push hints also invalidate open native thread/topic projections, rather than refreshing only the inbox/OS badge. Android carries the room through durable worker input; Swift rechecks the recipient at task execution and fences the live engine callback by session generation. No pushed count is trusted.
- HTTP replay settlement and explicit mark-all now invalidate scoped unread projections directly, even when socket echoes are absent. Android replays per-room revision signals to thread readers and refreshes topic caches; Swift invalidates thread tasks and topic rails. Batches coalesce by room; transport failures/retired sessions cannot fabricate progress, and invalidation never supplies a count or Seen timestamp.
- Android room/thread readers capture their first intent synchronously from displayed membership before the local journal write suspends; they no longer fetch a potentially newer manual-unread token through a delayed DAO read. Missing/foreign membership fails closed and queues coalesced metadata repair without adopting its future token; known offline membership retains the non-clearing offline fence.
- Swift now matches Android’s independent thread read-authority lifetime: an explicit thread opening gets its own gate, temporary covers retain that gate, and returning to the primary room restores its older authority rather than borrowing the thread’s. A new room/account epoch retires retained surface authority.
- Open message-info sheets now use live room rows instead of their opening snapshot; Swift also observes current receipt membership metadata. Incoming-message info excludes its author rather than its viewer. Empty recipient lists cannot claim everyone has Seen, and locally queued rows are not labeled Sent. Expired/missing/session-retired info no longer displays the old captured row.
- iOS Writing Tools keeps a separate occlusion lease after its launching edit menu closes, and its completed text returns through the existing draft binding. Native menu regressions exercise floating Android ActionModes on activity and thread-dialog windows and invoke Swift callbacks through the UIKit delegate protocol.
- Swift primary/thread readers register passive UIKit anchors by their existing visit/surface IDs. Qualification checks the actual reader's controller ancestors for presentations/transitions, including panels absent from SwiftUI sheet bindings; a thread is not blocked by its own enclosing sheet. Scene-specific native deactivation/activation also interrupts dwell synchronously. The existing one-second visibility tick notices presentation dismissal and starts a full fresh dwell; it does not grant reading while a panel is still present. No system views/delegates are modified. Controller-structure regression tests are not physical-device Lookup/IME acceptance.
- Swift also observes UIKit application deactivation/activation synchronously on the main queue. Its native inactive gate cannot be overridden by a stale SwiftUI active flag, and each lifecycle event restarts dwell even when scenePhase updates coalesce.
- Android’s window callback also retains focus interruption revisions. A focus loss/restoration within one Compose frame now restarts dwell even if the latest focus boolean is already true; current focus remains governed by WindowInfo.
- Native text-edit menus now participate in attention: Android observes ActionMode lifetime on each activity/dialog window, including fast open/close revisions; Swift composers expose public UITextView edit-menu presentation/dismissal callbacks. Draft bindings, five-line input sizing and autumn chrome are retained. These menus need not cause scene inactivity or window-focus loss. Reference: [8](https://developer.apple.com/videos/play/wwdc2022/10071/).
- Swift reading ownership now uses the navigation-entry UUID, not room ID alone. Late disappearance and viewport callbacks from an older same-room destination cannot retire its replacement. Hiding/restoring the same entry restarts dwell without renewing intent; a genuine new entry or account reset retires it. Framework-driven full-screen presentation/restoration still needs device execution.
- Android message push receipt schedules owned inbox reconciliation before cold-owner/notification-permission exits, with a second post-publication pass for alerts. Not displaying an alert no longer prevents the background count refresh.
- Row menus now distinguish **Clear unread reminder** (manual flag only) from **Mark as read** (explicit frozen-ID mark-all). Previously the former was labeled Mark as read even though actual unread messages remained. Both native shells expose the deliberate read action without broadening the reminder API.
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

### Historical nine-item status — superseded

| # | Item | Current status / next closure evidence |
|---|---|---|
| 1 | Manual-unread versus delayed reads | Server + both native implementations repaired for prepared-request/retry ordering; exact-candidate native/device verification pending. |
| 2 | Equal-timestamp boundaries | Open. Needs identity-bearing progress consistently in server counts, native storage, receipts and Seen rendering. |
| 3 | Unseen gaps | Open. Cumulative timestamps still imply progress across undisplayed rows; must not call this individually read-message proof. |
| 4 | Thread/topic granularity | Open. Requires a consistent per-scope/range model rather than a client-only counter patch. |
| 5 | Durable offline read intent | Open. Current boundary token lifetime is not persistent. Replay must retain its original intent and authority, not acquire fresh permission after reconnect. |
| 6 | Cross-device read ordering | Explicit manual/all mutations now have a server conflict fence. Broader read progress, late UI projection and multiple-device acceptance remain open. |
| 7 | New-member history | Boundary identity exclusions now cover group adds, channel/invite joins and app-community joins; retry/rejoin and actual-history-read cases are exercised. Product/device acceptance remains open. |
| 8 | All obscuring surfaces | Existing lifecycle/lock/dwell/thread ownership gates retained; full OS/modal/call/accessibility acceptance not established. |
| 9 | Badge convergence | Owner read events still trigger authoritative inbox refresh, not blind zeroing. Paired-device/app badge convergence is not certified. |

## Evidence

Actual route + SQLite tests cover tokenless/foreign owner rejection, missing intents, equal/newer delayed receipts, lost-ACK visible retry, repeated desired values/ABA, stale explicit-all/on/off, fresh reopen, leave/rejoin, legacy unfenced behavior and additive/rerunnable/cascading migration. Kotlin transport/production retry-holder and Swift transport/production read-gate regressions cover receipt identity and retained token ownership. Syntax/TypeScript checks do not constitute native/device execution.

Validation at this publication: **419 full backend tests / 407 top-level PASS**, **64 targeted actual-route/SQLite tests PASS**, strict affected TypeScript graph PASS and **17 changed/new native syntax parses PASS**. Native compiler/unit/device execution for this change is pending; earlier green checks are not applied to this patch.

## Follow-through: stale native Seen after privacy/member refresh

Tracing timestamp consumers exposed another paired projection defect. Swift's server privacy sentinel is epoch zero, not only nil. `RoomViewModel` previously treated that sentinel as a positive timestamp and kept the maximum of it and a previously published Seen value. It now excludes epoch/nonpositive/malformed watermarks during initialization and fresh member adoption; a late socket receipt cannot recreate a reader missing from the current eligible-member projection.

Android's alternate room independently OR'd a cached socket watermark into Seen even when fresh membership hid/removed that reader. Rendering now uses only the current member projection, as the repository already invalidates/reloads it on read events. The mounted room also no longer counts every member as a reader when the message timestamp fails parsing to zero. Both Android room renderers and the info sheet share a pure current-reader filter. Kotlin filter regressions and actual Swift room-model privacy-sentinel regressions were added; seven changed/new native files syntax-parse, native execution remains separate.

This improves privacy/badge projection but does **not** solve equal positive timestamp ties. Following item 2 confirmed the required change crosses `unreadSnapshot`, member serialization, persisted native member metadata, live read events, bubble Seen and message-info sheets. A one-sided `>=` → `>` change would wrongly suppress legitimate Seen for the latest read message; it is not presented as a fix. Item 2 still needs the coordinated message-ID-aware progress contract. Items 3–9 remain at the boundaries stated above.


### Exact evidence while Message Info is open

`GET /api/messages/:id/read-state?userId=...&conversationId=...` is a read-only,
versioned, authenticated single-message projection. Membership, root/message
lifetime, exact observations and receipt privacy are read in one serializable
transaction. A zero unread count, pre-join exemption and a migrated legacy stamp
are **not** Seen evidence. No body or cross-room reader metadata is returned.
A bound `messageAvailable: false` retires the Info surface; an unsupported route,
transport error or malformed response does not stand in for this verdict.

Android's standard and Mirror Info panels and iOS's standard Message Info sheet
refresh this projection on opening and scoped invalidations. A five-second
fallback while Info remains open repairs lost relay/push hints and failed GETs.
Android collects only while the surface lifecycle is resumed; iOS suspends network
work in the background and cancels on disappearance. Requests coalesce without
cancelling an in-flight GET. Metadata refresh belongs to the Info surface and
never grants its covered room new read authority. The iOS Mirror currently has
room info, not a separate message-info entry point; its existing room reader
metadata/window reconciliation remains unchanged by this addition.

Both caches replace only the exact row's reader IDs, rather than unioning a stale
privacy snapshot or replacing the message body. Session ownership and per-message
revision fences reject older results crossing a newer ACK/event. Current rosters
are refreshed separately. Ordinary hints do not trigger whole-history downloads.
This is not an assertion that every retained cached message is immediately
reconciled without an open surface, or that paired-device acceptance is complete.


### Membership and archive badge wake-ups

After committed add/join/invite/leave/removal/archive changes, affected owners now
receive the same capability-gated native read-state hint as read mutations. The
removed/leaving owner is explicitly included even though no longer in the room's
current membership list. Adds batch only genuinely new accounts; refused/no-op
membership changes do not notify unrelated users. Role changes retain their
existing socket notifications. Archive/unarchive wakes only the owner.

These hints contain no read proof, count or message body. Android's existing
owned worker refreshes its canonical conversation snapshot; iOS's existing
active/cold background paths refresh canonical badge and delivered-alert state.
Foreground/reconnect reconciliation remains necessary when the OS suppresses a
background push. This does not turn a best-effort wake-up into guaranteed delivery
or claim universal Android launcher badge parity.


### Historical membership boundary, including timestamp ties

The three group/channel/invite join paths now capture `joinedAt` and `lastReadAt`
from the same transaction clock and preserve existing boundary/future-dated
message identities as non-Seen (`legacy`) exclusions. App-community joining uses
the same helper. Strictly older history remains compactly excluded by `joinedAt`;
this does not materialize or duplicate the whole old history for every member.
A later message with the identical timestamp is absent from that identity snapshot
and therefore remains unread. Retrying an existing membership never repeats the
exemption pass. Leaving/rejoining creates a new membership/history snapshot, and
an old read intent remains retired. Actually viewing an exempt historical message
upgrades only its exact observation to Seen.

This uses the existing exact-observation migration and needs no new native schema
or SQL patch. Missing history storage rolls back the membership transaction rather
than admitting a member with a partial exemption. Android and iOS consume the same
canonical unread/receipt projections; no native optimistic sweep was introduced.
The deterministic server cases freeze the join clock and cover existing equal-time
and forward-skewed history, future equal-time arrivals, retries and retired intents.
They do not certify arbitrary backwards server-clock jumps after joining.


The iOS Info refresh also patches the retained room model's exact reader IDs.
That model is not a GRDB message observation, so changing storage plus sending an
expiry clock tick alone is insufficient. The patch preserves current text and
other fields, supports live carriers not yet in storage, rejects queued/absent or
retired-session rows, and does not classify a receipt update as a new arrival.
Every accepted snapshot fences earlier message-page responses, including when
there was no stored row to patch.


Android's standard incoming-message Info now excludes the **sender**, not the
viewing recipient. Its own cross-device confirmation can therefore appear, just
as on iOS, while sender proof cannot be counted as a recipient receipt. Android
Mirror's Info panel now displays the same exact Seen/unconfirmed split instead of
only sent/reaction metadata. Both use one pure domain projection, reject missing
viewer identity, and honor hidden/retired membership proof. Mirror retains its
existing autumn colors and 380dp sheet bound with a lazy recipient list.


### Public socket envelopes versus private owner evidence

A generic edit/reaction/message broadcast intentionally omits privacy-disabled
readers. That omission is not a canonical negative about the viewer's own private
read. Both native caches now retain **only** an already-confirmed observation for
the current viewer's current membership across such a broadcast. They do not
union cached peer arrays, invent missing proof, keep a retired membership, or
apply this rule to owner-scoped GETs. Swift normalizes before both cache writes
and signal fanout so its independent room/thread/Mirror arrays agree; Android
publishes its retained Room row. Definitive canonical Info snapshots can still
remove the owner's proof. The Swift generic-envelope path also no longer writes
the same socket row twice.

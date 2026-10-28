# Section 3 — source-to-regression reconciliation

> **Current status — requested recheck:** Actual gaps in original items 6,
> 7 and 8 were repaired and their scoped regressions passed. This is not an
> exhaustive full-app/device correctness claim. The original list has been supplied;
> [the authoritative checklist](NATIVE-COMPOSER-AUTHORITATIVE-CHECKLIST.md) is the
> acceptance matrix. No section 4, deployment or PR merge is included.

## Current recheck evidence — 2026-10-29

### Behavior checked

- Android manager rows freeze display-time draft revision/account, bulk actions
  freeze the rendered collection, and recovery confirmation retains its page
  owner. Negative controls demonstrate the old late-token/latest-owner guards
  admitting stale actions. Swift manager callbacks carry a generation fence;
  the store regression checks actual same-text ABA preservation. These are
  helper/store regressions plus source wiring checks, not physical UI callbacks.
- Same-instruction schedule creation had failed reproducibly with three busy
  responses and no creation. The failed no-op-write experiment was removed;
  bounded transaction budgets now survive contention. Original exact-one-creation
  assertions remain intact, including independent Prisma clients. Both contention
  tests additionally passed three repeated runs.
- Draft/edit and scheduled-command replay now persist/restore global Retry-After
  admission. HTTP fixtures check both replay lanes as first caller, draft and edit
  separately, 429 and 503, repeated scans, reopened stores, unchanged instructions,
  and a direct-transport negative control. Clock/room/owner isolation and metadata
  commit failure/recovery are also tested. Android revalidates ownership after
  the newly suspended admission read.

### Exact results

- **Backend:** full local rerun 536 passed, zero failed/skipped (524 top-level).
  Backend source/tests are unchanged from that rerun through `b80f027`.
- **Python:** 21 local SQL/source/reporter contracts passed.
- **Android:** run [40750618074](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40750618074),
  source `b80f02736bae9fe389f2b6d2e5a2b5b6df9fbb8a`. Build job `123036003728`
  completed successfully, including all listed JVM modules and signed-release
  assembly. API30/API34 instrumented test steps passed in jobs `123036003632`
  and `123036003123`; their later release-smoke steps were still running at
  observation. This is not a successful-whole-workflow claim.
- **iOS scoped:** run [40750341613](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40750341613),
  check `123035061509`, source `5faecb93f1a48edc3b2b75b296c885d0cdf00ed6`,
  completed successfully. Retrieved annotation explicitly reports
  **112 expected / 112 passed / 0 unverified**, `verified=true`.
  Swift source, test source, selector and reporter are unchanged through `b80f027`.
  Result digest: `ab6221357110551566b97e135fdf56ac7b250042bdbf27d93f68f7db638a6049`.

| Selected Swift suite | Expected | Passed |
| --- | ---: | ---: |
| ComposerAttentionTests | 4 | 4 |
| ComposerJournalTests | 9 | 9 |
| ComposerRetryAdmissionTests | 3 | 3 |
| DraftHandoffTests | 10 | 10 |
| DraftJournalTests | 8 | 8 |
| DraftReceiptTests | 1 | 1 |
| PulseOutboxTests | 49 | 49 |
| ScheduledAcceptanceTests | 6 | 6 |
| ScheduledCommandJournalTests | 3 | 3 |
| ScheduledCreationRetryTests | 3 | 3 |
| TextDeliveryTests | 11 | 11 |
| ThreadDraftHandoffTests | 5 | 5 |

The separate full iOS workflow for `b80f027` (`40750618672`) was queued at this
observation. The older ReadPresentation failure is not waived or declared fixed
by these composer passes. Deployment, migrations and comprehensive physical/UI
acceptance remain deferred as agreed. Checkpoints and admission metadata cannot
promise persistence through process death before a successful storage write.

## Historical investigation ledger

The entries below preserve earlier findings and failed/partial verification.
Their statements about an unavailable original list, pending tests or smaller
counts describe those earlier checkpoints, **not the current status above**.

## Where the checklist comes from

The original numbered eleven-item wording has not been found in the checkout,
its available history, or PR 5's description. These are actual historical
requirements/limitations that can be recovered, not guesses based on test names:

- **DRAFT:** `NATIVE-DRAFT-VOICE-LIFECYCLE.md`, “iOS composer” and its boundaries:
  last-keystroke ownership, explicit clears, atomic send consumption; remote
  mirroring lacked versioned durable sync; scheduling/recovery/ancillary writers
  needed shared semantics. Also `NATIVE-FEATURE-TREE-REVIEW.md`, “Lossless local
  drafts and confirmed remote mirrors” and “Draft restoration after local storage
  errors” (2026-10-26).
- **SEND/EDIT:** `NATIVE-DEEP-BEHAVIOR-FIXES.md`, concrete-failure table: old ACK
  erases new draft, failed sends overwrite drafts, failed rows starve the queue,
  discard leaves echo, metadata changes on retry, entering edit loses unsent text.
  The “Still open” paragraph expressly retained complete cross-device draft sync.
- **ORDER:** `NATIVE-FEATURE-TREE-REVIEW.md`, “Send ordering, ambiguous
  acknowledgements, scheduled delivery and false Seen”: independent immediate
  sends, retained operation IDs, admission/ACK failures, FIFO and retry holds.
- **SCHEDULE:** the same document, “Scheduled sends: cancellation/delivery race,
  pagination and native acknowledgement”, and the 2026-10-24 sections “Scheduled-send
  draft ownership and retryable cancellation”, “Durable scheduled-create retry
  identity”, “Scheduled-message offline push”, “Preserve the selected schedule
  through a retry”. These distinguish earlier repairs from remaining restart,
  UI-acceptance crash boundaries and durable notification recovery work.
- **IDENTITY:** `NATIVE-DELIVERY-CONTRACT.md`, message and scheduled creation
  receipt contracts: immutable owner-bound identity, retained terminal receipts,
  coordinated schema rollout, no duplicate message on replay.

These sources support the working areas below. They **do not prove** that their
wording or grouping equals the original eleven-item conversation inventory.

## Working-area trace

Paths below are relative to the Android/iOS source roots unless `src/` or
`tests/server/` is named. Tests named here are authored executable evidence;
execution status must come from the exact-candidate result section, not this table.

| # | Historical basis and required behavior | Current implementation | Concrete regressions |
| --- | --- | --- | --- |
| 1 | DRAFT: clears and cross-device writes must not overwrite newer revisions | `src/lib/composer-operation.ts`; protocol `ComposerIntents.kt`; Swift `PulseComposerSync.swift` | Backend `composer-operation.test.mjs`: draft CAS, lost ACK, terminal conflict, membership replacement, legacy edit ABA; Kotlin `ComposerReceiptTest`; Swift `ComposerJournalTests.testUnknownDraftBaselineCannotOverwriteARemoteClear` |
| 2 | DRAFT/IDENTITY: durable desired text separate from immutable transmitted instructions | Kotlin `ComposerJournal.kt`; Swift `PulseStore.stageComposer/freezeComposer/acceptComposer`; replay loops | Swift `testFrozenFlightSurvivesReopenAndNewTypingKeepsItsOriginalObservedRevision`, `testBlockedDraftRemainsRecoverableButDoesNotSpinTransportQueue`; Android `ComposerJournalTest.latePreflightRefusalCannotHoldNewerDraftOrEdit`; Swift matching `testLatePreflightRefusalCannotHoldNewerDraftOrEdit` |
| 3 | DRAFT/SEND: last-keystroke recovery, account/room/thread ownership, atomic send/clear | Kotlin `DraftWriteCoordinator`, repository enqueue; Swift `PulseDraftJournal`, `PulseDraftWriteFence`, store transactions | Kotlin `DraftRecoveryIntegrityTest`, `DraftSnapshotIntegrityTest`, `AccountDatabaseIsolationTest`; Swift `DraftJournalTests`, `DraftHandoffTests`, `ThreadDraftHandoffTests`, `DraftReceiptTests` |
| 4 | SEND/EDIT: unsent edit text survives reopening without overwriting the normal draft | Kotlin local edit snapshots/front buffer and both chat surfaces; Swift `saveComposerEdit`/`enqueueComposerEdit` and chat editor | Swift `testUnsubmittedEditIsDurableButNeverDispatchable`, `testFailedEditWriteSurvivesReopenedStoreWithoutRebasingOntoRemoteText`; Kotlin `AccountDatabaseIsolationTest`; `RoomComposerDraftTest`; Swift `ComposerAttentionTests` |
| 5 | EDIT/IDENTITY: old edit response cannot consume a newer editor; competing edits use original baseline | Backend composer edit CAS and `message-mutation.ts`; native flight + local-edit revision matching; metadata-only accepted baseline | Backend edit CAS/ABA/legacy-writer cases; Swift frozen-flight and exact-text fingerprint tests; Kotlin `ComposerReceiptTest`; new preflight-refusal cases cover draft and edit separately |
| 6 | SEND/ORDER: failed room must not starve another room; FIFO retained inside each room | Kotlin `FlushOutboxUseCase` and production DAO room-head queries; Swift `PulseOutboxEngine`/store room-head queries | Kotlin `FlushOutboxUseCaseTest`; Swift `testHealthyBacklogSharesEachDrainRoundWithOtherRooms`, `testFailedHeadRotatesOnNextPassWithoutOvertakingItsOwnRoom`, `testRoomCooldownBypassesLongPrefixButPreservesThatRoomsFIFOAndIdentity`; actual SQL `native-outbox-fairness.test.mjs` |
| 7 | SEND/ORDER: native producers persist complete envelopes before clearing/claiming success | Main/Mirror/thread/PiP, rich/forward/quick-reply/poll adapters share durable send paths; backend poll + carrier message + receipt are transactional | Kotlin `RichOutboxEnvelopeTest`, `OutboxOwnershipTest`, `AccountDatabaseIsolationTest`; Swift `TextDeliveryTests`, `PulseOutboxTests` quick-reply/forward/metadata/poll cases; backend `poll-outbox.test.mjs`. Picker/import/recording lifecycle remains section 4, not certified here |
| 8 | SCHEDULE/IDENTITY: lost create ACK/reopen must reuse exact owner/body/time/identity | Kotlin `ScheduledJournal`, `ScheduledCreationStore`; Swift `PulseScheduledJournal`/creation preference store; backend `scheduled-operation.ts` | Kotlin `ScheduledCreationStoreTest`, `ScheduledAcceptanceTest`, `ScheduleTimesTest`; Swift `ScheduledCreationRetryTests`, `ScheduledAcceptanceTests`; backend `scheduled-access.test.mjs` |
| 9 | SCHEDULE: pending/held schedule and cancellation survive uncertainty; stale ACK cannot free replacement or claim cancelled send is scheduled | Both native schedule journals, recovery managers and conditional room heads; reference index marks cached create receipt terminal on confirmed cancel | Kotlin `ScheduledJournalTest`; Swift `ScheduledCommandJournalTests` (cancel before/after composer ACK, replacement survives late ACK), `ScheduledAcceptanceTests` lost cancellation response; Swift `DraftHandoffTests.testScheduleAcknowledgementWaitsForAFailedLocalWriteToBecomeDurable` |
| 10 | SCHEDULE: reauthorize execution; atomically queue message + notices; resume failed notification channels | `src/app/api/maintenance/dispatch/route.ts`, `scheduled-delivery.ts`, `scheduled-notice.ts`; bounded leases, separate socket/push attempts | Backend `scheduled-access.test.mjs`, `push-delivery.test.mjs`: cancellation/dispatch race, policy changes, fairness, notice retry/lease/cleanup and no second message |
| 11 | IDENTITY/SCHEDULE/SEND: retained terminal identity without duplicate bodies; bounded, owner-scoped actionable recovery | Native metadata/hash receipts; keyset recovery + compare-and-swap actions; backend sent/discarded body scrubbing and bounded completed-notice cleanup | Actual Kotlin/Swift recovery SQL `native-composer-recovery.test.mjs` (>100 rows per namespace, wildcard owners, private head/reference exclusion); native schedule metadata/replacement cases; backend retention tests |

## Newly discovered gap during reconciliation

Android `ComposerJournal.flush` used equality of nullable flight IDs to decide
whether a failed lookup still owned a pending row. Before a flight exists,
`null == null` also matched a **newer desired draft or edit** staged while that
lookup was waiting. An obsolete refusal could therefore move newer work out of
automatic replay and into manual review.

The failure path now compares desired IDs before a flight exists, and flight IDs
once transmission is frozen. The Android Room + actual Ktor adapter regression
stages new text during the older GET, returns 403 for the old request, verifies
new text stays pending, then verifies the next GET/PATCH confirms only that new
text. It runs for both draft and edit. The Swift storage regression confirms the
same ownership boundary; its production `blockComposer` already used that rule.
The new cases have now executed successfully: Android API30/API34 instrumentation
and the 102-method scoped iOS run, detailed below.

## Explicit verification gate

`ios-composer-regression.yml` runs all test methods in eleven selected XCTest
suites, including both classes in `ComposerJournalTests.swift`. It leaves the
ordinary full iOS workflow unchanged. The result reporter enumerates expected
methods from checked-in test sources and requires an explicit terminal **passed**
record for every one. Missing, skipped, and failed-then-passed cases fail the
scoped check. Build success alone cannot pass it. Four local reporter tests cover
both XCTest output forms, multi-class files, missing cases, skips and retries.

Exact-candidate native run IDs and per-suite counts are recorded below. Earlier
`adb3490` results are not being used as verification of the new correction.

## Boundaries that remain explicit

- Historical limitation resolved: the original eleven-item wording is now available in the authoritative checklist; this older grouping is not proof of closure.
- The existing full-suite `ReadPresentationTests` failure is unresolved. A green
  scoped composer result cannot turn it into a green full iOS result.
- No guarantee of exact-time scheduling under arbitrary load, exactly-once
  provider notification, or immutable scheduled-list snapshots across pages.
- Failed SQLite commits cannot guarantee survival of abrupt process death;
  front buffers preserve in-process recovery and errors remain visible.
- No new schedule-edit endpoint is claimed. Native scheduling is text-only and
  refuses unsupported metadata contexts; an offline request retains its original
  deadline rather than silently choosing a new send time.
- Schema rollout, legacy-web compatibility, production deployment and physical
  Android/iOS acceptance remain separate work. No merge/release is performed.

## Exact results and scope decision (2026-10-28)

- The user required and subsequently supplied the original list. Closure now depends on implementing/verifying those requirements, not obtaining that list again.
- Scoped iOS run `40699358631`, job `122854640124`, source `12e7290`:
  **101 expected, 101 explicitly passed, verified=true**. This includes the
  cancellation before/after-acknowledgement tests and all eleven selected suites.
  Its annotation was truncated at 4096 bytes, but its complete counts/commit/
  verified header was retrieved. The full per-method report is in the run's
  evidence artifact and job summary. This is not full-suite acceptance.
- Scoped iOS run `40699700531`, source `293bc74`: **0/102 verified** because the
  newly added test called Swift's `isEmpty` property as a function at line 96.
  The compiler error is corrected; this failed run is not counted as execution
  of the new regression. The next run must explicitly verify all 102 methods.
- Android `293bc74`, run `40699700570`: build succeeded and both API30/API34
  instrumentation steps succeeded; release smoke jobs were still running at
  this observation. Backend `40699700624` succeeded on the same source.
- Broad local Python test discovery exposed two stale fixtures, not runtime
  product defects: the outbox query gained `excludedRooms`, and Swift saved
  selection moved into `savedItems` with expiry/root/page parameters. Repaired
  the fixtures, added expiry/root-denial SQL checks and included these tests in
  the backend contract workflow so they no longer silently drift unused.
  **13 Python checks passed**, plus **2 actual-native-query Node checks passed**.
- The reporter now emits bounded per-suite counts and a full-case-report digest
  in Checks annotations (rather than truncated JSON). Full per-method results
  remain in the artifact/summary. A regression checks the annotation size for
  both all-passed and all-missing cases. No production test is skipped to obtain
  a green result; the ordinary full iOS workflow is unchanged.

## Historical scoped result — 7542dcb follow-up

### iOS: explicit outcomes, not inferred from absent failures

- Source: `7542dcb8aa5cae8e525c76354b2398e06358578c`.
- Run: [40703333608](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40703333608), job/check `122868719955`: **success**.
- **102 expected / 102 passed / 0 unverified**, `verified=true`. The full bounded
  JSON annotation was retrieved and decoded; every suite's expected count equals
  its passed count. The corrected new ownership case is included in the eight
  ComposerJournalTests methods.
- Per-method results remain in the run's `pulse-ios-composer-evidence` artifact
  and job summary. The case-map digest reported by the runner is
  `07d2a47666ace70bcef9eea70cff1d8ac6a7d3725ed12d30181d1d0d9b591dac`.

| XCTest suite | Explicitly passed / expected |
| --- | --- |
| `ComposerAttentionTests` | 4 / 4 |
| `ComposerJournalTests` | 8 / 8 |
| `DraftHandoffTests` | 5 / 5 |
| `DraftJournalTests` | 8 / 8 |
| `DraftReceiptTests` | 1 / 1 |
| `PulseOutboxTests` | 48 / 48 |
| `ScheduledAcceptanceTests` | 6 / 6 |
| `ScheduledCommandJournalTests` | 3 / 3 |
| `ScheduledCreationRetryTests` | 3 / 3 |
| `TextDeliveryTests` | 11 / 11 |
| `ThreadDraftHandoffTests` | 5 / 5 |

### Android and backend

- Android source `293bc7452044bee9d7e6cb08004f223527788d14`, run
  `40699700570`: build succeeded; API30 and API34 **instrumentation steps both
  succeeded**, including the new Room/Ktor stale-lookup regression. API30's
  entire job succeeded. API34's subsequent release-smoke step remained running
  at the last observation; that whole job is not labeled passed.
- Android production and test sources are byte-for-byte unchanged between
  `293bc74` and `7542dcb` (`git diff` for `apps/android` is empty).
- Backend run `40703333710` succeeded on `7542dcb`, including the now-enabled
  Python SQLite/reporter checks. Local reruns: **13 Python checks + 2 Node
  native-query checks passed**. Backend production code is unchanged in this
  follow-up; the previously recorded complete suite remains 525 tests.

### What this resolves—and does not

This resolves the lack of explicit scoped iOS outcomes and verifies the new
Android stale-failure ownership repair. It does **not** establish equivalence to
the then-unavailable original eleven-item list. That list was subsequently supplied. Closure at this historical checkpoint remained open pending new implementation/verification; the final original-list reconciliation is below.
The unrelated full-suite read-presentation failure, deployment and comprehensive
physical-device acceptance are not waived by these scoped successes.


## Original-list reconciliation — 2026-10-28 continuation (not closure)

- The original eleven items are now recorded in
  `NATIVE-COMPOSER-AUTHORITATIVE-CHECKLIST.md`; no missing-list blocker remains.
- Local server suite at `a83af64`: **535 passed, zero failed/skipped** (523
  top-level tests), including 31 settings/draft contract tests and six browser
  mirror behaviors. The legacy endpoint no longer bypasses native draft CAS.
  Standalone strict TypeScript checking of `draft-mirror.ts` passed; this is not
  a successful whole-project TypeScript check.
- Scoped iOS source `3e2bf73115d6da90c1afd06eab2b6f5f1b4164f6`, run
  [40713513438](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40713513438),
  check `122904849816`: explicit **107 expected / 107 passed / 0 unverified**,
  `verified=true`; case-map digest
  `8e002b708c19cf6de839857063ea75fe7321a3317a204a97980874bc29c7bf53`.
  This predates the later file-only recovery, PiP and edit-launch changes.
- Android `c6565c2`, run
  [40716277261](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40716277261):
  JVM test step and both API30/API34 instrumentation steps succeeded. This
  executes the corrected failed-SQL checkpoint fixtures; it does not verify
  the later edit-generation or alternate schedule submission changes.
- Latest local Python contracts: **20 passed**. Source tripwires include
  alternate-scheduler pre-dispatch account ownership. Source checks are not
  native UI runtime proof. Current hosted native outcomes remain pending.


## Verified original-list implementation — `6f87622`

Source: `6f8762228e3178816e5a6d17ee8073a4980a118d`. The original eleven-item
mapping and explicit limits are in NATIVE-COMPOSER-AUTHORITATIVE-CHECKLIST.md.

### Android

Run [40722095034](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40722095034)
**completed successfully**: build and API30/API34 jobs, JVM tests, instrumentation,
signed-release assembly and both release smoke checks. Publishing was skipped
for the pull request; this is not deployment or physical-device acceptance.

Current instrumentation includes failed-SQL recovery, same-text ABA,
foreign-owner/wrong-text manager deletion, failed deletion retaining its
checkpoint, and exact held-schedule time display.

### iOS: exact current-source outcomes

Run [40722094764](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40722094764),
check `122935436308`, **completed successfully** on the exact source above.
The retrieved annotation was decoded and checked for that source SHA,
`verified=true`, **108 expected / 108 passed / 0 unverified**.

| XCTest suite | Explicitly passed / expected |
| --- | --- |
| ComposerAttentionTests | 4 / 4 |
| ComposerJournalTests | 9 / 9 |
| DraftHandoffTests | 9 / 9 |
| DraftJournalTests | 8 / 8 |
| DraftReceiptTests | 1 / 1 |
| PulseOutboxTests | 49 / 49 |
| ScheduledAcceptanceTests | 6 / 6 |
| ScheduledCommandJournalTests | 3 / 3 |
| ScheduledCreationRetryTests | 3 / 3 |
| TextDeliveryTests | 11 / 11 |
| ThreadDraftHandoffTests | 5 / 5 |

Case-map digest: `46e47f4d5fe813734f96f577884da2ee6e3ffd0a7816d289a37e8067845a1cf9`.
Full per-method outcomes remain in the run's evidence artifact and summary.
The current assertions include the original selected time alongside held-schedule
errors. Old 108-test runs are not substituted for this current-source result.

### Backend and local contracts

Native-server contracts run `40722095029` and web run `40722094842` succeeded.
Local server suite: **535 passed / zero failed/skipped** (523 top-level tests).
Server production/test source is unchanged since that complete run. Local Python
SQL/source/reporter contracts: **20 passed**. Standalone strict TypeScript checking
of `draft-mirror.ts` passed, not whole-project typechecking. No local native SDK
builds or native artifact downloads were required.

### Boundaries

Implementation and scoped regression verification are complete for the supplied
original eleven items, not every feature in the app. Source tripwires supplement,
not replace, runtime tests. The producer inventory records the audited pathways;
comprehensive physical UI/device acceptance remains deferred.

The separate full iOS run `40722094861` had passed archive and was still in its
build-test job at this observation. Its historical ReadPresentation failure is
not waived or declared repaired by these scoped successes. Migration/deployment,
PR merge and section 4 have not been performed. Checkpoint/all-storage and retry
clock limits remain explicit in the authoritative checklist.

The final evidence reconciliation changes documentation only; native/backend
source remains byte-for-byte the verified `6f87622` implementation.


## Reopened by requested recheck — 2026-10-29

The earlier all-eleven closure was too broad. This is a source/behavior gap,
not a demand to bring forward the deferred physical-device audit.

1. Main Android manager rows lacked observation-time revision/account authority.
   An edit-away/edit-back before Delete let the UI capture a fresh token and
   remove the new draft. The previous instrumented test captured its token
   **before** the ABA, so it did not cover the actual manager wiring. The new
   production snapshot helper is exercised with an explicit negative control:
   late recapture permits deletion, while the displayed snapshot refuses it.
   Bound producer observations, session-switch rejection and delayed deletion
   tests are added, along with the actual router producer-flow test. Swift
   manager callbacks now carry a view-generation fence through refresh/account
   changes, with a new DraftHandoff regression (109 selected tests expected).
2. `cross-device and legacy simultaneous semantic duplicates admit one instruction`
   failed twice in the recheck: all three requests returned 409 busy, no 201.
   Diagnostic output showed P1008 busy timeouts and P2028 expiration at the
   default 5000ms transaction deadline. A no-op write reservation did not fix it
   and was removed. The bounded 20000ms transaction/15000ms acquisition budgets
   allow the winner to survive the busy wait. Original identities, authorization
   checks, atomic writes and duplicate-conflict assertions remain unchanged.
   A separate test covers independent Prisma clients sharing the database.

Local recheck after fixes: **75 focused server tests passed, zero failed/skipped**;
**20 Python contracts passed**. Hosted native verification is pending. The old
535-test server and 108-test iOS results above must not be relabeled as current
verification of these new changes.


Recheck follow-through: Android bulk Clear also captures the collection rendered
by Compose, rather than reading a possibly newer StateFlow value inside the tap.
The complete local server rerun now passes **536 tests, zero failed/skipped**
(including the new independent-client scheduling test). Native verification of
these recheck changes remains pending; no earlier run is substituted for it.

A further callback audit found the same freshness borrowing in Android recovery
confirmations: the old code combined an old entry with the latest page owner.
Identical recovery payloads on another account could pass structural membership.
Recovery rows now retain their page's owner object through Copy, Retry and the
confirmation dialog; refresh replaces that authority. Both before launch and
inside the coroutine the original row must still belong to the displayed page.
Two additional helper regressions include an explicit negative control for the
old payload-equality guard. These additions still await hosted execution.


### Recheck results before the additional retry finding

- Full server rerun: **536 passed, zero failed/skipped**. Both same-client and
  independent-client scheduling contention tests additionally passed three
  repeated runs (two tests per run).
- Scoped iOS `40744247685`, check `123013682525`, source
  `582d29e96311de8ecd092896d4166a998faf84cb`: **109 expected / 109 passed /
  0 unverified**, verified=true. Digest:
  `16e00deef66e76a8424370f79d9287d12cbcb543aa971885626c13dae52e598a`.
  This includes the new manager fence/store test. Swift source, selector and
  reporter files were byte-unchanged from that commit through `457b46b`.
- Android `40745035980`, source `457b46b`: JVM and both API30/API34 instrumented
  steps passed; at observation the release assembly / remaining smoke completion
  was still pending. New manager helper tests include two negative controls for
  the old late-revision and latest-page-owner behaviors.

### Item 6 reopened: non-message replay also needs persisted admission

Source tracing showed ComposerJournal / PulseComposerReplay and ScheduledJournal /
PulseScheduledReplay had no Retry-After restore/hold gate. Their 429 handling only
ended that particular pass; another pass or restart could immediately retry. The
new gates share global durable metadata with the ordinary outbox, ignore unrelated
message-room holds, preserve instructions, and restore once into monotonic timing.
They hold on 429 and on 503 with Retry-After. Failed deadline commits keep the
memory hold and pending metadata; subsequent admission retries persistence.

New real-HTTP fixture regressions exercise both lanes as the first caller, both
429 and 503, repeated flushes, reopened stores, unchanged text/time, and a direct
transport negative control. Additional tests cover room/owner isolation, repeated
scans with wall-clock rollback, and failed deadline commit followed by recovery.
The shared Android loaded-deadline publication is serialized so one lane cannot
observe a restored marker before the other actually holds the gate.

Current local source/reporter/SQL contracts: **21 passed**. Expected scoped Swift
count is **112**. Native execution is pending; the preceding 109-test result must
not be relabeled as verification of these newest changes. The full iOS workflow
remains separate and is not declared green by the scoped run.

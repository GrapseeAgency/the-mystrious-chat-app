# Section 3 — source-to-regression reconciliation

Status: **scoped regression execution verified; original eleven-item checklist
still required for closure**.
This supplements `NATIVE-COMPOSER-IMPLEMENTATION.md`, whose previous blanket
closure statement has been withdrawn. It does not substitute a new list and call
it the recovered original. Deployment and physical-device acceptance stay deferred.

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

- Original eleven-item wording is still unavailable; this is a source-derived
  reconciliation, not a recovered original or proof that nothing was omitted.
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

- The user explicitly selected **require the original list**, not approval of
  this reconstructed checklist. Exact eleven-item closure remains unresolved
  until the original subitems are supplied/recovered.
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

## Verified scoped result — final follow-up

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
the unavailable original eleven-item list. The user explicitly required that
original list; overall section closure therefore remains open pending it.
The unrelated full-suite read-presentation failure, deployment and comprehensive
physical-device acceptance are not waived by these scoped successes.

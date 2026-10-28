# Section 3 — authoritative eleven-item checklist

**Recheck still open: item 6 replay-lane admission.** The prior all-eleven
closure was too broad. The requested recheck found and repaired displayed-row /
recovery-confirmation authority gaps in item 7 and scheduling contention in item 8.
The latter had reproducibly returned three busy responses and created nothing.
The repaired backend passes 536 tests; both contention contracts also passed
three repeated runs. The repaired Swift manager passed 109/109 selected tests in
run `40744247685` (`582d29e`); Swift source was unchanged through `457b46b`.
Android `457b46b` passed JVM and API30/API34 instrumentation, including the new
manager snapshot tests; release workflow completion is recorded separately.

A further source audit found that draft/edit and scheduled-command replay stopped
on 429 but did not persist/consult Retry-After on their next pass. Item 6 is
therefore reopened. Both lanes now share durable global admission on each native
platform, including 503 with Retry-After. Message-room slow-mode holds do not
unnecessarily block draft saving/scheduling. Failed deadline writes retain an
in-process hold and retry metadata persistence when storage returns.

These newest changes and their HTTP/reopen/fault regressions await hosted native
execution (112 selected Swift tests now expected). **21 Python contracts pass**,
which is not native runtime proof. No section 4, deployment or PR merge is included.

## Original requirements and implemented behavior

| # | User's exact item | Implementation / evidence |
| --- | --- | --- |
| 1 | Cross-device draft conflicts: Remote draft mirrors lack a server revision/precondition contract. | Shared server CAS, immutable operation identities and native replay protect drafts against stale writes. The legacy `/draft` endpoint now uses the same contract: missing preconditions return 428; conflicts/superseded flights return 409. The current browser mirror preserves its first observed baseline and retries exact frozen flights. `settings-write-order` and `draft-mirror` server tests, native ComposerJournal tests. |
| 2 | Thread draft synchronization: Thread drafts remain device-local rather than cross-device synchronized. | Server ThreadDraftState and native root-scoped composer intents synchronize thread drafts through the same CAS contract. A thread cannot consume the main draft or another root's draft. Native thread handoff/token and server composer-operation regressions. |
| 3 | Failed-storage durability: An in-memory recovery buffer cannot preserve the last keystrokes through process death when SQLite cannot commit. | Independent atomic private-file checkpoints preserve normal/thread drafts and edit snapshots after their writer completes. Transactional SQLite UUID acknowledgements prevent consumed files from resurrecting text; file-only drafts are visible in recovery. Actual failed-SQL/reopened-store tests pass on both platforms. The physical limits below remain explicit. |
| 4 | Continuous draft observation failure: Buffer-first restoration does not establish recovery from every later Room observation failure. | RecoveringDraftObservation combines independent buffer signals, Room resubscription and periodic snapshot retries for main/thread drafts. Cancellation propagates; failed reads never invent a clear. RecoveringDraftObservationTest and checkpoint/reopen regressions passed. |
| 5 | Durable edit recovery: Message edits do not have the same complete durable intent/replay treatment as ordinary outgoing messages. | Durable immutable edit intents retain the original server baseline, uncertain replay identity and independently checkpointed unsubmitted text. Revision-owned ACKs and pre-launch editor guards preserve newer edits, including same-text ABA during a suspended lookup. Native ComposerJournal, DraftSnapshotIntegrity and checkpoint tests. |
| 6 | Retry scheduling persistence: Process-local cooldowns/retry deadlines and conservative queue blocking still need restart/fairness follow-through. | Account/room/global outgoing-message cooldowns persist across restart, restore into monotonic gates once, and retire expired cache entries. Retry-After/503 handling and per-room FIFO/head rotation avoid letting a blocked room monopolize healthy work. Native OutboxRetryJournal/PulseOutbox restart and fairness regressions plus actual-query SQL tests. Recheck: composer/edit and scheduled-command replay also need to honor these deadlines. Their admission wiring and new HTTP/reopen/fault tests are implemented but not yet natively verified. |
| 7 | Every send entry point: Ancillary rich-message, alternate-screen, and background paths still need a complete ownership/durable-dispatch audit. | Producer inventory is in NATIVE-COMPOSER-SEND-PATHS.md. Main, alternate, thread, PiP, rich/poll/forward/attachment handoff, notification, background, edit and schedule paths were traced. Repairs include account capture before async dispatch, guarded PiP hydration, picker failure retention and exact draft consumption. Recheck fix: recovery-manager rows now freeze account and draft revision during observation, not at a later tap; delayed Swift callbacks are invalidated on refresh. Seven manager JVM helper tests, the producer-router regression and the new Swift manager fence/store regression passed; these are not physical UI instrumentation. Native runtime tests and source tripwires supplement this source audit; comprehensive UI/device acceptance remains deferred. |
| 8 | Legacy scheduled-create duplication: Older/no-operation-ID callers remain non-idempotent; cross-device semantic deduplication is also absent. | Deterministic legacy identities, exact-instruction cross-device semantic deduplication, legacy-row adoption and terminal protection are implemented. Different IDs for the same instruction return an explicit duplicate conflict, not an unsafe aliased success. Recheck fix: the transaction budget outlasts SQLite busy waits; same-client and independent-client contention contracts now pass. The current full server rerun passed 536 tests and both contention contracts passed three further repetitions. The additive semantic index patch is supplied but not deployed. |
| 9 | Offline scheduling queue: Scheduled-message creation is not a complete durable offline scheduling feature. | Both platforms persist create/cancel commands before HTTP, replay immutable instructions after reconnect/restart, and retain uncertain/held work for review. Expired instructions are not silently moved into the future. An unresolved room head cannot be replaced accidentally with a newly selected instruction. Native ScheduledCommandJournal/ScheduledCreationRetry and backend contracts. |
| 10 | Recoverable pending-schedule UI: Closing/reopening or restarting does not fully restore an uncertain schedule’s exact selected instruction. | Paged recovery retains original body/time/identity and retries that instruction rather than opening a new one. Both apps show the exact original time even when a held/error notice is present. Current Android instrumentation and Swift reopened-store assertions verify this held-state display. Removing recovery is explicitly not remote cancellation. |
| 11 | Operation-record retention: Long-term retention/cleanup policies for uncertain and terminal operation receipts need completion. | NATIVE-COMPOSER-RETENTION.md defines per-family policies. Compact replay identities are deliberately permanent; uncertain user instructions never age out. Checkpoint maintenance reads at most 100 payloads/pass with a restart cursor; retry cleanup is capped at 500 rows, and notice cleanup at 500 children/100 parents. Matching UUID/version checks protect newer recovery. Native maintenance and server retention tests pass. |

## Historical baseline (superseded by the recheck evidence above)

- **Android:** [40722095034](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40722095034)
  completed successfully on the source above: JVM tests, API30/API34
  instrumentation, signed-release assembly and both release smoke checks.
  Publishing was skipped for the pull request; no release/deployment is implied.
- **Scoped iOS:** [40722094764](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40722094764),
  check `122935436308`, completed successfully on the same source. The retrieved
  annotation explicitly reports **108 expected / 108 passed / 0 unverified**,
  `verified=true`, across all eleven selected XCTest suites. This is not inferred
  from the absence of failure messages. Full counts/digest are in the verification ledger.
- **Backend:** 535 local tests passed, zero failed/skipped (523 top-level tests).
  Server source is unchanged since that run; current native-server contracts run
  `40722095029` also succeeded. Local Python SQL/source/reporter contracts: **20 passed**.
- Standalone strict TypeScript checking of `draft-mirror.ts` passed. This is not
  a successful whole-project TypeScript check or a whole-web-app audit.

## Explicit limits and deferred work

Checkpoint durability starts when its asynchronous writer completes—not before
a pending UI task runs. Independent files address SQLite-commit failure, not
failure of all physical storage, unwritten keystrokes, whole-database startup
corruption or device loss. Corrupt recovery bytes are preserved, not guessed.

Retry deadlines use persisted wall-clock admission times across restart and
monotonic deadlines within a process. Holds are bounded to one day; changing the
wall clock across restart is not a trusted server clock. Server admission remains
authoritative, and neither platform promises an exact background wake time.

Permanent compact receipts imply growing metadata, not constant total storage.
Directory-name enumeration is not constant-memory. Coordinated account erasure
is a separate privacy policy, not an age-based deletion of uncertain operations.

The separate full iOS workflow `40722094861` had passed archive but was still
building/testing at this observation. The historical ReadPresentation failure
is not waived or declared fixed by scoped composer success. Full-app acceptance,
physical-device testing, migrations and deployment remain later work.

Old unconditional web draft PATCH clients must upgrade: accepting their blind
writes would reopen item 1. The current browser client was migrated alongside
the endpoint; no whole-web account-isolation claim is made.

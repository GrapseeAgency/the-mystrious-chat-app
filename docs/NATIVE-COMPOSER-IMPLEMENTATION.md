# Section 3 — drafts, outgoing messages, edits and scheduling

> **Current status:** The original eleven items have now been supplied and are
> tracked in [the authoritative checklist](NATIVE-COMPOSER-AUTHORITATIVE-CHECKLIST.md).
> The reconstructed grouping and older counts below are historical, not the
> acceptance boundary. Latest local backend run: 528/528; current architectural /
> SQL / reporter contracts: 16/16. Hosted scoped iOS at `f6f1e46`: 103/103,
> zero unverified (run 40709607487). Independent checkpoints and later producer /
> retention fixes are newer and still require native verification. Section 3
> remains open; no advance to section 4.


**Original-checklist closure still open; scoped native verification now complete.**
The previous implementation-closure statement was
stronger than the available evidence. Android and backend checks passed, but a
failed full iOS run with no reported composer failures is not explicit proof that
every composer regression ran and passed. A separate fail-closed iOS test check
requires a terminal `passed` result for every selected test. It has now verified
**102/102** at `7542dcb`; it supplements, not replaces, the full iOS suite.

The eleven rows below are a **reconstructed working checklist**, not a recovered
verbatim copy of the original eleven-item backlog. Reconciliation must identify
its historical sources and must not quietly substitute this list as the original.
Autumn styling remains unchanged. Deployment and physical-device auditing stay
deferred. The existing hosted read-presentation failure remains unresolved.

## Eleven acceptance areas

1. Server draft revision/conflict ordering, including explicit clears.
2. Durable retry and cross-device reconciliation of draft mirrors.
3. Local draft ownership/durability across room disposal, process recovery and ancillary writers.
4. Durable unsent edit text and recoverable edit operations.
5. Edit identity/revision ordering across lost responses and competing devices.
6. Fair outgoing replay without violating per-room FIFO or account ownership.
7. Durable enqueue/acknowledgement across all message-producing native entry points.
8. Scheduled-create identity and immutable accepted instructions across picker/retry lifetimes.
9. Durable pending scheduling/cancellation and recovery after restart/offline uncertainty.
10. Scheduled dispatch, delivery notification recovery and authorization at execution.
11. Retention/terminal receipts and bounded recovery, without resurrecting completed operations.

## Current implementation evidence

### Fair outgoing replay

Android and iOS select only each room's oldest pending operation. Persisted
attempt counts rank room heads across retries; later operations in a failed
room cannot jump ahead. Each drain round serves other eligible rooms before
returning to a successful room's backlog. Global authentication/rate-limit
holds remain global; unknown transport failures still end the current pass.
No pending sends are evicted. Android's SQL retains explicit owner filtering;
Swift's database remains account scoped.

Added native engine regressions for large healthy backlogs and a failed head
across consecutive passes. A local Python/SQLite regression executes the actual
production Kotlin and Swift selection SQL and verifies fairness, FIFO and owner
isolation. Native execution results and their limits are recorded below.

## Implementation reconciliation (2026-10-28)

The eleven rows above are historical implementation groupings; the supplied original list is now authoritative. Implementation has been traced in both apps and the backend, but item-by-item
evidence and explicit iOS test outcomes are being reconciled. This is not a
completion declaration.

| Area | Implemented behavior / principal evidence |
| --- | --- |
| 1. Revision/clear conflicts | `composer-operation.ts` binds owner, membership, target and immutable operation identity to a monotonic draft revision. Explicit clears participate in CAS; accepted/conflicted/superseded identities cannot be silently rebased. `composer-operation.test.mjs`. |
| 2. Cross-device draft mirror | Both composer journals freeze flights separately from newer desired text, retain failed/conflicting instructions and page replay. Unknown local baselines cannot overwrite an already-revised remote clear. Import rechecks revision, pending intents and failed-write front buffers. `ComposerReceiptTest`, `ComposerJournalTests`, `AccountDatabaseIsolationTest`. |
| 3. Local ownership/durability | Room/thread/PiP producers synchronously reserve revisions and retain front buffers until SQLite commits. Late disposal cannot overwrite a newer owner. Displaced iOS windows retain separate copy/remove recovery records rather than silently dropping their text. Draft handoff/journal tests exercise process reopening, independent stores, stale tokens and disk failures. |
| 4. Unsent edit text | Per-owner/message local edit snapshots retain immutable revision IDs and original baselines. Typing does not automatically send edits. Android disk-write failures now reach both room presentations; front buffers remain available. |
| 5. Edit ordering | Backend edit fingerprints include membership, content and edit timestamp; native immutable flights and local edit UUIDs prevent an old ACK from consuming newer typing. Accepted baseline caches now contain metadata and exact-text hashes, not another retained message body. |
| 6. Fair outgoing replay | Room heads are selected by persisted attempts with FIFO within each room and ownership filtering. Other eligible rooms progress without deleting failed intentions. Native engine tests and the production-SQL SQLite regression cover the selection. |
| 7. Producer handoff | Text, rich messages, forwards, notification replies, threads, PiP and polls use durable enqueue before acceptance/clear. Polls now share the standard message receipt and transactional poll/options creation; the legacy backend poll route delegates to that same authorization and policy path. Both poll composers stay open on persistence failure. `poll-outbox.test.mjs`, `TextDeliveryTests`, `AccountDatabaseIsolationTest`. Attachment capture/import itself remains section 4. |
| 8. Schedule identity | Original time/body/client ID are immutable and retained through uncertain responses. Accepted metadata receipts verify an instruction hash. UI acknowledgement waits for draft durability before retiring identity. An accepted but unconsumed background schedule keeps its room head fenced until acknowledgement or explicit recovery removal. |
| 9. Schedule/cancel recovery | Pending, held and accepted namespaces survive restart and expose scoped paged recovery. Cancellation remains visibly unconfirmed until a strict cancellation receipt. Retry never moves the original time forward. Text-only scheduling refuses anonymous, reply, topic, edit and armed-effect contexts instead of silently losing their metadata. |
| 10. Dispatch/notices | Execution rechecks membership/announcement/block/slow-mode/expiry policy. Message and notification work commit atomically. Socket and push channels retry independently; device-binding receipts and token pagination bound replay. Lease renewal before channels cannot revive expired ownership. `scheduled-access.test.mjs`, `push-delivery.test.mjs`. |
| 11. Retention/recovery | Delivered and explicitly discarded schedule rows scrub content; native accepted schedule receipts retain metadata/hash only. Recovery is owner-scoped, keyset paged and compare-and-swap protected, with explicit server-action warnings. Seven-day completed-notice cleanup removes at most 500 device receipts and 100 notice records per invocation; message/send identity receipts are not evicted. |

### Verification and remaining acceptance boundary

- Latest backend execution after poll, notice lease/retention and sent-content
  changes and native recovery SQL coverage: **525 passed, 0 failed, 0 skipped**
  (513 top-level tests).
- Exact native candidate `adb34904c63c1f1af2a22cc86669374ffa19fce8` has
  completed Android and iOS execution. See the exact outcomes below; the iOS
  test-job failure is not described as a pass.
- Notification hints are at-least-once: provider acceptance followed by a lost
  receipt can duplicate a hint, but cannot create another scheduled Message.
- Autumn styling is retained. SQL rollout, matching-backend deployment and
  comprehensive physical Android/iOS acceptance remain deferred as requested.
- This ledger does not assert whole-app correctness or completion of sections 4–16.

### Latest verification notes

- Full backend suite, including execution of both native recovery pagination
  queries against SQLite: **525 passed, 0 failed, 0 skipped** (513 top-level).
  The added query test crosses every recovery namespace and wildcard-containing
  owner IDs with 100-row keyset pages.
- TypeScript 5.9.3 successfully checked all 13 changed backend source files and
  their transitive imports with the project compiler settings (exit 0).
- Full-repository TypeScript checking still reports 20 diagnostics outside this
  section (web call/profile/icon code and auxiliary scripts/fixtures). That is
  not represented as a full-repository typecheck pass.
- Failed native edit writes now retain a shared per-store front buffer as well
  as reporting the storage error. Enqueue commits that buffer and its original
  baseline with the edit operation; obsolete Android typing writers are fenced
  from recreating a snapshot after acceptance.

- Execution-time rejected schedules intentionally retain their **unsent** text
  for the existing failed-schedule review UI; explicit discard erases that text
  and keeps the owner-bound terminal receipt. This is distinct from a delivered
  message body, which is scrubbed from its scheduling row.
- Confirmed cancellation also releases an unconsumed creation's room fence via
  a direct metadata-only lookup. An older acknowledgement cannot release a newer
  replacement's fence. Both native scheduled-journal tests exercise this order.
- Cancellation marks the original creation receipt terminal too, including
  cancellation after its composer was already acknowledged. Retrying the old
  body/time cannot return a cached “scheduled” success for a known cancellation.

## Previous closure evidence — 2026-10-28 (claim reopened)

Native production code and native test sources at closure are unchanged from
`adb34904c63c1f1af2a22cc86669374ffa19fce8`.

| Check | Actual result |
| --- | --- |
| Android run `40686534307` | **Success**: build `122810204821`, API 30 instrumented `122810205234`, API 34 instrumented `122810205245`. Publishing was skipped, not performed. |
| iOS run `40686534406` | Archive `122810205602` **success**; simulator build **success**; build-test job `122810205100` **failure**. |
| iOS failure review | All 25 check annotations were retrieved. The only named failing test is `ReadPresentationTests.testHostedSwiftUIRoomAndThreadHaveIndependentUnobscuredAnchors` at line 90: the hosted thread sheet remains presenting, with transition progress 0.0. No section 3 test failure is reported. This is not equivalent to a green complete iOS suite. |
| Backend run `40686534225` | **Success** on the same candidate. Prior full local execution: 525 passed, 0 failed, 0 skipped. |
| Closure SQL regression | **2 passed** locally: actual native recovery pagination and outbox fairness queries. Recovery coverage now also inserts internal schedule head/reference keys and proves neither is exposed as a user-removable recovery row. |

The iOS read-presentation failure also occurred on preceding candidates and
remains unresolved; its cause is not asserted to be a simulator-only flake.
It remains in the concrete-failure/device-acceptance backlog, not silently
waived or relabeled as a section 3 success. The full iOS run log could not be
retrieved in this environment; the exact failure statements above are from
GitHub check annotations, not an invented per-test pass count.

The previous statement closing all eleven rows is withdrawn pending the
reconciliation and explicit verification described above.
Deployment, schema-patch application, matching-backend/device acceptance,
the unresolved broader iOS test failure, and sections 4–16 are not closed by
this document. No release replacement or pull-request merge was performed.

## Reconciliation follow-up

See [source-to-regression reconciliation](NATIVE-COMPOSER-VERIFICATION.md) for
historical requirement sources, the eleven working-area mappings, explicit limits
and the newly discovered Android preflight-refusal ownership correction. Both
Android instrumentation steps passed the new regression; the corrected scoped
iOS run explicitly passed 102/102 methods. The user requires the original list,
so these results do not authorize closing all eleven reconstructed rows.

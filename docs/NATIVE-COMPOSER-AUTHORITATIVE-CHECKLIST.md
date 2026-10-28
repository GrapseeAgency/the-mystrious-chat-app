# Section 3 — authoritative eleven-item checklist

The user supplied this original list after `bdd04f7`. It supersedes the
reconstructed grouping in the earlier implementation/verification ledgers.
**Not complete.** Earlier 102-test scoped iOS success cannot close newly identified
requirements. Deployment and real-device auditing remain deferred; autumn remains.

| # | User's exact item | Reconciliation / remaining work |
| --- | --- | --- |
| 1 | Cross-device draft conflicts: Remote draft mirrors lack a server revision/precondition contract. | Implemented server draft CAS and immutable native replay. Verify against original requirement, not old OPEN label. |
| 2 | Thread draft synchronization: Thread drafts remain device-local rather than cross-device synchronized. | Current server ThreadDraftState + scoped native composer intents cover threads as well as main drafts. Reconcile entry points and tests. |
| 3 | Failed-storage durability: An in-memory recovery buffer cannot preserve the last keystrokes through process death when SQLite cannot commit. | Independent atomic private-file checkpoints now cover normal/thread drafts and edit snapshots, with SQLite consumption UUIDs preventing send/clear resurrection. File-only manager rows and exact deletion added. New native fault tests await hosted execution. No software can guarantee survival of unwritten bytes when all storage fails. |
| 4 | Continuous draft observation failure: Buffer-first restoration does not establish recovery from every later Room observation failure. | Added independent buffer events, Room resubscription and periodic snapshot-error recovery for main/thread observations; cancellation propagates and failed reads never invent a clear. Native validation pending. |
| 5 | Durable edit recovery: Message edits do not have the same complete durable intent/replay treatment as ordinary outgoing messages. | Durable edit intents, original baseline, failed-write buffer, revision-owned ACK and explicit recovery implemented. Independent JSON checkpoints also preserve the original edit baseline when SQLite fails; new lost-buffer/reopen/acceptance test pending. All-storage-failure limitation remains item 3. |
| 6 | Retry scheduling persistence: Process-local cooldowns/retry deadlines and conservative queue blocking still need restart/fairness follow-through. | Added persisted account/room/global cooldowns on both platforms, restored once into monotonic gates; 503 Retry-After also respected. Expired retry metadata cleanup capped at 500. New restart tests pending. Existing room-head fairness remains. |
| 7 | Every send entry point: Ancillary rich-message, alternate-screen, and background paths still need a complete ownership/durable-dispatch audit. | Existing durable producers reviewed previously; needs explicit original-scope path inventory, not a blanket assertion based on shared helper tests. |
| 8 | Legacy scheduled-create duplication: Older/no-operation-ID callers remain non-idempotent; cross-device semantic deduplication is also absent. | Implemented deterministic legacy identities, cross-device exact-instruction semantic deduplication, legacy-row adoption, and terminal protection. Different operation IDs receive an explicit duplicate conflict rather than an unsafe aliased success. Scheduled suite 27/27 and full server suite 528/528 pass locally. Additive semantic index patch not deployed. |
| 9 | Offline scheduling queue: Scheduled-message creation is not a complete durable offline scheduling feature. | Native pending/held schedule journals now persist before HTTP. Expired offline instructions must be retained for review, not silently moved into the future. Verify all entry points. |
| 10 | Recoverable pending-schedule UI: Closing/reopening or restarting does not fully restore an uncertain schedule’s exact selected instruction. | Existing paged recovery preserves exact original time/body/identity; retry does not open a new instruction. Verify current managers against original requirement. |
| 11 | Operation-record retention: Long-term retention/cleanup policies for uncertain and terminal operation receipts need completion. | Needs explicit policy and enforcement per record family. Never delete an uncertain operation or terminal deduplication protection just to cap storage. Existing delivered-body scrubbing / notice cleanup is only part of this item. |

Retry deadlines use persisted wall-clock admission times across restart and
monotonic deadlines during one process lifetime. They are bounded to one day;
manual wall-clock manipulation across restart is not a trusted server clock.
Server-side admission remains authoritative. No on-device exact wake-time guarantee.

Checkpoint durability begins after its asynchronous writer completes, not before a pending UI task runs. These files are independent SQLite-failure recovery, not redundant physical storage. All-storage failure and whole-database startup corruption remain explicit limits. Current native checkpoint additions are not yet compiler/runtime verified.

# Section 3 — authoritative eleven-item checklist

The user supplied this original list after `bdd04f7`. It supersedes the
reconstructed grouping in the earlier implementation/verification ledgers.
**Not complete.** Earlier 102-test scoped iOS success cannot close newly identified
requirements. Deployment and real-device auditing remain deferred; autumn remains.

| # | User's exact item | Reconciliation / remaining work |
| --- | --- | --- |
| 1 | Cross-device draft conflicts: Remote draft mirrors lack a server revision/precondition contract. | Implemented server draft CAS and immutable native replay. Final writer audit also closed the compatibility /draft endpoint: blind writes now return 428, and revision/operation-ID writes use the same CAS ledger. Current web mirror preserves immutable flights and observed baselines; conflicts never silently rebase. Backend suite now 535/535; native replay covered by scoped regressions. Current shared-store additions still await native verification. |
| 2 | Thread draft synchronization: Thread drafts remain device-local rather than cross-device synchronized. | Current server ThreadDraftState + scoped native composer intents cover threads as well as main drafts. Both thread composers hydrate/stage root-scoped intents through the same CAS contract; scoped thread handoff tests passed at f6f1e46. |
| 3 | Failed-storage durability: An in-memory recovery buffer cannot preserve the last keystrokes through process death when SQLite cannot commit. | Independent atomic private-file checkpoints now cover normal/thread drafts and edit snapshots, with SQLite consumption UUIDs preventing send/clear resurrection. File-only manager rows and exact deletion added. New native fault tests await hosted execution. No software can guarantee survival of unwritten bytes when all storage fails. |
| 4 | Continuous draft observation failure: Buffer-first restoration does not establish recovery from every later Room observation failure. | Added independent buffer events, Room resubscription and periodic snapshot-error recovery for main/thread observations; cancellation propagates and failed reads never invent a clear. Hosted f6f1e46 scoped iOS passed 103/103; current Android/checkpoint changes still need terminal native results. |
| 5 | Durable edit recovery: Message edits do not have the same complete durable intent/replay treatment as ordinary outgoing messages. | Durable edit intents, original baseline, failed-write buffer, revision-owned ACK and explicit recovery implemented. Independent JSON checkpoints also preserve the original edit baseline when SQLite fails; new lost-buffer/reopen/acceptance test pending. All-storage-failure limitation remains item 3. |
| 6 | Retry scheduling persistence: Process-local cooldowns/retry deadlines and conservative queue blocking still need restart/fairness follow-through. | Added persisted account/room/global cooldowns on both platforms, restored once into monotonic gates; 503 Retry-After also respected. Expired retry metadata cleanup capped at 500. iOS restart regression passed in the 103/103 f6f1e46 run; Android/current candidate verification pending. Existing room-head fairness remains. |
| 7 | Every send entry point: Ancillary rich-message, alternate-screen, and background paths still need a complete ownership/durable-dispatch audit. | Source inventory in NATIVE-COMPOSER-SEND-PATHS.md. Audit found and fixed Android PiP pre-persistence clear/memory-only draft, alternate sticker/location swallowed failures, and rich/poll account capture gaps. Added PiP handoff tests and architectural tripwires; new native runtime verification pending. |
| 8 | Legacy scheduled-create duplication: Older/no-operation-ID callers remain non-idempotent; cross-device semantic deduplication is also absent. | Implemented deterministic legacy identities, cross-device exact-instruction semantic deduplication, legacy-row adoption, and terminal protection. Different operation IDs receive an explicit duplicate conflict rather than an unsafe aliased success. Scheduled suite 27/27 and full server suite 535/535 pass locally. Additive semantic index patch not deployed. |
| 9 | Offline scheduling queue: Scheduled-message creation is not a complete durable offline scheduling feature. | Native pending/held schedule journals now persist before HTTP. Expired offline instructions must be retained for review, not silently moved into the future. Verify all entry points. |
| 10 | Recoverable pending-schedule UI: Closing/reopening or restarting does not fully restore an uncertain schedule’s exact selected instruction. | Existing paged recovery preserves exact original time/body/identity; retry does not open a new instruction. Verify current managers against original requirement. |
| 11 | Operation-record retention: Long-term retention/cleanup policies for uncertain and terminal operation receipts need completion. | Per-family policy in NATIVE-COMPOSER-RETENTION.md. Compact replay receipts are deliberately permanent; uncertain bodies never age out. Added bounded (100 payloads/pass), restart-cursored consumption-file cleanup on both apps, alongside 500-row retry/500-child+100-parent notice cleanup. New native maintenance tests pending. |

Retry deadlines use persisted wall-clock admission times across restart and
monotonic deadlines during one process lifetime. They are bounded to one day;
manual wall-clock manipulation across restart is not a trusted server clock.
Server-side admission remains authoritative. No on-device exact wake-time guarantee.

Checkpoint durability begins after its asynchronous writer completes, not before a pending UI task runs. These files are independent SQLite-failure recovery, not redundant physical storage. All-storage failure and whole-database startup corruption remain explicit limits. Current native checkpoint additions are not yet compiler/runtime verified.

Latest intermediate iOS evidence: `73049c2`, run 40713138802, **107/107
passed, zero unverified**, including bounded checkpoint maintenance. Later
file-only recovery, mini-chat hydration and exact-revision restoration changes
still require their own hosted result. Android checkpoint fault fixtures exposed
two fixture defects (duplicate DataStore instances, then the iOS spelling of the
Android cache table); both corrected, without removing the failed-storage tests.
Local contracts now 18/18. No native SDK builds run in the sandbox.

Compatibility note: old unconditional web draft PATCH clients now receive 428
and must upgrade; allowing them to overwrite a protected native draft would
leave item 1 open. Current web client was updated alongside the endpoint. This
is a targeted safety migration, not a whole-web-app audit.

Additional producer review: main/alternate edit launch checks now reject displaced
composer revisions, and Android edit ACK ownership captures the typing generation
before the first suspended read. Alternate schedule submission also captures the
Android account receiver and checks the Swift sheet/session before async dispatch.
These latest producer changes still need their own hosted native verification.

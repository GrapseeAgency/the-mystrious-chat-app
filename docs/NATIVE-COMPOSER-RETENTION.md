# Section 3 operation retention policy

This policy distinguishes **replay identity** from disposable delivery hints. A
fixed TTL on identity would turn a late retry into a second send. There is no
server-enforced maximum offline lifetime, so permanent compact deduplication
receipts are intentional; constant total metadata size is not promised.

| Record family | Retention / disposal |
| --- | --- |
| Server message/send identity, composer operation, scheduled creation operation | Keep compact identity, request fingerprint and outcome. No body or age-based eviction; never reuse a terminal identity. Account erasure requires a separate coordinated privacy policy, not this housekeeping path. |
| Native pending outbox, composer desired/flight, held edits, pending/held schedule or cancellation | Keep exact instruction until authoritative acceptance or explicit version-checked local removal. No age/size eviction. Removing local recovery is not remote cancellation. |
| Native accepted composer baselines | Metadata plus exact-text hash, not another copy of the body. Keep revision/predecessor safety. |
| Native accepted schedule/cancellation receipts and creation references | Metadata/hash and identity; no scheduled body. Keep head/reference fencing until consumption/cancellation or explicit checked removal. Server permanent receipts still prevent recreation after local removal. |
| Active draft/edit snapshots and independent checkpoints | Keep user text, including explicit clears, until superseded, committed, consumed or explicitly discarded. Never expire by age. Unreadable recovery bytes are retained rather than guessed or discarded. File-only edits recover on message reopen and are rehydrated into the recovery manager by bounded maintenance. iOS displaced-window copies use the same independent path. Rehydration creates unsubmitted recovery, never a network intent. Normal/thread file-only drafts appear in the draft manager. |
| Consumed checkpoint files | SQLite UUID ACK commits with save/send/clear/edit capture. Remove matching file only after that commit; mismatched/newer UUID survives. Successful save and recovery reads clean immediately. Background maintenance reads at most 100 file payloads per pass, removes at most 100 acknowledged files, and persists a lexical cursor across restarts. Directory name enumeration is not constant-memory. Compact per-scope ACKs are retained. |
| Retry-admission deadlines | Expired wall-clock rows only, at most 500 deleted/pass; no user content or replay receipts share this prefix. During-process timing remains monotonic. |
| Scheduled message bodies | Scrub on sent or explicit discard/cancellation; refused instructions stay recoverable. Identity remains even if content is gone. |
| Scheduled delivery hints and push binding receipts | Stop stale hint retries after 24h. Seven days after completion, delete at most 500 child receipts and 100 child-free parent notices/pass. This cannot recreate a scheduled message. |

Native recovery remains owner-scoped, keyset paged (100 displayed rows plus a
lookahead) and version checked. Maintenance never truncates uncertain work just
to make storage numbers small. The independent file sweep is best effort while
the app runs; neither platform promises an OS background wake at an exact time.
All-storage failure, unwritten UI tasks, database startup corruption and device
loss are not solved by a second write path on the same device.

Evidence: scheduled-access/push-delivery/composer-operation server contracts,
native recovery SQL contracts, retry reopen tests, and new checkpoint bounded
scan/consumption tests. The `6f87622` implementation passed Android JVM/API30/API34
and all 108 selected iOS regressions; exact outcomes are recorded in
NATIVE-COMPOSER-VERIFICATION.md. Deployment and physical acceptance remain deferred.


Item-6 recheck addition (native verification pending): the same compact global
admission record now gates composer/edit and scheduled-command replay, not only
message outbox sends. Failed metadata commits retain a memory hold and retry the
metadata write; this does not promise durability through process death while all
writes fail. Neither the hold nor its expiry deletes an original instruction.

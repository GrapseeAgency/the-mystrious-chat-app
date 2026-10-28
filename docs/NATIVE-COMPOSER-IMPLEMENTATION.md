# Section 3 — drafts, outgoing messages, edits and scheduling

In progress. Both native platforms and the backend are required. Autumn styling
is unchanged; deployment and comprehensive physical-device auditing remain deferred.
Existing repairs are retained, not counted as newly discovered missing features.

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
isolation. Native SDK execution of this section's changes remains pending.

This document is a work ledger, not a declaration that all eleven areas are done.

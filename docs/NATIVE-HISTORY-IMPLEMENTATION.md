# History, search, mentions and saved content — implementation work

Deployment and physical-device auditing are deferred by the owner. Completion in
this workstream means implemented native/backend paths plus code-level validation,
not a guarantee that no undiscovered defects exist. Autumn styling is unchanged.

## Work ledger

1. **Old-message jump boundary:** replacing fourteen-page scans with indexed,
   authorized exact-message windows in Android primary/Mirror and the shared iOS
   room model. Sparse jumps do not move the ordinary contiguous history cursor.
2. **Server anchor/window lookup:** `/message-window` supports exact anchors and
   both tuple-cursor directions; parent/membership/expiry checks share a snapshot.
   Additive navigation indexes are in `20261028_history_navigation.sql`.
3. **Very large threads:** incremental transport/rendering work remains.
4. **Cross-page reconciliation:** implementation work remains.
5. **Streaming exports:** implementation work remains.
6. **Exhaustive global search:** implementation work remains.
7. **Exhaustive mentions:** implementation work remains.
8. **Exact mention entry points:** implementation work remains; window lookup is
   the shared navigation foundation, not yet closure of every entry point.
9. **Mention badge freshness:** implementation work remains.
10. **Saved pagination:** implementation work remains.
11. **Durable ordered saved state:** implementation work remains.

Window tests use real Prisma/SQLite, 6,000 equal-time rows, deleted cursor anchors,
thread/root checks, and authorization/expiry failures. Native SDK validation is
still required for this new implementation. No item is closed merely because a
historical backlog label was removed.

# Native message operation contract

Native clients assign a `clientId` **before** a send, retain it in the outbox, and reuse it for every automatic retry. Deliberate separate sends use separate IDs, even if their content is identical. The server scopes uniqueness by `(senderId, clientId)` and fingerprints the complete submitted message including conversation, reply/thread/topic, media, rich payload and privacy flags.

- First send: message and operation receipt commit in the **same SQLite transaction**.
- Replay: returns the original message, without repeating broadcast, push, bot, XP or streak side effects. Replay can precede slow-mode and attachment revalidation because membership is still checked.
- Key reused for another request: **409**. Clients must not silently allocate a new key to evade this conflict.
- Original message removed/expired: **410**. Receipts survive deletion so retry cannot resurrect it.
- Clients without a key retain legacy behavior. This is not an authentication fix: existing missing-token/name-only server contracts remain a separate release blocker.
- Concurrent attempts can receive a transient SQLite contention failure. Retrying the **same key** converges on one committed message; success is never inferred from a timeout.

## Deployment order

1. Back up the server SQLite database.
2. Apply `prisma/patches/20261019_message_send_operation.sql` to that database before deploying the route. It is additive and rerunnable, retains all existing messages, and needs no destructive reset. The Prisma schema also declares the table; regenerate the client as part of the normal server build when using schema-driven deployment. The contract implementation uses parameterized SQL and works with the current checked-in generated client.
3. Deploy the route, then native builds. Old servers ignore `clientId`; installing a new native build alone does **not** provide deduplication.
4. Run `node --experimental-transform-types --test tests/server/*.test.mjs` with Node 22.22.3. Tests use isolated temporary SQLite databases and the checked-in Prisma runtime; no production data or service is contacted.

The Kotlin/Swift main text composers now persist the optimistic message and retry operation before network I/O. Local acknowledgement updates are transactional and retire only the matching operation, never all messages with matching text. Swift retry envelopes preserve quote, topic and anonymity metadata. Existing ancillary paths still need process-death coverage; media upload itself is not made idempotent by a message-operation key.

A durable receipt prevents repeating downstream side effects after replay, but does not guarantee side effects run if the server crashes immediately after commit. Exactly-once push/bot/XP work would require a separate transactional server event outbox. No such guarantee is claimed here.

## Scheduled-send creation receipts (2026-10-24)

Updated Android and iOS clients create through `POST /api/conversations/{id}/scheduled/create-v2` with a durable `clientId`. There is no fallback to the legacy endpoint. The response must include the same `clientId`, Boolean `replayed`, `scheduleVersion: 1`, requested viewer/room, and the confirmed pending item. Local retry identity is retained until caller acceptance; uncertain failures do not allocate a new ID for the same instruction.

**Server prerequisite:** back up the database and apply `prisma/patches/20261024_scheduled_creation_operations.sql` before deploying this route and releasing clients. It is additive/rerunnable. A missing table fails closed with 503 and creates nothing. The scheduled row and receipt are transactional. Keep receipt rows independent of scheduled-row/conversation cleanup: old retries must not recreate cancelled, sent or deleted schedules. Terminal requests receive 410 `schedule_finished`; clients retain their identity rather than automatically resubmitting a fresh operation.

This does not change legacy no-ID creation, create an offline scheduler, or cancel a previous uncertain schedule when a user changes its text/time. Those changed instructions are distinct; review the scheduled list before submitting a replacement. Coordinated rollout is required; no deployment is implied by source publication.

### Story audience/deletion rollout (2026-10-24)

Story feed/create/viewer read/view mark/delete now require the existing bearer credential to own `requesterId`; all personalized responses are private/no-store. Deploy compatible native clients before enforcing this server revision: the repaired Kotlin DELETE attaches its bearer token, and Swift DELETE uses the route's encoded `requesterId` query rather than only a body field. Both require `{ok:true}` rather than treating an arbitrary 2xx body as deletion confirmation. Missing-token legacy/web requests fail closed; no new account/sign-in/recovery flow or schema migration is introduced. Story creation still lacks durable lost-ACK deduplication. These changes have not been deployed.

## Upload durability boundary

The upload endpoint must not return a file receipt until `UploadedFile` has accepted the bytes. Both native outboxes treat that receipt as permission to replace their raw upload payload with the stored path. Ensure the existing UploadedFile table and write path are available before rolling out this server revision: missing/unavailable durable storage now returns 503/Retry-After instead of an ephemeral-only success. The uploads directory is only an atomically published optional cache; its loss/failure must still allow DB-backed reads. Current JSON/response fields and native credential formats are unchanged.

New upload/byte responses use private/no-store headers. This does **not** authenticate previously public media URLs or revoke browser/native copies already cached. Media audience authorization/provenance and lost-upload-ack deduplication remain separate open work. No deployment is implied by source delivery.

## Profile mutations: owner credentials and managed photo paths

PATCH `/api/users/[id]` now requires an existing bearer credential accepted for that same user. Headerless profile writers receive 401 and another user's credential receives 403; GET public-profile behavior is unchanged. Coordinate authenticated-client rollout before enabling this endpoint revision for clients that still write profiles without credentials. No deployment is implied here, and no credentials need to be supplied in chat.

Native upload receipts are filenames; profile avatar/cover PATCH values must be `/api/uploads/<filename>` (or an explicit empty/null clear). The iOS editor now normalizes these receipts, as Android already does. Empty bio strings now intentionally clear the bio; wrong-type values are errors rather than implicit clears. Handle uniqueness is ultimately enforced by the database; racing claims return 409, not a success inferred from an earlier availability check.

Successful media metadata updates do not establish ownership of upload URLs, revoke cached media, or make concurrent profile edits revision-safe. Old iOS bio/status cache values without owner provenance are ignored; the editor reloads the current owner's server profile rather than adopting another account's cached fields.

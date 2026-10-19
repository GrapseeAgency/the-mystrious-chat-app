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

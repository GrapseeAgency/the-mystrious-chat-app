# Backend readiness for paired native read acceptance

**Audience:** the tester and the operator of an authorized, isolated test environment.

You do not need to guess whether a server is ready. Ask the operator to complete the receipt at the bottom before running [the nine device cases](NATIVE-READ-DEVICE-ACCEPTANCE.md). This document does not authorize deployment, migrations, production access, or account changes. Nothing has been deployed by this workstream.

## 1. Identify the candidate and environment

- Nominate an isolated test backend with synthetic accounts/messages. Record its environment label and deployed source revision from the deployment manifest—not merely the branch name or the app's version label.
- The native candidate `02976a6` completed its Android build/API 30/API 34 workflow and iOS archive/build-test workflow successfully. Backend contract and web checks also passed. These are hosted results, **not physical-device acceptance** or proof that earlier intermittent failures cannot recur.
- Candidate workflows: [Android](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40511992959), [iOS](https://github.com/GrapseeAgency/the-mystrious-chat-app/actions/runs/40511992969). Use the team's normal installation/signing process; an unsigned iOS archive is not an installable signed release.
- Deploying an older `main` backend underneath this candidate is not a matching setup. The operator must identify a backend revision containing the same read contracts, schema and privacy behavior. The candidate revision is the simplest source reference; equivalent deployments need a recorded comparison.
- Check that both apps actually target this environment for HTTP and realtime. A locally running backend or a successful build does not establish that the installed phones use it.

**If the environment or deployed revision is unknown: STOP / BLOCKED.** Do not create production test data or change production configuration to resolve uncertainty.

## 2. Ask for migration evidence

The operator must verify these repository patches, in this order:

1. `prisma/patches/20261026_read_intent_state.sql`
2. `prisma/patches/20261026_exact_read_observations.sql`
3. `prisma/patches/20261026_native_read_push.sql`

If anything is missing, preparation requires a separately authorized change: tested backup/recovery, the exact patches, generated Prisma client, matching backend build and deployment. The second patch already contains `BEGIN IMMEDIATE` / `COMMIT`; it must execute as one transaction on one SQLite connection, not as independently committed statements. Preserve its `exact-v1` marker. Never delete that marker or rerun a hand-written timestamp backfill to make counts look correct.

These are SQL patch files, not an assertion that `prisma migrate deploy` discovers them. Do not use `db:push --accept-data-loss`, `db:reset`, or a destructive schema reset as a shortcut.

### Optional read-only schema inspection

On the authorized test database, an operator with the SQLite CLI can run the following after setting `TEST_DB` to the **existing test SQLite file**. Do not paste database paths, credentials or database contents into chat. `-readonly` prevents this inspection from creating or modifying the database.

```sh
[ -n "${TEST_DB:-}" ] && [ -f "$TEST_DB" ] || { echo "Set TEST_DB to an existing authorized test database" >&2; exit 1; }
sqlite3 -readonly "$TEST_DB" <<'SQL'
.bail on
SELECT name FROM sqlite_master
WHERE type = 'table' AND name IN (
  'ReadIntentState', 'MessageReadObservation',
  'ReadLedgerMigration', 'PushReadStateCapability'
) ORDER BY name;
PRAGMA table_info('ReadIntentState');
PRAGMA table_info('MessageReadObservation');
PRAGMA table_info('PushReadStateCapability');
PRAGMA index_list('MessageReadObservation');
PRAGMA foreign_key_list('ReadIntentState');
PRAGMA foreign_key_list('MessageReadObservation');
PRAGMA foreign_key_list('PushReadStateCapability');
SELECT id FROM ReadLedgerMigration WHERE id = 'exact-v1';
SQL
```

Expected evidence:

- All four tables exist, and the migration marker query returns `exact-v1`.
- `ReadIntentState`: membership `participantId` primary key and non-null `revision`.
- `MessageReadObservation`: composite primary key `(participantId, messageId)`, `observedAt`, `source`, and the message-ID index. Membership/message foreign keys cascade deletion/update as specified in the patch.
- `PushReadStateCapability`: `tokenId` primary key, `version`, and cascading `PushToken` foreign key.
- Compare the actual schema against the patch files; a table name alone is insufficient.

Do not insert a marker to conceal a missing backfill. Schema inspection establishes prerequisites only; it cannot prove that the running service uses this database or that a proxy serves the correct backend.

## 3. Verify the running contracts with synthetic data

Use the operator's existing approved authenticated API client. Never send tokens, cookies, authorization headers or real message contents in an acceptance report. Authentication must remain enabled.

For reader R in an isolated test room, verify:

| Operation | Expected contract |
|---|---|
| `GET /api/conversations/{room}/read-intent?userId={R}` | `readIntentVersion: 2`, exact room/user binding, non-empty opaque `readIntent`; private/no-store response. This prepares authority and may initialize its row, but does not itself mark messages read. |
| `GET /api/conversations/{room}/read-state?userId={R}` | `readStateVersion: 1`, matching room/user and canonical unread/manual state. |
| Same read-state GET with either `threadId` or `topicId` | Version 1 and the exact selected scope; never supply both. Use real synthetic IDs in this room. |
| Same read-state GET with `messageId` alone | `notificationStateVersion: 1`, the same message binding and boolean `messageUnread`. Unknown/network errors are not an unread=false result. |
| A deliberate visible-read POST in the synthetic smoke room | `readReceiptVersion: 3` and exact user/room/original-intent/mode/message identity echoes. This **changes test data**; do it only as an authorized smoke action, outside the untouched acceptance controls. |
| A deliberately exercised manual reminder action | `unreadReceiptVersion: 2`, original intent and exact owner/room binding. |

Also verify that stale explicit intent is refused rather than automatically renewed. Validated permanent visible-read refusals can carry `readRetirement.version: 1` bound to user, room, message and original intent. A generic authentication/proxy 403/404 is not that contract and must not delete a native journal entry. Use controlled test fixtures for refusal injection; do not remove real users/messages.

A 200 response from an old route, a health page, a legacy timestamp receipt, or a successful login is not sufficient evidence of these contracts. The operator should investigate missing/wrong envelopes rather than disabling ownership checks.

## 4. Verify notification and fixture prerequisites

- The matching apps register native push capability using `readStateHints: 1`; the server associates version 1 capability with those registered tokens. Check using the operator's private tools without copying tokens into reports.
- Confirm the test environment's existing FCM/APNs/realtime configuration can reach the installed candidates. Record notification permission, foreground/background/force-quit state, battery restrictions and launcher behavior. Do not request new signing keys or provider credentials in chat.
- Reader R needs simultaneous authorized sessions on Android and iPhone. Sender S is a different account. Keep the second R session outside the observed room unless the case deliberately uses it to read.
- An authorized fixture is needed to create distinct IDs with the exact same canonical timestamp. An authorized fault proxy/harness is needed for held responses and bound/unbound refusal cases. Without those, label the affected subcases BLOCKED; do not simulate them by changing phone clocks or editing production data.
- Do not interpret the OS declining a background wake, or a launcher lacking numeric badges, as a successful background-convergence test.

## 5. Return this readiness receipt

```text
Environment label (no secrets):
Operator / date:
Deployed backend revision and generated-client build:
Android candidate revision / workflow artifact:
iOS candidate revision / signed test build provenance:
Both apps target this HTTP + realtime environment: YES / NO / UNKNOWN
All three patches verified against this database: YES / NO / UNKNOWN
exact-v1 marker and expected schema verified: YES / NO / UNKNOWN
Running contract versions / identity binding verified: YES / NO / UNKNOWN
Native push registration + delivery verified: YES / NO / UNKNOWN
R Android + R iPhone + separate S authorized: YES / NO / UNKNOWN
Equal-timestamp fixture available: YES / NO
Fault/response-ordering fixture available: YES / NO
Readiness: READY / BLOCKED
Blocked prerequisites / responsible operator:
```

Only after readiness is established should the tester execute the nine-case checklist in both platform directions and return its PASS/FAIL/BLOCKED results. READY here means the environment is suitable for testing—not that the app passed, or that merging/deploying to production is authorized.

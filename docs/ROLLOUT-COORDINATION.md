# Rollout Coordination — backend / relay / client deploy order

Section 16 item 5 (OPEN — coordinated backend/relay/client rollout) and item 6
(production migration/deployment proof) record what must ship as ONE unit and
what this repository can and cannot prove.

## The units that deploy together

The relay (`mini-services/pulse-socket`) and the Next backend are not
independently deployable surfaces. The contract points (drift-guarded by
`tests/server/section16-rollout-drift.test.mjs`):

| Contract point | Direction | Failure if split |
| --- | --- | --- |
| `POST /api/internal/verify` + `x-pulse-key` | relay → Next | presented socket tokens unverifiable → every native join 401-disconnects |
| `GET /api/internal/privacy` + `x-pulse-key` (30s TTL, fail-closed) | relay → Next | relay must assume all-private; message previews degrade |
| `GET /api/internal/call-access` — `callAccessVersion: 1` | relay → Next | version mismatch → relay rejects every call-access receipt (line-guarded `callAccessVersion !== 1`) |
| `POST /api/maintenance/dispatch` (20s ticker) + `POST /api/maintenance/upload-sweep` (hourly) + `x-pulse-key` | relay → Next | scheduled/outbox/sweep pipelines stall |
| `POST /api/conversations/[id]/calls/ring` — `ringReceiptVersion: 1` | relay ← internal probe | native ring receipts unverifiable |
| `POST :3003/notify` + `x-pulse-key` | Next → relay | no `message:*` fanout events at all |
| gateway `XTransformPort` + `/socket.io/*` → :3003, everything else → :3000 | Caddy | clients see the wrong service |

## Deploy order (the only safe sequence)

1. **Database first.** `bun run db:push` against the production SQLite BEFORE
   any code: every route reads columns the client receipts write. Section 13
   tables (`SettingsOperation`, `SettingsRevision`, `SettingsFieldStamp`),
   Section 14 legacy-recovery tables, Section 12 integration receipts.
2. **Next backend.** New routes and versioned receipts must be live before the
   relay learns about them (the relay pulls from Next, never the reverse).
3. **Relay.** One instance (single-node invariant: registries are
   process-local, `NODE_ID` stamped at boot). Restart order after a Next
   deploy does not matter — the relay re-probes Next on demand.
4. **Web.** The web shell (this repo's `src/`) must deploy together with the
   backend credential adoption (R67-era): the hardened routes reject
   headerless calls, and `apiJson` now attaches the session bearer. A web
   deploy older than the backend keeps working (headerless window at the
   proxy); a backend older than the web deploy leaves the web with 401s on
   hardened routes.
5. **Native apps.** Any version; both attach Bearer on every request family
   and tolerate older backends through the proxy's optional-verify window.

## Configuration matrix (environment)

| Variable | Consumed by | Notes |
| --- | --- | --- |
| `CRON_SECRET` | proxy-internal verify, relay `x-pulse-key`, maintenance routes | shared secret; fail-closed, no default (relay warns at boot) |
| `PULSE_ORIGIN` | relay → Next base | default `http://localhost:3000`; production points at the real Next origin |
| `PULSE_SOCKET_PORT` | relay listen port | default 3003; the gateway and `notifySocket` must know the same value |
| `DATABASE_URL` | Next Prisma client | SQLite file; back it up before every `db push` |

## Migration / deployment proof — what is proven, what is not

**Proven in this repo (CI, every push):** the full server suite (692 tests)
runs against a fresh DMMF-built SQLite schema and the historical SQL patches,
including the real upgrade path acceptance on native stores
(`PulseStoreUpgradeAcceptanceTests` — every historical GRDB version;
`RoomMigrationTest` — raw-SQL v3→v10 chain) and the send/upload/settings
receipt tables replayed from their patch SQL.

**NOT proven here, honestly:** production schema compatibility and a real
deployment. No workflow deploys the backend, runs `db push`, or touches
production (the release job publishes an Android artifact only and states it
deploys nothing). Closing item 6 requires one production rehearsal:
`bun run db:push` against a RESTORED production backup (not the live file),
then a boot of the backend against that restored DB and the server suite's
route smoke against it. That rehearsal is out-of-band for this repository's
CI and remains the documented gate.

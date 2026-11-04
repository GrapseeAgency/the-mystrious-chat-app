# Pulse self-host runbook - run the full backend on your own host so the
# Android app (GitHub Releases APK) can be audited on a real device.

Two paths. **Path A is the executed-and-verified one** (it is the exact stack
this repository's development sandbox runs and verifies live: Next.js + the
socket relay + the Caddy single-origin gateway). Path B (Docker) packages the
same topology but is declarative-only - see HONEST LIMITS.

After either path, the phone steps are identical:

1. Install the latest `Pulse-<version>.apk` from
   [Releases](https://github.com/GrapseeAgency/the-mystrious-chat-app/releases).
2. Open the app. In onboarding, set **Server** to `http://<host>:81`
   (on the same machine: `http://<lan-ip>:81`).
3. The connect gate live-probes `GET <base>/api/users`; a 200 unlocks
   **Continue as demo** and account creation.

---

## Path A - bare metal (verified topology)

Requirements: a Linux host with git, bun, node, and caddy.

```bash
git clone https://github.com/GrapseeAgency/the-mystrious-chat-app.git
cd the-mystrious-chat-app
bun install
echo "DATABASE_URL=file:$(pwd)/db/custom.db" > .env
bun run db:push                 # prisma schema -> db/custom.db (SQLite)

# web (dev server - the verified configuration)
bun run dev                     # :3000

# relay (separate shell)
cd mini-services/pulse-socket && bun install && bun run dev   # :3003

# gateway (separate shell, from the repo root)
caddy run                       # :81 per the root Caddyfile
```

Single-origin contract (pinned by `tests/server/section16-rollout-drift.test.mjs`):
`/socket.io/*` rides the relay (:3003), everything else rides the web app
(:3000). The phone only ever needs the one `:81` base URL.

The production alternative (`bun run build` + `bun run start`, Next standalone)
is provided by `package.json`; it is not exercised in CI or the sandbox.

## Path B - Docker compose

```bash
cd deploy
PULSE_CRON_SECRET=<pick-a-secret> docker compose up --build -d
```

- one image, two roles (`web` / `relay`), Caddy gateway on `:81`
- first boot seeds the data volume from the tracked demo database
  (`db/custom.db` - the 84 seeded demo conversations), preserving any
  existing volume across upgrades
- `PULSE_RELAY_BASE`/`PULSE_ORIGIN` retarget the cross-service hops that the
  single-host defaults (`localhost:3003` / `localhost:3000`) pin

## Environment matrix

| Variable | Where | Default | Meaning |
| --- | --- | --- | --- |
| `DATABASE_URL` | web | `file:.../db/custom.db` | SQLite file path |
| `PULSE_RELAY_BASE` | web | `http://localhost:3003` | Next -> relay hops (notify/typing/gcall/online) |
| `PULSE_ORIGIN` | relay | `http://localhost:3000` | relay -> Next internal base |
| `PULSE_SOCKET_PORT` | relay | `3003` | relay listen port |
| `CRON_SECRET` | both | (unset = internal calls skipped) | shared internal service key |

## HONEST LIMITS

- The compose stack has never been executed (no Docker daemon in the
  development sandbox, no CI workflow builds containers). It mirrors the
  verified topology 1:1, but treat the first `docker compose up` as its
  first real test.
- Path A's `bun run dev` configuration is the one verified end-to-end
  (692/692 server tests against these services; live browser verification of
  sign-in, conversation list, and room feed through the gateway).
- `http://<lan-ip>:81` works from the phone because the app permits cleartext;
  for public-internet hosting, terminate TLS in front of Caddy and use `https`.
- iOS has no sandbox-buildable IPA: build from `apps/ios` in Xcode on a Mac.
- The demo database ships real demo data; for a fresh start, delete
  `db/custom.db` (or the `pulse-data` volume) and run `bun run db:push`.

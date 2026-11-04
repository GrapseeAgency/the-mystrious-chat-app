#!/bin/sh
# Pulse role entrypoint - one image, two roles.
#   docker run image            -> web (Next.js standalone)
#   docker run image relay      -> socket relay
set -eu

ROLE="${1:-web}"

if [ "$ROLE" = "web" ]; then
  # First boot: seed the data volume from the tracked demo database unless a
  # database already exists (preserves user data across restarts/upgrades).
  if [ ! -f /data/custom.db ]; then
    mkdir -p /data
    echo "[pulse-web] seeding /data/custom.db from the tracked demo database"
    cp /seed/custom.db /data/custom.db
  fi
  echo "[pulse-web] serving on :3000"
  exec bun /pulse/web/server.js
fi

if [ "$ROLE" = "relay" ]; then
  cd /pulse/relay
  echo "[pulse-relay] serving socket.io on :${PULSE_SOCKET_PORT:-3003}"
  exec bun index.ts
fi

echo "unknown role: $ROLE (expected 'web' or 'relay')" >&2
exit 2

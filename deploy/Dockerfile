# Pulse self-host image - one image, two roles (arg decides): `web` (Next.js
# standalone) or `relay` (socket.io relay). Built multi-stage on oven/bun.
#
# HONEST LIMITS (see deploy/README.md): this container build is NOT exercised
# by any CI workflow and could not be executed in the development sandbox
# (no Docker daemon there). The proven path is the bare-metal runbook, which
# runs the exact stack this sandbox verified live (bun dev web + relay +
# Caddy gateway on :81). The compose stack below packages that same stack
# declaratively.

FROM oven/bun:1 AS build
WORKDIR /app

# Web dependencies + prisma client
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

# Full source (respecting .dockerignore)
COPY . .
RUN bun run db:generate

# Relay dependencies (its own lockfile)
WORKDIR /app/mini-services/pulse-socket
RUN bun install --frozen-lockfile

# Production web build (next build, standalone output - see package.json build)
WORKDIR /app
RUN bun run build

# ---- runtime ----
FROM oven/bun:1 AS runtime
WORKDIR /pulse

# Web standalone (already carries .next/static + public via the build script)
COPY --from=build /app/.next/standalone ./web
# Relay service with its node_modules
COPY --from=build /app/mini-services/pulse-socket ./relay
# Prisma schema for a fresh `db push` if the operator wants an empty start
COPY --from=build /app/prisma ./prisma
# The tracked demo database ships as the initial data volume seed
# (84 seeded demo conversations - same data the sandbox verified live).
COPY db/custom.db /seed/custom.db

# Role entrypoint: `web` (default) or `relay`
COPY deploy/entrypoint.sh /usr/local/bin/pulse-entrypoint
RUN chmod +x /usr/local/bin/pulse-entrypoint

ENV NODE_ENV=production \
    PULSE_SOCKET_PORT=3003 \
    DATABASE_URL=file:/data/custom.db

EXPOSE 3000 3003
ENTRYPOINT ["/usr/local/bin/pulse-entrypoint"]

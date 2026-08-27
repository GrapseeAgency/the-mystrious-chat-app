// ─────────────────────────────────────────────────────────────
// Pulse Keeper — resilience supervisor.
// The sandbox reaps the dev server between tool-call boundaries;
// this long-lived mini service re-spawns it whenever the health
// probe fails, keeping the preview usable.
// ─────────────────────────────────────────────────────────────
import { createServer } from 'node:http'

const APP_PORT = 3000
const HEALTH_PORT = 3004
const CHECK_INTERVAL_MS = 5000
/** require this many consecutive failed probes before respawning (avoids false positives under load) */
const DOWN_STREAK_THRESHOLD = 3
/** after a failed spawn attempt, wait before trying again (port-in-use storms) */
const SPAWN_COOLDOWN_MS = 15_000

interface ChildHandle {
  pid: number | undefined
  startedAt: number
}

let lastChild: ChildHandle | null = null
let restarting = false
let restartCount = 0
let downStreak = 0
let cooldownUntil = 0

async function isAppUp(): Promise<boolean> {
  try {
    const res = await fetch(`http://localhost:${APP_PORT}/api/users`, {
      signal: AbortSignal.timeout(3500),
    })
    return res.status < 500
  } catch {
    return false
  }
}

function spawnApp(): void {
  restarting = true
  try {
    const proc = Bun.spawn({
      cmd: ['bun', '--bun', 'node_modules/next/dist/bin/next', 'dev', '-p', String(APP_PORT)],
      cwd: '/home/z/my-project',
      stdin: 'ignore',
      stdout: 'ignore',
      stderr: 'ignore',
    })
    lastChild = { pid: proc.pid, startedAt: Date.now() }
    restartCount += 1
    console.log(`[keeper] spawned next dev (pid=${proc.pid}, restarts=${restartCount})`)
    void proc.exited.then((code) => {
      console.log(`[keeper] child pid=${proc.pid} exited code=${code}`)
      // Fast exit right after spawn almost always means port-in-use:
      // back off so we never storm a perfectly healthy server.
      if (Date.now() - (lastChild?.startedAt ?? Date.now()) < 8_000) {
        cooldownUntil = Date.now() + SPAWN_COOLDOWN_MS
      }
    })
  } catch (error) {
    console.error('[keeper] spawn failed:', error)
  } finally {
    setTimeout(() => {
      restarting = false
    }, 4000)
  }
}

async function tick(): Promise<void> {
  if (restarting) return
  if (Date.now() < cooldownUntil) return
  const up = await isAppUp()
  if (!up) {
    downStreak += 1
    if (downStreak >= DOWN_STREAK_THRESHOLD) {
      console.log(`[keeper] app DOWN on :${APP_PORT} (${downStreak} probes) → respawning`)
      downStreak = 0
      spawnApp()
    }
  } else {
    downStreak = 0
  }
}

void tick()
setInterval(() => void tick(), CHECK_INTERVAL_MS)

// tiny health endpoint for observability
const httpServer = createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(
    JSON.stringify({
      ok: true,
      service: 'pulse-keeper',
      appPort: APP_PORT,
      lastChildPid: lastChild?.pid ?? null,
      childUptimeSec: lastChild ? Math.round((Date.now() - lastChild.startedAt) / 1000) : null,
      restartCount,
      downStreak,
      cooldownSec: Math.max(0, Math.round((cooldownUntil - Date.now()) / 1000)),
      uptimeSec: Math.round(process.uptime()),
    }),
  )
})
httpServer.listen(HEALTH_PORT, () => {
  console.log(`[keeper] health on :${HEALTH_PORT}`)
})

process.on('SIGTERM', () => {
  httpServer.close(() => process.exit(0))
})
process.on('SIGINT', () => {
  httpServer.close(() => process.exit(0))
})

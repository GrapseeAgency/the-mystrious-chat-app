// ─────────────────────────────────────────────────────────────
// /api/internal/privacy — R47 privacy-flag snapshot for the
// pulse-socket mini service. The socket relay intentionally has NO
// database access, so it polls THIS endpoint (shared-key guarded,
// same pattern as /api/maintenance/dispatch) before relaying
// typing indicators and building presence snapshots.
//
// Contract:
//   GET ?ids=a,b,c   [x-pulse-key header required]
//     → 200 { flags: { [id]: { typingVisible, presenceVisible } } }
//
// Semantics: unknown / missing ids are simply absent from the map —
// consumers fail OPEN (treat as visible) so an API hiccup never
// blinds the whole mesh. presenceVisible is derived from the
// Telegram-coupled "Last seen & online" toggle (lastSeenVisible);
// typingVisible is its own R47 key.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mergePrefs } from '@/lib/prefs-defaults'

export const dynamic = 'force-dynamic'

function authorized(req: Request): boolean {
  const expected = process.env.CRON_SECRET ?? 'pulse-dispatch-key'
  return req.headers.get('x-pulse-key') === expected
}

export async function GET(req: Request) {
  if (!authorized(req)) {
    return NextResponse.json({ error: 'Unauthorized.' }, { status: 401 })
  }

  const idsParam = new URL(req.url).searchParams.get('ids') ?? ''
  const ids = Array.from(
    new Set(
      idsParam
        .split(',')
        .map((v) => v.trim())
        .filter((v) => v.length > 0),
    ),
  )
  if (ids.length === 0) {
    return NextResponse.json({ flags: {} })
  }
  if (ids.length > 500) {
    return NextResponse.json({ error: 'Too many ids (max 500).' }, { status: 400 })
  }

  const users = await db.user.findMany({
    where: { id: { in: ids } },
    select: { id: true, preferences: true },
  })

  const flags: Record<string, { typingVisible: boolean; presenceVisible: boolean }> = {}
  for (const user of users) {
    const prefs = mergePrefs(safeParse(user.preferences))
    flags[user.id] = {
      typingVisible: prefs.typingVisible,
      // Telegram parity: one toggle governs last seen AND online presence.
      presenceVisible: prefs.lastSeenVisible,
    }
  }

  return NextResponse.json({ flags })
}

function safeParse(raw: string | null): unknown {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

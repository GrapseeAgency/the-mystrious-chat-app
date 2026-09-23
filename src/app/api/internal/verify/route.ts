// ─────────────────────────────────────────────────────────────
// /api/internal/verify — session-token check for the pulse-socket
// mini service (Wave 8, spec §3.11 A-2). The socket relay has NO
// database access, so it calls THIS endpoint (shared-key guarded,
// same pattern as /api/internal/privacy) when a join carries a
// token. Joins WITHOUT a token stay accepted during the migration
// window (optional-verify so web keeps working); a PRESENT token
// must match the stored hash or the join is refused.
//
// Contract:
//   GET ?userId=<id>&token=<raw>   [x-pulse-key header required]
//     → 200 { valid: boolean }
//
// Response is a boolean only — never reveals which part failed.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { hashSessionToken } from '@/lib/session-token'

export const dynamic = 'force-dynamic'

function authorized(req: Request): boolean {
  // Fail-closed: no default secret. CRON_SECRET must be set in the environment;
  // an unset/empty secret refuses every request (audit finding S5).
  const expected = process.env.CRON_SECRET
  if (!expected) return false
  return req.headers.get('x-pulse-key') === expected
}

export async function GET(req: Request) {
  if (!authorized(req)) {
    return NextResponse.json({ error: 'Unauthorized.' }, { status: 401 })
  }

  const url = new URL(req.url)
  const userId = (url.searchParams.get('userId') || '').trim()
  const token = (url.searchParams.get('token') || '').trim()
  if (!userId || !token) {
    return NextResponse.json({ valid: false })
  }

  const user = await db.user.findUnique({
    where: { id: userId },
    select: { sessionTokenHash: true },
  })
  // No stored hash (pre-token identity) → token cannot match → invalid.
  const valid = Boolean(user?.sessionTokenHash) && user!.sessionTokenHash === hashSessionToken(token)
  return NextResponse.json({ valid })
}

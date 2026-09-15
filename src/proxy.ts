// ─────────────────────────────────────────────────────────────
// Session-token optional-verify proxy — Wave 8 (spec §3.11
// A-2: "server verifies (middleware, optional-verify during
// migration so web keeps working)").
//
// Semantics (binding):
//   • NO Authorization header  → pass through untouched. Web and
//     pre-token clients keep working (the migration window).
//   • Authorization: Bearer <token> → the token must hash-match a
//     stored User.sessionTokenHash, else 401. This is what makes a
//     native token a real credential: stale/rotated/forged tokens
//     fail closed for the clients that present them.
//   • /api/internal/* stays excluded — those routes are already
//     guarded by the shared x-pulse-key, and the socket relay calls
//     /api/internal/verify WITHOUT a user Bearer by design.
// ─────────────────────────────────────────────────────────────
import { NextResponse, type NextRequest } from 'next/server'
import { db } from '@/lib/db'
import { bearerToken, hashSessionToken } from '@/lib/session-token'

export const config = {
  matcher: ['/api/:path*'],
}

export default async function proxy(req: NextRequest) {
  const path = req.nextUrl.pathname
  if (path.startsWith('/api/internal')) {
    return NextResponse.next()
  }

  const token = bearerToken(req as unknown as Request)
  if (!token) {
    return NextResponse.next()
  }

  const hash = hashSessionToken(token)
  const user = await db.user.findFirst({
    where: { sessionTokenHash: hash },
    select: { id: true },
  })
  if (!user) {
    return NextResponse.json({ error: 'Session token is invalid or has been rotated. Log in again.' }, { status: 401 })
  }
  return NextResponse.next()
}

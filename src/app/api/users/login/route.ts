// /api/users/login - reclaim an existing identity with a session
// token (Wave 8, spec §3.11 A-1: "POST /api/users/login (new) for
// reclaim returns same"). Pulse identities are name-keyed and
// self-declared (honest state per §3.11): this route hands the
// caller a fresh credential for a name that exists, rotating any
// previous token (last login wins - reclaim is a login).
//
// Contract:
//   POST { name } → 200 { user: AppUser, token }
//                  | 400 { error }            (name missing)
//                  | 404 { error }            (unknown name - honest)
//   The raw `token` (32 B hex) is shown exactly once; only its
//   sha256 is stored (User.sessionTokenHash).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapUser, safeJson, strField } from '@/lib/serializers'
import { generateSessionToken, hashSessionToken, pushLegacyTokenHash } from '@/lib/session-token'

export const dynamic = 'force-dynamic'

export async function POST(req: Request) {
  const body = await safeJson(req)
  const name = strField(body.name)
  if (!name) {
    return NextResponse.json({ error: 'Name is required.' }, { status: 400 })
  }

  // Same folded-match semantics as GET /api/users?name= (SQLite has no
  // insensitive filter in Prisma - scan same-letter candidates).
  const first = name.charAt(0).toLowerCase()
  const candidates = await db.user.findMany({
    where: { OR: [{ name: { startsWith: first } }, { name: { startsWith: first.toUpperCase() } }] },
  })
  const user = candidates.find((u) => u.name.toLowerCase() === name.toLowerCase())
  if (!user) {
    return NextResponse.json({ error: 'No identity with that name on this Pulse.' }, { status: 404 })
  }

  // Rotate: the newest reclaim becomes the primary credential. R52 - the
  // outgoing hash moves to the grace list (legacyTokenHashes, cap 8)
  // instead of being destroyed, so the phone that logged in this morning
  // is NOT 401-torn-down the moment another device reclaims the same
  // identity. Last login wins the primary; earlier sessions keep working.
  const token = generateSessionToken()
  await db.user.update({
    where: { id: user.id },
    data: {
      sessionTokenHash: hashSessionToken(token),
      legacyTokenHashes: pushLegacyTokenHash(user.sessionTokenHash, user.legacyTokenHashes),
      lastSeenAt: new Date(),
    },
  })

  return NextResponse.json({ user: mapUser(user), token })
}

// ─────────────────────────────────────────────────────────────
// /api/users — create a user, list all users (contacts)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapUser, normalizeColor, safeJson, strField, USER_NAME_MAX } from '@/lib/serializers'
import { ensurePulseBot } from '@/lib/ai-bot'

export const dynamic = 'force-dynamic'

/**
 * POST /api/users { name, color? } → 201 { user: AppUser }
 * Names are unique case-insensitively (Pulse identities are name-keyed —
 * contacts/search/list rows would otherwise be ambiguous).
 * → 409 when the exact-insensitive name is already taken.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const name = strField(body.name)
  if (!name || name.length > USER_NAME_MAX) {
    return NextResponse.json(
      { error: `Name is required and must be 1–${USER_NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  // SQLite has no insensitive filter in Prisma — scan same-letter candidates and
  // compare folded in JS. The candidate set stays tiny at chat-app scale.
  const first = name.trim().charAt(0).toLowerCase()
  const candidates = await db.user.findMany({
    where: { OR: [{ name: { startsWith: first } }, { name: { startsWith: first.toUpperCase() } }] },
    select: { id: true, name: true },
  })
  if (candidates.some((u) => u.name.toLowerCase() === name.toLowerCase())) {
    return NextResponse.json(
      { error: 'That name is taken on this Pulse. Try another one.' },
      { status: 409 },
    )
  }

  const user = await db.user.create({
    data: { name, color: normalizeColor(body.color) },
  })

  return NextResponse.json({ user: mapUser(user) }, { status: 201 })
}

/**
 * GET /api/users                     → { users: AppUser[] } sorted by name asc
 * GET /api/users?name=Alice%20Chen   → { user: AppUser } | 404
 * Case-insensitive exact match lookup powering the ?login= deep-link and the
 * onboarding "that's you? log in" affordance (Pulse identities are name-keyed).
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const name = url.searchParams.get('name')?.trim() ?? ''

  if (name.length > 0) {
    if (name.length > USER_NAME_MAX) {
      return NextResponse.json(
        { error: `Name must be 1–${USER_NAME_MAX} characters.` },
        { status: 400 },
      )
    }
    const first = name.charAt(0).toLowerCase()
    const candidates = await db.user.findMany({
      where: { OR: [{ name: { startsWith: first } }, { name: { startsWith: first.toUpperCase() } }] },
    })
    const user = candidates.find((u) => u.name.toLowerCase() === name.toLowerCase())
    if (!user) {
      return NextResponse.json({ error: 'No Pulse account with that name.' }, { status: 404 })
    }
    return NextResponse.json({ user: mapUser(user) })
  }

  // ensure the AI companion exists so it is addable like any user
  await ensurePulseBot()
  const users = await db.user.findMany({ orderBy: { name: 'asc' } })
  return NextResponse.json({ users: users.map(mapUser) })
}

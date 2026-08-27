// ─────────────────────────────────────────────────────────────
// /api/users — create a user, list all users (contacts)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapUser, normalizeColor, safeJson, strField, USER_NAME_MAX } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** POST /api/users { name, color? } → 201 { user: AppUser } */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const name = strField(body.name)
  if (!name || name.length > USER_NAME_MAX) {
    return NextResponse.json(
      { error: `Name is required and must be 1–${USER_NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  const user = await db.user.create({
    data: { name, color: normalizeColor(body.color) },
  })

  return NextResponse.json({ user: mapUser(user) }, { status: 201 })
}

/** GET /api/users → { users: AppUser[] } sorted by name asc */
export async function GET() {
  const users = await db.user.findMany({ orderBy: { name: 'asc' } })
  return NextResponse.json({ users: users.map(mapUser) })
}

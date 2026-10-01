// /api/push/register - device push-transport registry (web/android/ios).
//
// POST   { userId, platform: 'web'|'android'|'ios', token, endpoint? }
//        → upsert (token unique) so re-registrations rebind the user.
// DELETE { token } → remove (logout / subscription replaced).
//
// Web clients post the serialized PushSubscription JSON as `token` and the
// endpoint URL as `endpoint`; mobile clients post their FCM/APNs token.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const PLATFORMS = ['web', 'android', 'ios'] as const
const TOKEN_MAX = 4096 // web subscriptions are ~1-3 KB JSON

export async function POST(req: Request) {
  const body = (await safeJson(req)) as Record<string, unknown>
  const userId = strField(body.userId)
  const platform = strField(body.platform)
  const token = typeof body.token === 'string' ? body.token.trim() : ''
  const endpoint = typeof body.endpoint === 'string' ? body.endpoint.slice(0, 2048) : null

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!(PLATFORMS as readonly string[]).includes(platform)) {
    return NextResponse.json({ error: `platform must be one of: ${PLATFORMS.join(', ')}.` }, { status: 400 })
  }
  if (!token || token.length > TOKEN_MAX) {
    return NextResponse.json({ error: `token is required (≤${TOKEN_MAX} chars).` }, { status: 400 })
  }
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const row = await db.pushToken.upsert({
    where: { token },
    update: { userId, platform, endpoint },
    create: { userId, platform, token, endpoint },
  })
  return NextResponse.json({ ok: true, id: row.id })
}

export async function DELETE(req: Request) {
  const body = (await safeJson(req)) as Record<string, unknown>
  const token = typeof body.token === 'string' ? body.token.trim() : ''
  if (!token) {
    return NextResponse.json({ error: 'token is required.' }, { status: 400 })
  }
  try {
    await db.pushToken.delete({ where: { token } })
  } catch {
    // already gone - idempotent
  }
  return NextResponse.json({ ok: true })
}

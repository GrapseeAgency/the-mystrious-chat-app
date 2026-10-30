// /api/push/register - device push-transport registry (web/android/ios).
//
// POST   { userId, platform: 'web'|'android'|'ios', token, endpoint?,
//         readStateHints?, apnsEnvironment? }
//        → upsert (token unique) so re-registrations rebind the user. Every
//          rebind increments `revision` - the monotonic counter an in-flight
//          provider prune must also match, so a same-millisecond rebind can
//          never be erased by a stale UNREGISTERED response. The row also
//          records the gateway origin it was registered against (push payloads
//          echo it so native clients can drop foreign-deployment banners) and,
//          for iOS, the client-declared APNs environment.
// DELETE { token } → remove (logout / subscription replaced).
//
// Web clients post the serialized PushSubscription JSON as `token` and the
// endpoint URL as `endpoint`; mobile clients post their FCM/APNs token.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const PLATFORMS = ['web', 'android', 'ios'] as const
const TOKEN_MAX = 4096 // web subscriptions are ~1-3 KB JSON
const APNS_ENVIRONMENTS = ['sandbox', 'production'] as const

/** Gateway origin (scheme://host[:port]) this registration belongs to, read
 * from proxy/request headers. Null when no host header survives (tests). */
function requestOrigin(req: Request): string | null {
  const proto = (req.headers.get('x-forwarded-proto') ?? 'http').split(',')[0].trim() || 'http'
  const host = (req.headers.get('x-forwarded-host') ?? req.headers.get('host') ?? '').split(',')[0].trim()
  return host ? `${proto}://${host}` : null
}

export async function POST(req: Request) {
  const body = (await safeJson(req)) as Record<string, unknown>
  const userId = strField(body.userId)
  const platform = strField(body.platform)
  const token = typeof body.token === 'string' ? body.token.trim() : ''
  const endpoint = typeof body.endpoint === 'string' ? body.endpoint.slice(0, 2048) : null
  const apnsEnvironment = typeof body.apnsEnvironment === 'string' ? body.apnsEnvironment.trim() : ''

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!(PLATFORMS as readonly string[]).includes(platform)) {
    return NextResponse.json({ error: `platform must be one of: ${PLATFORMS.join(', ')}.` }, { status: 400 })
  }
  if (!token || token.length > TOKEN_MAX) {
    return NextResponse.json({ error: `token is required (≤${TOKEN_MAX} chars).` }, { status: 400 })
  }
  // Only iOS tokens carry an APNs environment; anything else (or an unknown
  // value) is rejected rather than silently misrouting provider sends.
  if (apnsEnvironment && (platform !== 'ios' || !(APNS_ENVIRONMENTS as readonly string[]).includes(apnsEnvironment))) {
    return NextResponse.json({ error: `apnsEnvironment must be one of: ${APNS_ENVIRONMENTS.join(', ')} (iOS only).` }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'push registration')
  if (denied) return denied
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  const origin = requestOrigin(req)

  const row = await db.$transaction(async tx => {
    const row = await tx.pushToken.upsert({
      where: { token },
      update: { userId, platform, endpoint, revision: { increment: 1 },
        ...(platform === 'ios' && apnsEnvironment ? { apnsEnvironment } : { apnsEnvironment: null }),
        ...(origin ? { origin } : {}) },
      create: { userId, platform, token, endpoint,
        ...(platform === 'ios' && apnsEnvironment ? { apnsEnvironment } : {}),
        ...(origin ? { origin } : {}) },
    })
    if (platform !== 'web' && body.readStateHints === 1) {
      await tx.pushReadStateCapability.upsert({ where: { tokenId: row.id },
        update: { version: 1 }, create: { tokenId: row.id, version: 1 } })
    } else {
      // Re-registration from an older app revokes the capability atomically.
      await tx.pushReadStateCapability.deleteMany({ where: { tokenId: row.id } })
    }
    return row
  })
  return NextResponse.json({ ok: true, id: row.id, revision: row.revision })
}

export async function DELETE(req: Request) {
  const body = (await safeJson(req)) as Record<string, unknown>
  const token = typeof body.token === 'string' ? body.token.trim() : ''
  if (!token) {
    return NextResponse.json({ error: 'token is required.' }, { status: 400 })
  }
  const row = await db.pushToken.findUnique({ where: { token } })
  if (row) {
    const denied = await requireActionOwner(req, row.userId, 'push removal')
    if (denied) return denied
    // The revision guard matches the same boundary a provider prune uses: a
    // rebind that landed during owner verification survives the removal.
    await db.pushToken.deleteMany({ where: { id: row.id, token, userId: row.userId, revision: row.revision, updatedAt: row.updatedAt } })
  }
  return NextResponse.json({ ok: true })
}

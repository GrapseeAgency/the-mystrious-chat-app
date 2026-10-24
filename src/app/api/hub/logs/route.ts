// Activity is an account-owned history, not a public dump of LogEvent.
// LogEvent also backs private room operations, including anonymous sends.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'
const ACTIVITY_KINDS = ['checkin', 'transfer', 'swap', 'redpacket', 'market', 'hand', 'system', 'message', 'tournament']

export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) return NextResponse.json({ error: 'userId is required for private activity.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'activity')
  if (denied) return denied
  const limitRaw = Number.parseInt(url.searchParams.get('limit') ?? '60', 10)
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 200) : 60
  const kind = url.searchParams.get('kind')?.trim() ?? ''
  if (kind && !ACTIVITY_KINDS.includes(kind)) return NextResponse.json({ error: 'Unknown activity category.' }, { status: 400 })

  const logs = await db.logEvent.findMany({
    where: { userId, kind: kind || { in: ACTIVITY_KINDS } },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    take: limit,
    include: { user: { select: { id: true, name: true, username: true, color: true } } },
  })
  return NextResponse.json({
    activityVersion: 1, userId,
    logs: logs.map(l => ({ id: l.id, kind: l.kind, message: l.message, meta: l.meta ?? null,
      createdAt: l.createdAt.toISOString(), user: l.user })),
  }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
}

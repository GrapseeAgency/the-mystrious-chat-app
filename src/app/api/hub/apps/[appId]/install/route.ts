import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { MATRIX } from '@/lib/hub-catalog'
import { safeJson } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ appId: string }>
}

function resolveApp(appId: string) {
  const n = Number(appId)
  if (!Number.isInteger(n)) return null
  return MATRIX.find((a) => a.n === n) ?? null
}

/**
 * GET /api/hub/apps/[appId]/install?userId=<id>
 * → 200 { installed, status, installedAt, installs, installers }
 * installers = up to 6 most recent connected members (real users).
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) {
    return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  }
  const url = new URL(req.url)
  const userId = (url.searchParams.get('userId') || '').trim()

  const rows = await db.appInstall.findMany({
    where: { appId: appId, status: 'connected' },
    orderBy: { installedAt: 'desc' },
    include: { user: { select: { id: true, name: true, username: true, color: true } } },
  })

  const mine = userId ? rows.find((r) => r.userId === userId) : undefined
  return NextResponse.json({
    installed: Boolean(mine),
    status: mine?.status ?? null,
    installedAt: mine?.installedAt.toISOString() ?? null,
    installs: rows.length,
    installers: rows.slice(0, 6).map((r) => ({
      id: r.user.id,
      name: r.user.name,
      username: r.user.username,
      color: r.user.color,
    })),
  })
}

/**
 * POST /api/hub/apps/[appId]/install  body { userId }
 * Connects the user to the app (idempotent upsert; re-connect revives).
 * → 200 { installed: true, installs }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) {
    return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  }
  const body = await safeJson(req)
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  await db.appInstall.upsert({
    where: { userId_appId: { userId, appId } },
    create: { userId, appId, status: 'connected' },
    update: { status: 'connected' },
  })
  const installs = await db.appInstall.count({ where: { appId, status: 'connected' } })
  return NextResponse.json({ installed: true, status: 'connected', installs })
}

/**
 * DELETE /api/hub/apps/[appId]/install  body { userId }
 * Disconnects the user from the app.
 * → 200 { installed: false, installs }
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) {
    return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  }
  const body = await safeJson(req)
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  await db.appInstall.deleteMany({ where: { userId, appId } })
  const installs = await db.appInstall.count({ where: { appId, status: 'connected' } })
  return NextResponse.json({ installed: false, status: null, installs })
}

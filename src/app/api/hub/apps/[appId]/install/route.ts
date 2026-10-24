import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { MATRIX } from '@/lib/hub-catalog'
import { safeJson } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { appInstallSnapshot, setAppInstalled, hubAppErrorResponse } from '@/lib/hub-app-operation'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ appId: string }> }
function resolveApp(appId: string) {
  // One catalog identity, not separate rooms/installations for 01, 1e0 or 1.0.
  if (!/^[1-9][0-9]*$/.test(appId)) return null
  return MATRIX.find(app => String(app.n) === appId) ?? null
}
const headers = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }
export async function GET(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  if (!resolveApp(appId)) return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  const userId = new URL(req.url).searchParams.get('userId')?.trim() || null
  if (userId) {
    const denied = await requireActionOwner(req, userId, 'app')
    if (denied) return denied
  }
  try { return NextResponse.json(await appInstallSnapshot(db, appId, userId), { headers }) }
  catch (error) { return hubAppErrorResponse(error) }
}
export async function POST(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  const body = await safeJson(req)
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'app')
  if (denied) return denied
  try { return NextResponse.json(await setAppInstalled(db, appId, userId, true), { headers }) }
  catch (error) { return hubAppErrorResponse(error) }
}
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { appId } = await params
  const app = resolveApp(appId)
  if (!app) return NextResponse.json({ error: 'Unknown app id.' }, { status: 404 })
  const body = await safeJson(req)
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'app')
  if (denied) return denied
  try { return NextResponse.json(await setAppInstalled(db, appId, userId, false), { headers }) }
  catch (error) { return hubAppErrorResponse(error) }
}

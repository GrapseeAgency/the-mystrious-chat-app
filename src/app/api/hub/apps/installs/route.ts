import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { appInstallsBatchSnapshot, setAppInstalled, hubAppErrorResponse } from '@/lib/hub-app-operation'

// /api/hub/apps/installs - Section 11 item 6: ONE batch snapshot endpoint for
// the whole catalog. Installation scans stop paying one request per app.
// The static segment wins over [appId], so this coexists with the per-app
// install route that remains the write path.
export const dynamic = 'force-dynamic'

const headers = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }

export async function GET(req: Request) {
  const userId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'app')
  if (denied) return denied
  try { return NextResponse.json(await appInstallsBatchSnapshot(db, userId), { headers }) }
  catch (error) { return hubAppErrorResponse(error) }
}

/** Bulk connect helper for offline recovery - same replay contract as the
 * per-app write, one entry per row. A body {actions: [{appId, installed,
 * clientId}]} is applied in order; the first failure stops the batch and is
 * reported with its index. */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'app')
  if (denied) return denied
  const actions = Array.isArray(body.actions) ? body.actions : null
  if (!actions || actions.length === 0 || actions.length > 200) {
    return NextResponse.json({ error: 'actions must be a non-empty list (max 200).' }, { status: 400 })
  }
  const applied: Array<{ appId: string; installed: boolean; replayed: boolean; installsRev: number }> = []
  try {
    for (const [index, action] of actions.entries()) {
      const appId = typeof action?.appId === 'string' ? action.appId.trim() : ''
      if (!/^[1-9][0-9]*$/.test(appId)) {
        return NextResponse.json({ error: `actions[${index}].appId is not a catalog app id.` }, { status: 400 })
      }
      const installed = action.installed === true
      const clientId = typeof action.clientId === 'string' && action.clientId ? action.clientId : null
      const snapshot = await setAppInstalled(db, appId, userId, installed, clientId)
      applied.push({ appId, installed, replayed: snapshot.replayed === true, installsRev: snapshot.installsRev ?? 0 })
    }
    return NextResponse.json({ appVersion: 2, userId, applied }, { headers })
  } catch (error) {
    const status = error instanceof Error ? (error as unknown as { status?: number }).status : undefined
    if (error instanceof Error && typeof status === 'number') {
      return NextResponse.json({ error: error.message, appliedCount: applied.length }, { status })
    }
    return hubAppErrorResponse(error)
  }
}

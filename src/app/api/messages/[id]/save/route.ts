// Legacy web toggle plus explicit removal; native callers use /save-state.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { safeJson, strField, notifySocket } from '@/lib/serializers'
import { mutateSavedMessage, SavedMessageError } from '@/lib/saved-message-operation'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }
async function perform(req: Request, id: string, removing: boolean) {
  const userId = strField((await safeJson(req)).userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  {
    const denied = await requireActionOwner(req, userId, 'saved messages')
    if (denied) return denied
  }
  try {
    const receipt = await mutateSavedMessage(db, userId, id, removing ? false : undefined)
    await notifySocket('saved:changed', [userId], { userId, messageId: id })
    return NextResponse.json(receipt, { headers: { 'Cache-Control': 'private, no-store' } })
  }
  catch (error) {
    if (error instanceof SavedMessageError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}
export async function POST(req: Request, { params }: RouteCtx) { return perform(req, (await params).id, false) }
export async function DELETE(req: Request, { params }: RouteCtx) { return perform(req, (await params).id, true) }

// Native read acceptance distinguishes actual visibility from deliberate mark-all.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { memberIdsOf, notifySocket, safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { advanceReadReceipt, ReadReceiptError, receiptRecipients } from '@/lib/read-receipt'

export const dynamic = 'force-dynamic'
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req), userId = strField(body.userId)
  const mode = body.mode
  const messageId = typeof body.messageId === 'string' ? body.messageId.trim() : null
  if (!userId || (mode !== 'visible' && mode !== 'all') ||
      (mode === 'visible' && !messageId) || (mode === 'all' && body.messageId != null)) {
    return NextResponse.json({ error: 'Specify visible with a messageId, or an explicit all read action.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'read receipt')
  if (denied) return denied
  try {
    const at = await advanceReadReceipt(db, id, userId, mode === 'visible' ? messageId! : undefined)
    const owner = await db.user.findUnique({ where: { id: userId }, select: { preferences: true } })
    const recipients = receiptRecipients(userId, await memberIdsOf(id), owner?.preferences ?? null)
    await notifySocket('message:read', recipients, { conversationId: id, userId, lastReadAt: at.toISOString() })
    return NextResponse.json({ ok: true, readReceiptVersion: 1, conversationId: id, userId, mode,
      messageId: mode === 'visible' ? messageId : null, lastReadAt: at.toISOString() })
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

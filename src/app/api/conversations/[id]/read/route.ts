// Read acknowledgement: displayed boundary, monotonic progress, private receipts.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { memberIdsOf, notifySocket, safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { advanceReadReceipt, ReadReceiptError, receiptRecipients } from '@/lib/read-receipt'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (body.messageId !== undefined && (typeof body.messageId !== 'string' || !body.messageId.trim())) {
    return NextResponse.json({ error: 'messageId must identify a displayed message.' }, { status: 400 })
  }
  // A legacy mark-all route must not bypass the native read-state authority.
  const denied = await requireActionOwner(req, userId, 'read receipt')
  if (denied) return denied
  try {
    const at = await advanceReadReceipt(db, id, userId, body.messageId as string | undefined)
    const owner = await db.user.findUnique({ where: { id: userId }, select: { preferences: true } })
    const recipients = receiptRecipients(userId, await memberIdsOf(id), owner?.preferences)
    await notifySocket('message:read', recipients, { conversationId: id, userId, lastReadAt: at.toISOString() })
    return NextResponse.json({ ok: true, lastReadAt: at.toISOString() })
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

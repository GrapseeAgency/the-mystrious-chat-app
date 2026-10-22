// Native explicit pin state. Old gateways must refuse, never silently toggle.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mutateMessage, MessageMutationError } from '@/lib/message-mutation'
import { mapMessage, memberIdsOf, notifySocket, MESSAGE_FULL_INCLUDE, safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId || typeof body.pinned !== 'boolean') {
    return NextResponse.json({ error: 'userId and a boolean pinned are required.' }, { status: 400 })
  }
  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'pin')
    if (denied) return denied
  }
  const pinned = body.pinned
  try {
    const result = await mutateMessage(db, id, userId, { kind: 'pin', present: pinned }, (tx, messageId) =>
      tx.message.findUniqueOrThrow({ where: { id: messageId }, include: MESSAGE_FULL_INCLUDE }))
    if (result.changed) {
      const recipients = await memberIdsOf(result.conversationId)
      await notifySocket('message:pinned', recipients, { type: 'message:pinned', message: mapMessage(result.message),
        recipientIds: recipients, conversationId: result.conversationId })
    }
    return NextResponse.json({ message: mapMessage(result.message, userId), pin: { messageId: id, pinned } })
  } catch (error) {
    if (error instanceof MessageMutationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

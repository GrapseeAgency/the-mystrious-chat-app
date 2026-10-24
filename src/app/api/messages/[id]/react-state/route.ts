// Explicit state only: older gateways must refuse instead of toggling on retry.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mutateMessage, MessageMutationError } from '@/lib/message-mutation'
import { mapMessage, memberIdsOf, notifySocket, MESSAGE_FULL_INCLUDE, REACTION_EMOJIS, safeJson, strField } from '@/lib/serializers'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId), emoji = strField(body.emoji)
  if (!userId || typeof body.present !== 'boolean' || !(REACTION_EMOJIS as readonly string[]).includes(emoji)) {
    return NextResponse.json({ error: 'userId, a supported reaction and a boolean present are required.' }, { status: 400 })
  }
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'reaction')
    if (denied) return denied
  }
  const present = body.present
  try {
    const result = await mutateMessage(db, id, userId, { kind: 'react', emoji, present }, (tx, messageId) =>
      tx.message.findUniqueOrThrow({ where: { id: messageId }, include: MESSAGE_FULL_INCLUDE }))
    if (result.changed) {
      const recipients = await memberIdsOf(result.conversationId)
      await notifySocket('message:react', recipients, { type: 'message:react', message: mapMessage(result.message),
        recipientIds: recipients, conversationId: result.conversationId })
    }
    return NextResponse.json({ message: mapMessage(result.message, userId), reaction: { messageId: id, emoji, present } })
  } catch (error) {
    if (error instanceof MessageMutationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

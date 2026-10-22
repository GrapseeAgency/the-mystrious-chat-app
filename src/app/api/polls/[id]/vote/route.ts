import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mutatePoll, PollMutationError } from '@/lib/poll-mutation'
import { mapMessage, memberIdsOf, notifySocket, safeJson, strField, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const optionId = strField(body.optionId)
  if (!userId || !optionId) return NextResponse.json({ error: 'userId and optionId are required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'poll')
    if (denied) return denied
  }
  try {
    const result = await mutatePoll(db, id, userId, { kind: 'vote', optionId }, (tx, messageId) =>
      tx.message.findUniqueOrThrow({ where: { id: messageId }, include: MESSAGE_FULL_INCLUDE }))
    if (result.changed) {
      const recipients = await memberIdsOf(result.conversationId)
      // Neutral voter metadata, including the actor's other devices.
      await notifySocket('poll:voted', recipients, { type: 'poll:voted', message: mapMessage(result.message),
        recipientIds: recipients, conversationId: result.conversationId })
    }
    return NextResponse.json({ message: mapMessage(result.message, userId) })
  } catch (error) {
    if (error instanceof PollMutationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

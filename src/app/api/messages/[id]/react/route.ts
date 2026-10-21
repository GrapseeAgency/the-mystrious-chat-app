// /api/messages/[id]/react - toggle a reaction (value domain: reaction ids)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mutateMessage, MessageMutationError } from '@/lib/message-mutation'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  MESSAGE_FULL_INCLUDE,
  REACTION_EMOJIS,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const emoji = strField(body.emoji)
  if (!(REACTION_EMOJIS as readonly string[]).includes(emoji)) {
    return NextResponse.json({ error: 'Unsupported reaction.' }, { status: 400 })
  }
  try {
    const result = await mutateMessage(db, id, userId, { kind: 'react', emoji }, (tx, messageId) =>
      tx.message.findUniqueOrThrow({ where: { id: messageId }, include: MESSAGE_FULL_INCLUDE }))
    const mapped = mapMessage(result.message, userId)
    if (result.changed) {
      const recipients = await memberIdsOf(result.conversationId)
      await notifySocket('message:react', recipients, { type: 'message:react', message: mapMessage(result.message),
        recipientIds: recipients, conversationId: result.conversationId })
    }
    return NextResponse.json({ message: mapped })
  } catch (error) {
    if (error instanceof MessageMutationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

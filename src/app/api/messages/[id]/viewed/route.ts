// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/viewed — consume a view-once attachment
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  safeJson,
  strField,
  MESSAGE_FULL_INCLUDE,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/messages/[id]/viewed  body { userId }
 * → 200 { message: ChatMessage }. Idempotent: the FIRST non-sender
 * open stamps viewedAt/viewedBy forever. Relays message:viewed so the
 * sender's bubble flips to its burned state live.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const message = await db.message.findUnique({
    where: { id },
    select: { conversationId: true, senderId: true, viewOnce: true, viewedAt: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (!message.viewOnce) {
    return NextResponse.json({ error: 'This message is not view-once.' }, { status: 400 })
  }
  if (message.senderId === userId) {
    return NextResponse.json({ error: 'The sender always sees their own photo.' }, { status: 400 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: message.conversationId } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  if (message.viewedAt === null) {
    await db.message.update({
      where: { id },
      data: { viewedAt: new Date(), viewedBy: userId },
    })
  }

  const fresh = await db.message.findUnique({ where: { id }, include: MESSAGE_FULL_INCLUDE })
  if (!fresh) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }

  const mapped = mapMessage(fresh, userId)
  const recipients = (await memberIdsOf(message.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  await notifySocket('message:viewed', recipients, {
    type: 'message:viewed',
    message: mapped,
    recipientIds: recipients,
    conversationId: message.conversationId,
  })

  return NextResponse.json({ message: mapped })
}

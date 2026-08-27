// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/react — toggle an emoji reaction
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
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

/**
 * POST /api/messages/[id]/react  body { userId, emoji }
 * Toggles the (messageId, userId, emoji) reaction.
 * → { message: ChatMessage } (fresh reactions) · relays message:react to others.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const emoji = strField(body.emoji)
  if (!(REACTION_EMOJIS as readonly string[]).includes(emoji)) {
    return NextResponse.json({ error: 'Unsupported reaction emoji.' }, { status: 400 })
  }

  const message = await db.message.findUnique({
    where: { id },
    select: { conversationId: true, deletedAt: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.deletedAt) {
    return NextResponse.json(
      { error: 'Deleted messages cannot be reacted to.' },
      { status: 400 },
    )
  }

  const participant = await db.conversationParticipant.findUnique({
    where: {
      userId_conversationId: { userId, conversationId: message.conversationId },
    },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  // Toggle: unique key exists → delete, else create.
  const existing = await db.reaction.findUnique({
    where: { messageId_userId_emoji: { messageId: id, userId, emoji } },
    select: { id: true },
  })
  if (existing) {
    await db.reaction.delete({ where: { id: existing.id } })
  } else {
    await db.reaction.create({ data: { messageId: id, userId, emoji } })
  }

  const fresh = await db.message.findUnique({
    where: { id },
    include: MESSAGE_FULL_INCLUDE,
  })
  if (!fresh) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }

  const mapped = mapMessage(fresh)

  // Relay to every OTHER member (the actor already holds the new state).
  const recipients = (await memberIdsOf(message.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  await notifySocket('message:react', recipients, {
    type: 'message:react',
    message: mapped,
    recipientIds: recipients,
    conversationId: message.conversationId,
  })

  return NextResponse.json({ message: mapped })
}

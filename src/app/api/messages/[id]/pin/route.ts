// /api/messages/[id]/pin - toggle pinned state for a message
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { removeMessagePin, PinAccessError } from '@/lib/pin-operation'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  MESSAGE_FULL_INCLUDE,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/messages/[id]/pin  body { userId }
 * Any conversation participant may pin/unpin (toggle).
 * Deleted messages cannot be pinned (400).
 * → { message: ChatMessage } (fresh pinned state) · relays message:pinned.
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
    select: { conversationId: true, deletedAt: true, pinnedAt: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.deletedAt) {
    return NextResponse.json(
      { error: 'Deleted messages cannot be pinned.' },
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

  const updated = await db.message.update({
    where: { id },
    data: message.pinnedAt
      ? { pinnedAt: null, pinnedBy: null } // toggle off
      : { pinnedAt: new Date(), pinnedBy: userId }, // pin
    include: MESSAGE_FULL_INCLUDE,
  })

  // Actor already holds the new state → relay to every OTHER member.
  const recipients = (await memberIdsOf(message.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  const mapped = mapMessage(updated)
  await notifySocket('message:pinned', recipients, {
    type: 'message:pinned',
    message: mapped,
    recipientIds: recipients,
    conversationId: message.conversationId,
  })

  return NextResponse.json({ message: mapped })
}


/** Retrying an acknowledged/lost-response Unpin must never pin it again. */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const denied = await requireActionOwner(req, userId, 'unpin')
  if (denied) return denied
  try {
    const conversationId = await removeMessagePin(db, userId, id)
    const message = await db.message.findUnique({ where: { id }, include: MESSAGE_FULL_INCLUDE })
    if (message) {
      const recipients = await memberIdsOf(conversationId)
      await notifySocket('message:pinned', recipients, { type: 'message:pinned', message: mapMessage(message),
        recipientIds: recipients, conversationId })
    }
    return NextResponse.json({ unpinned: true })
  } catch (error) {
    if (error instanceof PinAccessError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

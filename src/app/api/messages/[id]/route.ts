// ─────────────────────────────────────────────────────────────
// /api/messages/[id] — edit (sender only) + soft-delete (sender only)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  MESSAGE_FULL_INCLUDE,
  MESSAGE_MAX,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/messages/[id] body { userId, content }
 * Only the sender may edit; deleted messages are not editable (400).
 * Content must be 1..MESSAGE_MAX chars after trim. Sets editedAt (kept even
 * if the text is reverted — mirrors Telegram). Media captions editable too.
 * → { message: ChatMessage } · relays message:edited to other members.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const content = strField(body.content)
  if (content.length === 0) {
    return NextResponse.json({ error: 'content cannot be empty.' }, { status: 400 })
  }
  if (content.length > MESSAGE_MAX) {
    return NextResponse.json(
      { error: `content is too long (max ${MESSAGE_MAX} characters).` },
      { status: 400 },
    )
  }

  const message = await db.message.findUnique({
    where: { id },
    select: { conversationId: true, senderId: true, deletedAt: true, content: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.senderId !== userId) {
    return NextResponse.json(
      { error: 'Only the sender can edit this message.' },
      { status: 403 },
    )
  }
  if (message.deletedAt) {
    return NextResponse.json(
      { error: 'Deleted messages cannot be edited.' },
      { status: 400 },
    )
  }

  const updated = await db.message.update({
    where: { id },
    data: { content, editedAt: new Date() },
    include: MESSAGE_FULL_INCLUDE,
  })

  // Sender already knows → relay only to other members of the conversation.
  const recipients = (await memberIdsOf(updated.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  const mapped = mapMessage(updated)
  await notifySocket('message:edited', recipients, {
    type: 'message:edited',
    message: mapped,
    recipientIds: recipients,
    conversationId: updated.conversationId,
  })

  return NextResponse.json({ message: mapped })
}

/**
 * DELETE /api/messages/[id] body { requesterId }
 * Only the sender may delete; already-deleted → 200 no-op.
 * Soft-deletes (deletedAt=now), relays message:deleted to other members.
 * → { message: ChatMessage }
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const message = await db.message.findUnique({
    where: { id },
    include: MESSAGE_FULL_INCLUDE,
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }

  // Idempotent no-op for tombstones.
  if (message.deletedAt) {
    return NextResponse.json({ message: mapMessage(message) })
  }

  if (message.senderId !== requesterId) {
    return NextResponse.json(
      { error: 'Only the sender can delete this message.' },
      { status: 403 },
    )
  }

  const updated = await db.message.update({
    where: { id },
    data: { deletedAt: new Date() },
    include: MESSAGE_FULL_INCLUDE,
  })

  // Sender already knows → relay only to other members of the conversation.
  const recipients = (await memberIdsOf(updated.conversationId)).filter(
    (memberId) => memberId !== requesterId,
  )
  const mapped = mapMessage(updated)
  await notifySocket('message:deleted', recipients, {
    type: 'message:deleted',
    message: mapped,
    recipientIds: recipients,
    conversationId: updated.conversationId,
  })

  return NextResponse.json({ message: mapped })
}

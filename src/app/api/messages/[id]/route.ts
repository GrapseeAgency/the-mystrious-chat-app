// ─────────────────────────────────────────────────────────────
// /api/messages/[id] — soft-delete (sender only) + relay
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
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

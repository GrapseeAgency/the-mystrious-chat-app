// /api/messages/[id]/save - Telegram-style star/save toggle
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { removeSavedMessage, threadRootAvailable } from '@/lib/message-visibility'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/messages/[id]/save  body { userId }
 * → 200 { saved: boolean } - toggles the bookmark for THIS user.
 * Participant-guarded; deleted messages cannot be saved.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'saved messages')
    if (denied) return denied
  }
  const message = await db.message.findUnique({
    where: { id },
    select: { conversationId: true, deletedAt: true, expiresAt: true, viewOnce: true, parentId: true,
      parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } } },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.expiresAt && message.expiresAt <= new Date()) {
    return NextResponse.json({ error: 'Message expired.' }, { status: 410 })
  }
  if (message.deletedAt) {
    return NextResponse.json({ error: 'Deleted messages cannot be saved.' }, { status: 400 })
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

  if (message.viewOnce) return NextResponse.json({ error: 'View-once messages cannot be saved.' }, { status: 400 })
  if (message.parentId && !threadRootAvailable(message.parent, message.conversationId, new Date())) {
    return NextResponse.json({ error: 'This thread is no longer available.' }, { status: 410 })
  }

  const existing = await db.savedMessage.findUnique({
    where: { userId_messageId: { userId, messageId: id } },
    select: { id: true },
  })

  if (existing) {
    await db.savedMessage.delete({ where: { id: existing.id } })
    return NextResponse.json({ saved: false })
  }
  await db.savedMessage.create({ data: { userId, messageId: id } })
  return NextResponse.json({ saved: true })
}

/** DELETE is an explicit, idempotent Unsave; never fall back to POST's toggle. */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const denied = await requireActionOwner(req, userId, 'saved messages')
  if (denied) return denied
  return NextResponse.json(await removeSavedMessage(db, userId, id))
}

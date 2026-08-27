// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/save — Telegram-style star/save toggle
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/messages/[id]/save  body { userId }
 * → 200 { saved: boolean } — toggles the bookmark for THIS user.
 * Participant-guarded; deleted messages cannot be saved.
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
    select: { conversationId: true, deletedAt: true },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
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

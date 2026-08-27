// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/archive — per-user archive toggle
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/archive  body { userId, archived }
 * archived=true  → sets the viewer's archivedAt watermark (chat leaves the main list).
 * archived=false → clears it (back to the main list).
 * A new incoming message auto-clears the watermark (see messages POST).
 * → { ok: true, archived: boolean, archivedAt: string | null }
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (typeof body.archived !== 'boolean') {
    return NextResponse.json({ error: 'archived must be a boolean.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({ where: { id }, select: { id: true } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const nextArchivedAt = body.archived ? new Date() : null
  await db.conversationParticipant.update({
    where: { id: participant.id },
    data: { archivedAt: nextArchivedAt },
  })

  return NextResponse.json({
    ok: true,
    archived: nextArchivedAt !== null,
    archivedAt: nextArchivedAt ? nextArchivedAt.toISOString() : null,
  })
}

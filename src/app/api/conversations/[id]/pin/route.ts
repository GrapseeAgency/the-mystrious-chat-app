// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/pin — per-user pinned-to-top toggle
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/pin  body { userId }
 * Toggles the viewer's pinnedAt watermark.
 * → { ok: true, pinned: boolean, pinnedAt: string | null }
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({ where: { id }, select: { id: true } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true, pinnedAt: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const nextPinnedAt = participant.pinnedAt ? null : new Date()
  await db.conversationParticipant.update({
    where: { id: participant.id },
    data: { pinnedAt: nextPinnedAt },
  })

  return NextResponse.json({
    ok: true,
    pinned: nextPinnedAt !== null,
    pinnedAt: nextPinnedAt ? nextPinnedAt.toISOString() : null,
  })
}

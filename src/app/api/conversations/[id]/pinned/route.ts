// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/pinned — list pinned messages
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/conversations/[id]/pinned?userId=
 * Participant-guarded list of pinned messages (oldest pin first).
 * → { messages: ChatMessage[] } — 403 for non-participants.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
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

  const rows = await db.message.findMany({
    where: { conversationId: id, pinnedAt: { not: null }, deletedAt: null },
    orderBy: { pinnedAt: 'asc' },
    include: MESSAGE_FULL_INCLUDE,
  })

  return NextResponse.json({ messages: rows.map(mapMessage) })
}

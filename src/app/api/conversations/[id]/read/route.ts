// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/read — mark-read receipt + relay
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { memberIdsOf, notifySocket, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/conversations/[id]/read body { userId }
 * Participant-only: bumps lastReadAt=now, relays a ReadEvent to the others.
 * → { ok: true } | 400 | 403 | 404
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  // Existence first (404), then membership (403).
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
      select: { id: true },
    }),
  ])
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const now = new Date()
  await db.conversationParticipant.update({
    where: { userId_conversationId: { userId, conversationId: id } },
    data: { lastReadAt: now },
  })

  // Everyone except the reader gets the watermark update.
  const recipients = (await memberIdsOf(id)).filter((memberId) => memberId !== userId)
  await notifySocket('message:read', recipients, {
    conversationId: id,
    userId,
    lastReadAt: now.toISOString(),
  })

  return NextResponse.json({ ok: true })
}

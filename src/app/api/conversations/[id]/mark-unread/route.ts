// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/mark-unread — per-VIEWER unread dot
// ─────────────────────────────────────────────────────────────
// R44 — WhatsApp/Telegram "mark as unread": a participant flags a row so it
// re-appears with the unread badge even with nothing new to read. The dot
// clears the next time the room is opened (the read route flips it off).
// Self-service comfort setting — never admin-gated (mirrors R42 screenPrivacy).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/mark-unread  body { userId, on }
 * on: boolean — true = badge my own row; false = clear it.
 * Participant-only (403 for everyone else). A viewer may only flag their
 * OWN row — userId IS the requester by construction of the call sites.
 * → { ok: true, manualUnread: boolean } | 400 | 403 | 404
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (typeof body.on !== 'boolean') {
    return NextResponse.json({ error: 'on must be a boolean.' }, { status: 400 })
  }

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

  await db.conversationParticipant.update({
    where: { id: participant.id },
    data: { manualUnread: body.on },
  })

  return NextResponse.json({ ok: true, manualUnread: body.on })
}

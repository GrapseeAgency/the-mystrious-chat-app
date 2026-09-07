// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/screen-privacy — per-VIEWER screen security
// ─────────────────────────────────────────────────────────────
// R42 — the personal half of Signal's screen security. The conversation-wide
// flag (PATCH /api/conversations/[id] { screenPrivacy }) frosts the room for
// EVERY member; this route lets any participant frost their OWN view without
// touching anyone else's. chat-room applies the veil when EITHER flag is on.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/screen-privacy  body { userId, on }
 * on: boolean — true = frost MY view while Pulse is unfocused.
 * Participant-only (403 for everyone else) — a comfort setting, never
 * admin-gated (mirrors the room-wide flag's R38 gating decision).
 * → { ok: true, screenPrivacy: boolean } (the viewer's personal flag)
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

  await db.conversationParticipant.update({
    where: { id: participant.id },
    data: { screenPrivacy: body.on },
  })

  return NextResponse.json({ ok: true, screenPrivacy: body.on })
}

// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/draft — server-synced composer draft
// ─────────────────────────────────────────────────────────────
// R45 — Telegram-style cross-device drafts: a participant's half-typed
// message is persisted on THEIR participant row so it restores from any
// device/tab and surfaces as a "Draft: …" preview in the chats list even
// where no local draft exists. Empty string clears. The client debounces
// writes (~600ms) and mirrors every local store mutation through this
// route, so all existing clear/save paths sync with zero call-site churn.
// Self-service — a viewer may only write their OWN row (userId IS the
// requester by construction; mirrors mark-unread/screenPrivacy gating).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Same cap as the composer + localStorage draft store. */
const MAX_DRAFT = 2000

/**
 * PATCH /api/conversations/[id]/draft  body { userId, draft }
 * draft: string ≤ 2000 chars ('' = clear).
 * → { ok: true, draft: string } | 400 | 403 | 404
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (typeof body.draft !== 'string') {
    return NextResponse.json({ error: 'draft must be a string.' }, { status: 400 })
  }
  const draft = body.draft.slice(0, MAX_DRAFT)

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
    data: { draft: draft.length > 0 ? draft : null },
  })

  return NextResponse.json({ ok: true, draft })
}

// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/slow-mode — Telegram-style slow mode
// ─────────────────────────────────────────────────────────────
// R44 — group admins cap how often MEMBERS may send (admins are always
// exempt, mirroring Telegram). Enforcement lives server-side in the messages
// POST (429 + retryAfter); this route only manages the room-wide setting.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Honest preset ladder — arbitrary values would fake the UX affordance. */
export const SLOW_MODE_PRESETS = [0, 5, 10, 30, 60, 300] as const

/**
 * PATCH /api/conversations/[id]/slow-mode  body { userId, seconds }
 * seconds: one of 0 (off) | 5 | 10 | 30 | 60 | 300 — wait window between
 * member sends. ADMIN-only (403 for members — it throttles THEM).
 * → { ok: true, slowModeSeconds: number } | 400 | 403 | 404
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const seconds = body.seconds
  if (typeof seconds !== 'number' || !Number.isInteger(seconds) || !(SLOW_MODE_PRESETS as readonly number[]).includes(seconds)) {
    return NextResponse.json(
      { error: `seconds must be one of: ${SLOW_MODE_PRESETS.join(', ')}.` },
      { status: 400 },
    )
  }

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
      select: { id: true, role: true },
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
  if (participant.role !== 'admin') {
    return NextResponse.json(
      { error: 'Only admins can change slow mode.' },
      { status: 403 },
    )
  }

  await db.conversation.update({
    where: { id },
    data: { slowModeSeconds: seconds },
  })

  return NextResponse.json({ ok: true, slowModeSeconds: seconds })
}

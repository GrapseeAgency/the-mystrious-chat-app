// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/mute — per-user notification mute
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Preset → offset in milliseconds ("always" ≈ 50 years out). */
const MUTE_PRESETS_MS: Record<string, number> = {
  '8h': 8 * 60 * 60 * 1000,
  '1w': 7 * 24 * 60 * 60 * 1000,
  always: 50 * 365 * 24 * 60 * 60 * 1000,
}

/**
 * PATCH /api/conversations/[id]/mute  body { userId, until }
 * until: '8h' | '1w' | 'always' → set mutedUntil watermark.
 * until: null | 'off' → unmute.
 * → { ok: true, mutedUntil: string | null } (null = unmuted)
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  let mutedUntil: Date | null
  if (body.until === null || body.until === undefined || strField(body.until) === 'off') {
    mutedUntil = null
  } else {
    const preset = strField(body.until)
    const offsetMs = MUTE_PRESETS_MS[preset]
    if (offsetMs === undefined) {
      return NextResponse.json(
        { error: "until must be one of '8h' | '1w' | 'always' | 'off' | null." },
        { status: 400 },
      )
    }
    mutedUntil = new Date(Date.now() + offsetMs)
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
    data: { mutedUntil },
  })

  return NextResponse.json({
    ok: true,
    mutedUntil: mutedUntil ? mutedUntil.toISOString() : null,
  })
}

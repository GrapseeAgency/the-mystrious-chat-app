// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/disappearing — WhatsApp/Signal TTL switch
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  memberIdsOf,
  notifySocket,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Allowed disappearing-message TTL presets (seconds). 0 = off. */
const TTL_PRESETS = [0, 86_400, 604_800, 2_592_000] as const // off · 24h · 7d · 30d

/**
 * PATCH /api/conversations/[id]/disappearing  body { userId, ttlSeconds }
 * → 200 { conversation: ConversationDetail } · relays conversation:updated.
 * Any participant may change it — applies to messages sent AFTER the change.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const rawTtl = body.ttlSeconds
  if (typeof rawTtl !== 'number' || !Number.isInteger(rawTtl)) {
    return NextResponse.json({ error: 'ttlSeconds must be an integer.' }, { status: 400 })
  }
  if (!(TTL_PRESETS as readonly number[]).includes(rawTtl)) {
    return NextResponse.json(
      { error: `ttlSeconds must be one of ${TTL_PRESETS.join(', ')}.` },
      { status: 400 },
    )
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true },
  })
  if (!participant) {
    const exists = await db.conversation.findUnique({ where: { id }, select: { id: true } })
    if (!exists) {
      return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
    }
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const updated = await db.conversation.update({
    where: { id },
    data: { ttlSeconds: rawTtl },
    include: CONVERSATION_FULL_INCLUDE,
  })

  const recipients = await memberIdsOf(id)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })

  return NextResponse.json({ conversation: buildConversationDetail(updated, userId) })
}

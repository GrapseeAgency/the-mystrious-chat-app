import { clipUtf16 } from '@/lib/text-limits'
// /api/webhooks/[token] - Discord-style webhook ingest + cleanup.
// POST   (PUBLIC) { content, username? } → lands a real Message row
//        into the webhook's conversation, authored by the bot user
//        ("pulseai") when it participates, else the first participant.
// DELETE ?requesterId= (or JSON body) - participant-admin only.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { messageDeliveryDecision } from '@/lib/scheduled-delivery'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { roomControl } from '@/lib/room-control'
import {
  mapMessage,
  memberIdsOf,
  MESSAGE_MAX,
  MESSAGE_FULL_INCLUDE,
  notifySocket,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Permissive CORS - external services (CI bots, scripts, other apps) post here. */
const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
}

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: CORS_HEADERS })
}

// In-memory rate limit: 20 posts / minute / token

const RATE_LIMIT = 20
const RATE_WINDOW_MS = 60_000
const rateHits = new Map<string, number[]>()

function rateLimited(token: string): boolean {
  const now = Date.now()
  const recent = (rateHits.get(token) ?? []).filter((t) => now - t < RATE_WINDOW_MS)
  if (recent.length >= RATE_LIMIT) {
    rateHits.set(token, recent)
    return true
  }
  recent.push(now)
  rateHits.set(token, recent)
  // Opportunistic sweep so abandoned tokens never pin memory.
  if (rateHits.size > 500) {
    for (const [key, hits] of rateHits) {
      if (hits.every((t) => now - t >= RATE_WINDOW_MS)) rateHits.delete(key)
    }
  }
  return false
}

// POST - public ingest

/**
 * POST /api/webhooks/[token] { content, username? }
 * → 200 { success: true } · 400 invalid content · 404 unknown token ·
 *   429 rate limited (20/min/token).
 * The message rides the same pipeline as a normal send: real Message row,
 * conversation.updatedAt bump, archive unpin, socket relay to other members.
 */
export async function POST(req: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params

  const body = await safeJson(req)
  const content = strField(body.content)
  if (content.length === 0) {
    return NextResponse.json({ error: 'content is required.' }, { status: 400, headers: CORS_HEADERS })
  }
  if (content.length > MESSAGE_MAX) {
    return NextResponse.json(
      { error: `content must be ${MESSAGE_MAX} characters or fewer.` },
      { status: 400, headers: CORS_HEADERS },
    )
  }
  const result = await db.$transaction(async tx => {
    const webhook = await tx.webhook.findUnique({ where: { token } })
    if (!webhook) return { status: 404, error: 'Webhook not found.' }
    // Unknown tokens must not allocate permanent rate-limit buckets.
    if (rateLimited(token)) return { status: 429, error: 'Rate limit exceeded - max 20 posts per minute.', retryAfter: 60 }
    const participants = await tx.conversationParticipant.findMany({
      where: { conversationId: webhook.conversationId }, select: { userId: true },
      orderBy: [{ joinedAt: 'asc' }, { userId: 'asc' }],
    })
    if (!participants.length) return { status: 404, error: 'Webhook author is no longer available.' }
    const botUser = await tx.user.findUnique({ where: { username: 'pulseai' }, select: { id: true } })
    const senderId = botUser && participants.some(row => row.userId === botUser.id) ? botUser.id : participants[0].userId
    const now = new Date()
    const decision = await messageDeliveryDecision(tx, webhook.conversationId, senderId, now, true)
    if (decision.state === 'cancel') return { status: 403, error: 'Webhook posting is not permitted by the current room policy.' }
    if (decision.state === 'wait') return { status: 429, error: 'Room slow mode is active.', retryAfter: decision.retryAfter }
    const message = await tx.message.create({ data: {
      conversationId: webhook.conversationId, senderId, content, kind: 'text', viaAutomation: true,
      payload: JSON.stringify({ webhook: true, webhookName: clipUtf16(strField(body.username), 40) || webhook.name, webhookColor: webhook.avatarColor }),
      expiresAt: decision.expiresAt, createdAt: now,
    }, include: MESSAGE_FULL_INCLUDE })
    await tx.conversation.update({ where: { id: webhook.conversationId }, data: { updatedAt: now } })
    await tx.conversationParticipant.updateMany({ where: {
      conversationId: webhook.conversationId, userId: { not: senderId }, archivedAt: { not: null },
    }, data: { archivedAt: null } })
    return { message, conversationId: webhook.conversationId }
  }, { isolationLevel: 'Serializable' })
  if (!result.message) return NextResponse.json({ error: result.error }, { status: result.status, headers: {
    ...CORS_HEADERS, ...(result.retryAfter ? { 'Retry-After': String(result.retryAfter) } : {}),
  } })
  // A committed post must not turn into a send failure merely because the
  // realtime relay is unavailable; external services would retry and duplicate it.
  try {
    const recipients = await memberIdsOf(result.conversationId)
    await notifySocket('message:new', recipients, {
      type: 'message:new', message: mapMessage(result.message), recipientIds: recipients,
      conversationId: result.conversationId,
    })
  } catch { console.error('[webhooks] committed post notification unavailable') }
  return NextResponse.json({ success: true, messageId: result.message.id }, { headers: CORS_HEADERS })
}

// DELETE - participant-admin only

/**
 * DELETE /api/webhooks/[token]?requesterId=X (also accepts JSON body)
 * → { ok: true } - X must be an admin of the webhook's conversation.
 */
export async function DELETE(req: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params

  let requesterId = new URL(req.url).searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) {
    const body = await safeJson(req)
    requesterId = strField(body.requesterId)
  }
  const refusal = await requireActionOwner(req, requesterId, 'webhook-control')
  if (refusal) return refusal
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  return roomControl(db, async tx => {
    const webhook = await tx.webhook.findUnique({ where: { token } })
    if (!webhook) {
      return NextResponse.json({ error: 'Webhook not found.' }, { status: 404 })
    }

    const participant = await tx.conversationParticipant.findUnique({
      where: {
        userId_conversationId: { userId: requesterId, conversationId: webhook.conversationId },
      },
      select: { role: true },
    })
    if (!participant) {
      return NextResponse.json(
        { error: 'You are not a participant of this conversation.' },
        { status: 403 },
      )
    }
    if (participant.role !== 'admin') {
      return NextResponse.json(
        { error: 'Only admins can delete webhooks.' },
        { status: 403 },
      )
    }

    await tx.webhook.delete({ where: { token } })
    return NextResponse.json({ ok: true, deleted: true, token, userId: requesterId }, { headers: CORS_HEADERS })
  })
}

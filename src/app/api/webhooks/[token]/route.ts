// ─────────────────────────────────────────────────────────────
// /api/webhooks/[token] — Discord-style webhook ingest + cleanup.
// POST   (PUBLIC) { content, username? } → lands a real Message row
//        into the webhook's conversation, authored by the bot user
//        ("pulseai") when it participates, else the first participant.
// DELETE ?requesterId= (or JSON body) — participant-admin only.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
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

/** Permissive CORS — external services (CI bots, scripts, other apps) post here. */
const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
}

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: CORS_HEADERS })
}

// ── In-memory rate limit: 20 posts / minute / token ──────────

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

// ── POST — public ingest ─────────────────────────────────────

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
  if (rateLimited(token)) {
    return NextResponse.json(
      { error: 'Rate limit exceeded — max 20 posts per minute.' },
      { status: 429, headers: CORS_HEADERS },
    )
  }

  const webhook = await db.webhook.findUnique({ where: { token } })
  if (!webhook) {
    return NextResponse.json({ error: 'Webhook not found.' }, { status: 404, headers: CORS_HEADERS })
  }

  // Author: the bot user when it participates, else the first participant
  // (deterministic: joinedAt asc, then userId asc).
  const participants = await db.conversationParticipant.findMany({
    where: { conversationId: webhook.conversationId },
    select: { userId: true },
    orderBy: [{ joinedAt: 'asc' }, { userId: 'asc' }],
  })
  if (participants.length === 0) {
    return NextResponse.json(
      { error: 'No participant available to author webhook messages.' },
      { status: 500, headers: CORS_HEADERS },
    )
  }
  let senderId = participants[0].userId
  const botUser = await db.user.findUnique({ where: { username: 'pulseai' }, select: { id: true } })
  if (botUser && participants.some((p) => p.userId === botUser.id)) {
    senderId = botUser.id
  }

  const displayName = strField(body.username).slice(0, 40) || webhook.name
  const payload = JSON.stringify({
    webhookName: displayName,
    webhookColor: webhook.avatarColor,
    webhook: true,
  })

  const now = new Date()
  const conv = await db.conversation.findUnique({
    where: { id: webhook.conversationId },
    select: { ttlSeconds: true },
  })
  const expiresAt =
    conv && conv.ttlSeconds > 0 ? new Date(now.getTime() + conv.ttlSeconds * 1000) : null

  const created = await db.$transaction(async (tx) => {
    const msg = await tx.message.create({
      data: {
        conversationId: webhook.conversationId,
        senderId,
        content,
        kind: 'text',
        payload,
        ...(expiresAt ? { expiresAt } : {}),
      },
      include: MESSAGE_FULL_INCLUDE,
    })
    await tx.conversation.update({
      where: { id: webhook.conversationId },
      data: { updatedAt: now },
    })
    await tx.conversationParticipant.updateMany({
      where: {
        conversationId: webhook.conversationId,
        userId: { not: senderId },
        archivedAt: { not: null },
      },
      data: { archivedAt: null },
    })
    return msg
  })

  const recipients = (await memberIdsOf(webhook.conversationId)).filter((id) => id !== senderId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapMessage(created),
    recipientIds: recipients,
    conversationId: webhook.conversationId,
  })

  return NextResponse.json({ success: true }, { headers: CORS_HEADERS })
}

// ── DELETE — participant-admin only ──────────────────────────

/**
 * DELETE /api/webhooks/[token]?requesterId=X (also accepts JSON body)
 * → { ok: true } — X must be an admin of the webhook's conversation.
 */
export async function DELETE(req: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params

  let requesterId = new URL(req.url).searchParams.get('requesterId')?.trim() ?? ''
  if (!requesterId) {
    const body = await safeJson(req)
    requesterId = strField(body.requesterId)
  }
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const webhook = await db.webhook.findUnique({ where: { token } })
  if (!webhook) {
    return NextResponse.json({ error: 'Webhook not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
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

  await db.webhook.delete({ where: { token } })
  return NextResponse.json({ ok: true }, { headers: CORS_HEADERS })
}

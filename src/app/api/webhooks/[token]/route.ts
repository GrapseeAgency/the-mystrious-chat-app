import { clipUtf16 } from '@/lib/text-limits'
import { randomUUID } from 'node:crypto'
// /api/webhooks/[token] - Discord-style webhook ingest + capability admin.
// POST   (PUBLIC) { content, username? } → lands a real Message row
//        into the webhook's conversation, authored by THE canonical
//        machine account (serviceKey 'pulse.bot') when it participates.
//        Rate limit is DURABLE per token (20/min stored on the row,
//        Section 12 item 5) - no in-memory bucket to reset on deploy.
// PATCH  { requesterId, clientId? } - admin-only capability ROTATION
//        (Section 12 item 9): mints a fresh token, stamps rotatedAt, and
//        the old URL stops working immediately. Previously copied/exposed
//        URLs are revoked by rotating (or deleting).
// DELETE ?requesterId= (or JSON body) - participant-admin only. With
//        clientId: durable delete receipt (Section 12 item 1).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { deliverLivePush } from '@/lib/live-push-outbox'
import { messageDeliveryDecision } from '@/lib/scheduled-delivery'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { roomControl } from '@/lib/room-control'
import {
  bumpIntegrationRevision,
  canonicalBotId,
  probeIntegrationOp,
  settleIntegrationOp,
  webhookRateWindow,
} from '@/lib/integration-contracts.ts'
import { clientIdFrom, clientIdFromQuery } from '@/lib/section10-contracts.ts'
import {
  mapMessage,
  memberIdsOf,
  MESSAGE_MAX,
  MESSAGE_FULL_INCLUDE,
  notifySocket,
  safeJson,
  strField,
} from '@/lib/serializers'

function urlSearchParamsClientId(url: string): string | null {
  return new URL(url).searchParams.get('clientId')
}

/** Same shape as the collection route's DTO - kept local so route modules
 * never import from each other (mirrors the automation routes' convention). */
function toWebhookDTO(row: {
  id: string; name: string; token: string; avatarColor: string
  createdAt: Date; createdBy: string; rotatedAt: Date | null
}) {
  return {
    id: row.id,
    name: row.name,
    token: row.token,
    avatarColor: row.avatarColor,
    url: `/api/webhooks/${row.token}`,
    createdAt: row.createdAt.toISOString(),
    createdBy: row.createdBy,
    rotatedAt: row.rotatedAt ? row.rotatedAt.toISOString() : null,
  }
}

export const dynamic = 'force-dynamic'

/** Permissive CORS - external services (CI bots, scripts, other apps) post here. */
const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, PATCH, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
}

export async function OPTIONS() {
  return new NextResponse(null, { status: 204, headers: CORS_HEADERS })
}

// POST - public ingest

/**
 * POST /api/webhooks/[token] { content, username? }
 * → 200 { success: true } · 400 invalid content · 404 unknown token ·
 *   429 rate limited (20/min/token, durable).
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
    // Item 5: the durable rate window lives on the row and is enforced in
    // this Serializable tx - restart- and instance-safe. Unknown tokens
    // still never allocate buckets.
    const admission = await webhookRateWindow(tx, webhook, new Date())
    if (!admission.allowed) {
      return { status: 429, error: 'Rate limit exceeded - max 20 posts per minute.', retryAfter: admission.retryAfter }
    }
    const participants = await tx.conversationParticipant.findMany({
      where: { conversationId: webhook.conversationId }, select: { userId: true },
      orderBy: [{ joinedAt: 'asc' }, { userId: 'asc' }],
    })
    if (!participants.length) return { status: 404, error: 'Webhook author is no longer available.' }
    // Item 6: THE canonical machine author - serviceKey first, legacy
    // handle/name branches stamp themselves forward. No mutable display
    // name or handle decides who authors machine messages.
    const botId = await canonicalBotId(tx)
    // R44 section-5 machine-sender semantics: the fallback used to be the
    // oldest HUMAN participant, silently attributing automation output to a
    // real account (unread attribution, notifications, receipts, forwards).
    // If the room has no canonical machine member there is no honest machine
    // author - refuse rather than misattribute.
    if (!botId || !participants.some(row => row.userId === botId)) {
      return { status: 409, error: 'This conversation has no automation author. Add the automation account to the room first.' }
    }
    const senderId = botId
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
  // Machine producers ride the same remote push path as human sends (the
  // planner re-reads current mute/block/membership/preview privacy).
  void deliverLivePush(db, result.message.id)
  return NextResponse.json({ success: true, messageId: result.message.id }, { headers: CORS_HEADERS })
}

// PATCH - authorized capability rotation

/**
 * PATCH /api/webhooks/[token] { requesterId, clientId? }
 * → 200 WebhookDTO with a FRESH token and rotatedAt stamped. The previously
 * copied ingest URL dies with the old token - revocation and rotation are
 * the same atomic act (Section 12 item 9). Requester must be an admin of
 * the webhook's conversation. With clientId: durable rotate receipt.
 */
export async function PATCH(req: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  const refusal = await requireActionOwner(req, requesterId, 'webhook-control')
  if (refusal) return refusal
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }
  const clientId = clientIdFrom(body)
  if (!clientId.ok) {
    return NextResponse.json({ error: 'clientId must be 16-128 url-safe characters.' }, { status: 400 })
  }

  return roomControl(db, async tx => {
    // Rotation-replay comes in on the OLD token (the ACK was lost before the
    // client could learn the fresh one), so the receipt probe MUST run before
    // the row lookup. The stored op carries the conversation the requester
    // must still administrate.
    if (clientId.clientId) {
      const probe = await probeIntegrationOp<Record<string, unknown>>(tx, {
        kind: 'webhook.rotate', clientId: clientId.clientId, conversationId: '', userId: requesterId, identity: [token],
      })
      if (probe.status === 'mismatch') {
        return NextResponse.json(
          { error: 'clientId already used for a different integration operation.' },
          { status: 409 },
        )
      }
      if (probe.status === 'replay') {
        const participant = await tx.conversationParticipant.findUnique({
          where: { userId_conversationId: { userId: requesterId, conversationId: probe.conversationId } },
          select: { role: true },
        })
        if (!participant || participant.role !== 'admin') {
          return NextResponse.json({ error: 'Only admins can rotate webhooks.' }, { status: 403 })
        }
        // The receipt carries the CURRENT capability when the webhook still
        // exists (a later rotation may have replaced the token again); the
        // frozen stored DTO is the fallback for a since-deleted webhook.
        const current = await tx.webhook.findFirst({ where: { id: (probe.result as { id?: string }).id ?? '' } })
        return NextResponse.json({
          ...(current ? toWebhookDTO(current) : probe.result),
          replayed: true,
        })
      }
    }

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
        { error: 'Only admins can rotate webhooks.' },
        { status: 403 },
      )
    }

    // The rotation itself: a brand-new opaque token replaces the old one in
    // the same write. The old URL 404s from this instant.
    const rotated = await tx.webhook.update({
      where: { id: webhook.id },
      data: { token: randomUUID().replace(/-/g, ''), rotatedAt: new Date() },
    })
    const revision = await bumpIntegrationRevision(tx, webhook.conversationId)
    const dto = toWebhookDTO(rotated)
    if (clientId.clientId) {
      const settled = await settleIntegrationOp(
        tx,
        { kind: 'webhook.rotate', clientId: clientId.clientId, conversationId: webhook.conversationId, userId: requesterId, identity: [token] },
        rotated.id,
        { ...dto, revision },
      )
      return NextResponse.json({ ...settled.result, replayed: settled.replayed })
    }
    return NextResponse.json({ ...dto, revision })
  })
}

// DELETE - participant-admin only

/**
 * DELETE /api/webhooks/[token]?requesterId=X (also accepts JSON body)
 * → { ok: true } - X must be an admin of the webhook's conversation.
 * With clientId: durable delete receipt (item 1).
 */
export async function DELETE(req: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params

  let requesterId = new URL(req.url).searchParams.get('requesterId')?.trim() ?? ''
  let clientId: { ok: true; clientId: string | null } = { ok: true, clientId: null }
  if (!requesterId) {
    const body = await safeJson(req)
    requesterId = strField(body.requesterId)
    clientId = clientIdFrom(body)
  } else {
    clientId = clientIdFromQuery(urlSearchParamsClientId(req.url))
  }
  const refusal = await requireActionOwner(req, requesterId, 'webhook-control')
  if (refusal) return refusal
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }
  if (!clientId.ok) {
    return NextResponse.json({ error: 'clientId must be 16-128 url-safe characters.' }, { status: 400 })
  }

  return roomControl(db, async tx => {
    // Delete-replay FIRST: after a successful delete the row (and its
    // conversation pointer) is gone; the stored op still authorizes.
    if (clientId.clientId) {
      const probe = await probeIntegrationOp<Record<string, unknown>>(tx, {
        kind: 'webhook.delete', clientId: clientId.clientId, conversationId: '', userId: requesterId, identity: [token],
      })
      if (probe.status === 'mismatch') {
        return NextResponse.json(
          { error: 'clientId already used for a different integration operation.' },
          { status: 409 },
        )
      }
      if (probe.status === 'replay') {
        const participant = await tx.conversationParticipant.findUnique({
          where: { userId_conversationId: { userId: requesterId, conversationId: probe.conversationId } },
          select: { role: true },
        })
        if (!participant || participant.role !== 'admin') {
          return NextResponse.json({ error: 'Only admins can delete webhooks.' }, { status: 403 })
        }
        return NextResponse.json({ ...probe.result, replayed: true }, { headers: CORS_HEADERS })
      }
    }

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
    const revision = await bumpIntegrationRevision(tx, webhook.conversationId)
    const payload = { ok: true, deleted: true, token, userId: requesterId, revision }
    if (clientId.clientId) {
      const settled = await settleIntegrationOp(
        tx,
        { kind: 'webhook.delete', clientId: clientId.clientId, conversationId: webhook.conversationId, userId: requesterId, identity: [token] },
        null,
        payload,
      )
      return NextResponse.json({ ...settled.result, replayed: settled.replayed }, { headers: CORS_HEADERS })
    }
    return NextResponse.json(payload, { headers: CORS_HEADERS })
  })
}

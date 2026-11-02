// /api/webhooks - manage Discord-style incoming webhooks.
// POST   { conversationId, name, requesterId, clientId? } → 201 WebhookDTO
// GET    ?conversationId=&requesterId=&limit=&cursor=
//        → { webhooks: WebhookDTO[], revision, page: { nextCursor, hasMore } }
//        (createdAt, id) ASC keyset; without cursor the first `limit` rows
//        come back (default 50). `revision` is the conversation's monotonic
//        integration revision (Section 12 item 2).
// POST carries a durable create identity with clientId: a lost-ACK retry
// replays the SAME webhook token (replayed:true), a reused clientId with
// different instructions is a 409 (Section 12 item 1).
// Management requires a credential-bound conversation admin
// (deletion/rotation are admin-only and live on /api/webhooks/[token]).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { roomControl } from '@/lib/room-control'
import { AVATAR_COLORS, safeJson, strField } from '@/lib/serializers'
import { bumpIntegrationRevision, probeIntegrationOp, settleIntegrationOp } from '@/lib/integration-contracts.ts'
import {
  clientIdFrom,
  decodeKeysetCursor,
  keysetAfter,
  pageLimit,
  pageVerdict,
} from '@/lib/section10-contracts.ts'

export const dynamic = 'force-dynamic'

const WEBHOOK_NAME_MAX = 32
const WEBHOOK_PAGE_DEFAULT = 50
const WEBHOOK_PAGE_MAX = 200

/** Shape consumed by the group-info webhooks card. */
export interface WebhookDTO {
  id: string
  name: string
  token: string
  avatarColor: string
  /** relative ingest URL - pair with the app origin on the client */
  url: string
  createdAt: string // ISO
  createdBy: string
  /** ISO instant of the last capability rotation (Section 12 item 9). */
  rotatedAt: string | null
}

interface WebhookRow {
  id: string
  name: string
  token: string
  avatarColor: string
  createdAt: Date
  createdBy: string
  rotatedAt: Date | null
}

function toWebhookDTO(row: WebhookRow): WebhookDTO {
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

/**
 * GET /api/webhooks?conversationId=X&requesterId=Y[&limit=&cursor=]
 * → { webhooks, revision, page } (createdAt asc keyset) - Y must be an authenticated admin.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const conversationId = url.searchParams.get('conversationId')?.trim() ?? ''
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  const refusal = await requireActionOwner(req, requesterId, 'webhook-control')
  if (refusal) return refusal
  if (!conversationId || !requesterId) {
    return NextResponse.json(
      { error: 'conversationId and requesterId are required.' },
      { status: 400 },
    )
  }
  const limit = pageLimit(url.searchParams.get('limit'), WEBHOOK_PAGE_MAX)
  if (!limit.ok) {
    return NextResponse.json({ error: `limit must be an integer 1-${WEBHOOK_PAGE_MAX}.` }, { status: 400 })
  }
  const cursor = decodeKeysetCursor(url.searchParams.get('cursor'))
  if (!cursor.ok) {
    return NextResponse.json({ error: 'cursor must be a `<epochMs>.<id>` keyset cursor.' }, { status: 400 })
  }

  return roomControl(db, async tx => {
    const [conv, participant] = await Promise.all([
      tx.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
      tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: requesterId, conversationId } },
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

    if (participant.role !== 'admin') return NextResponse.json({ error: 'Only admins can access webhook credentials or create webhooks.' }, { status: 403 })

    const cap = limit.limit ?? WEBHOOK_PAGE_DEFAULT
    const rows = await tx.webhook.findMany({
      where: { conversationId, ...(cursor.absent ? {} : keysetAfter(cursor.atMs, cursor.id)) },
      orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
      take: cap + 1,
    })
    const verdict = pageVerdict(rows, cap, row => ({ atMs: row.createdAt.getTime(), id: row.id }))
    const revision = await tx.integrationRevision.findUnique({
      where: { conversationId }, select: { rev: true },
    })
    return NextResponse.json({
      webhooks: verdict.rows.map(toWebhookDTO),
      revision: revision?.rev ?? 0,
      page: { nextCursor: verdict.nextCursor, hasMore: verdict.hasMore },
    })
  })
}

/**
 * POST /api/webhooks { conversationId, name, requesterId, clientId? }
 * → 201 WebhookDTO (token = cuid via @default(cuid()), unique).
 * Requester must be an authenticated conversation admin.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const conversationId = strField(body.conversationId)
  const requesterId = strField(body.requesterId)
  const refusal = await requireActionOwner(req, requesterId, 'webhook-control')
  if (refusal) return refusal
  const name = strField(body.name)
  const clientId = clientIdFrom(body)
  if (!clientId.ok) {
    return NextResponse.json({ error: 'clientId must be 16-128 url-safe characters.' }, { status: 400 })
  }
  if (!conversationId || !requesterId) {
    return NextResponse.json(
      { error: 'conversationId and requesterId are required.' },
      { status: 400 },
    )
  }
  if (name.length === 0 || name.length > WEBHOOK_NAME_MAX) {
    return NextResponse.json(
      { error: `Webhook name must be 1-${WEBHOOK_NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  return roomControl(db, async tx => {
    const [conv, participant] = await Promise.all([
      tx.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
      tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: requesterId, conversationId } },
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

    if (participant.role !== 'admin') return NextResponse.json({ error: 'Only admins can access webhook credentials or create webhooks.' }, { status: 403 })

    // Lost-ACK replay FIRST: the retry must return the SAME capability URL
    // instead of minting a second credential for the same instructions.
    if (clientId.clientId) {
      const args = { kind: 'webhook.create' as const, clientId: clientId.clientId, conversationId, userId: requesterId, identity: [conversationId, requesterId, name] }
      const probe = await probeIntegrationOp<WebhookDTO>(tx, args)
      if (probe.status === 'mismatch') {
        return NextResponse.json(
          { error: 'clientId already used for a different integration operation.' },
          { status: 409 },
        )
      }
      if (probe.status === 'replay') {
        return NextResponse.json({ ...probe.result, replayed: true }, { status: 201 })
      }
    }

    // Colorful dots in the management card - picked from the avatar palette.
    const avatarColor = AVATAR_COLORS[Math.floor(Math.random() * AVATAR_COLORS.length)]
    const created = await tx.webhook.create({
      data: { conversationId, name, avatarColor, createdBy: requesterId },
    })
    const revision = await bumpIntegrationRevision(tx, conversationId)
    const dto = toWebhookDTO(created)
    if (clientId.clientId) {
      const settled = await settleIntegrationOp(
        tx,
        { kind: 'webhook.create', clientId: clientId.clientId, conversationId, userId: requesterId, identity: [conversationId, requesterId, name] },
        created.id,
        dto,
      )
      return NextResponse.json({ ...settled.result, replayed: settled.replayed }, { status: 201 })
    }
    return NextResponse.json(dto, { status: 201 })
  })
}

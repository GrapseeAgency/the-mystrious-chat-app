// ─────────────────────────────────────────────────────────────
// /api/webhooks — manage Discord-style incoming webhooks.
// POST   { conversationId, name, requesterId } → 201 WebhookDTO
// GET    ?conversationId=&requesterId=         → { webhooks: WebhookDTO[] }
// Both require the requester to be a conversation participant
// (deletion is admin-only and lives on /api/webhooks/[token]).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { AVATAR_COLORS, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

export const WEBHOOK_NAME_MAX = 32

/** Shape consumed by the group-info webhooks card. */
export interface WebhookDTO {
  id: string
  name: string
  token: string
  avatarColor: string
  /** relative ingest URL — pair with the app origin on the client */
  url: string
  createdAt: string // ISO
  createdBy: string
}

interface WebhookRow {
  id: string
  name: string
  token: string
  avatarColor: string
  createdAt: Date
  createdBy: string
}

export function toWebhookDTO(row: WebhookRow): WebhookDTO {
  return {
    id: row.id,
    name: row.name,
    token: row.token,
    avatarColor: row.avatarColor,
    url: `/api/webhooks/${row.token}`,
    createdAt: row.createdAt.toISOString(),
    createdBy: row.createdBy,
  }
}

/**
 * GET /api/webhooks?conversationId=X&requesterId=Y
 * → { webhooks: WebhookDTO[] } (createdAt asc) — Y must be a participant.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const conversationId = url.searchParams.get('conversationId')?.trim() ?? ''
  const requesterId = url.searchParams.get('requesterId')?.trim() ?? ''
  if (!conversationId || !requesterId) {
    return NextResponse.json(
      { error: 'conversationId and requesterId are required.' },
      { status: 400 },
    )
  }

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: requesterId, conversationId } },
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

  const rows = await db.webhook.findMany({
    where: { conversationId },
    orderBy: { createdAt: 'asc' },
  })
  return NextResponse.json({ webhooks: rows.map(toWebhookDTO) })
}

/**
 * POST /api/webhooks { conversationId, name, requesterId }
 * → 201 WebhookDTO (token = cuid via @default(cuid()), unique).
 * Requester must be a conversation participant.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const conversationId = strField(body.conversationId)
  const requesterId = strField(body.requesterId)
  const name = strField(body.name)
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

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: requesterId, conversationId } },
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

  // Colorful dots in the management card — picked from the avatar palette.
  const avatarColor = AVATAR_COLORS[Math.floor(Math.random() * AVATAR_COLORS.length)]
  const created = await db.webhook.create({
    data: { conversationId, name, avatarColor, createdBy: requesterId },
  })
  return NextResponse.json(toWebhookDTO(created), { status: 201 })
}

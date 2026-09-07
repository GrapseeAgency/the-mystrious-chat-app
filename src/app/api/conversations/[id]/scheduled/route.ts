// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/scheduled — Telegram-style delayed send
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { parseIsoDate, safeJson, strField, MESSAGE_MAX } from '@/lib/serializers'
import type { ScheduledItem } from '@/lib/types'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const MIN_LEAD_MS = 30_000 // at least 30s out
const MAX_HORIZON_MS = 30 * 24 * 3600 * 1000 // no more than 30 days

function toItem(row: {
  id: string
  conversationId: string
  content: string
  scheduledAt: Date
  sentAt: Date | null
  cancelledAt: Date | null
  cancelledReason: string | null
}): ScheduledItem {
  return {
    id: row.id,
    conversationId: row.conversationId,
    content: row.content,
    scheduledAt: row.scheduledAt.toISOString(),
    sentAt: row.sentAt ? row.sentAt.toISOString() : null,
    cancelledAt: row.cancelledAt ? row.cancelledAt.toISOString() : null,
    cancelledReason: row.cancelledReason,
  }
}

/**
 * GET /api/conversations/[id]/scheduled?userId=X → { items: ScheduledItem[] }
 * The caller's OWN pending messages for this chat, soonest first.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
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

  // Pending rows AND rows dispatch refused to send (cancelled) — the sender
  // keeps visibility into both until they delete them.
  const rows = await db.scheduledMessage.findMany({
    where: { conversationId: id, senderId: userId, sentAt: null },
    orderBy: [{ cancelledAt: 'asc' }, { scheduledAt: 'asc' }],
    take: 50,
  })
  return NextResponse.json({ items: rows.map(toItem) })
}

/**
 * POST /api/conversations/[id]/scheduled  body { senderId, content, scheduledAt }
 * → 201 { item: ScheduledItem }. Broadcast mode still applies.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const senderId = strField(body.senderId)
  if (!senderId) {
    return NextResponse.json({ error: 'senderId is required.' }, { status: 400 })
  }
  const content = strField(body.content)
  if (content.length === 0) {
    return NextResponse.json({ error: 'content cannot be empty.' }, { status: 400 })
  }
  if (content.length > MESSAGE_MAX) {
    return NextResponse.json(
      { error: `Message must be ${MESSAGE_MAX} characters or fewer.` },
      { status: 400 },
    )
  }
  const when = parseIsoDate(body.scheduledAt)
  if (!when) {
    return NextResponse.json({ error: 'scheduledAt must be a valid ISO date string.' }, { status: 400 })
  }
  const nowMs = Date.now()
  if (when.getTime() < nowMs + MIN_LEAD_MS || when.getTime() > nowMs + MAX_HORIZON_MS) {
    return NextResponse.json(
      { error: 'scheduledAt must be between 30 seconds and 30 days from now.' },
      { status: 400 },
    )
  }

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true, isGroup: true, broadcastMode: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: senderId, conversationId: id } },
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
  if (conv.isGroup && conv.broadcastMode && participant.role !== 'admin') {
    return NextResponse.json(
      { error: 'Only admins can schedule messages while announcement mode is on.' },
      { status: 403 },
    )
  }

  const row = await db.scheduledMessage.create({
    data: { conversationId: id, senderId, content, scheduledAt: when },
  })

  return NextResponse.json({ item: toItem(row) }, { status: 201 })
}

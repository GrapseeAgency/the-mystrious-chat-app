// ─────────────────────────────────────────────────────────────
// /api/reminders — Beeper/Zulip-style per-message reminders.
// Each user reminds THEMSELVES about a message (or a whole chat):
// GET lists the viewer's rows, POST creates one. The client due-loop
// marks rows fired via PATCH /api/reminders/[id].
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { parseIsoDate, safeJson, strField } from '@/lib/serializers'
import type { ReminderItem } from '@/lib/types'

export const dynamic = 'force-dynamic'

/** Reminders may be set from "now" (60s clock-skew tolerance) out to 90 days. */
const PAST_TOLERANCE_MS = 60_000
const MAX_HORIZON_MS = 90 * 24 * 3600 * 1000
const NOTE_MAX = 280
const SNIPPET_MAX = 120

/** Row + resolved conversation/message context → wire shape (src/lib/types.ts). */
function toReminderItem(
  row: {
    id: string
    conversationId: string
    messageId: string | null
    note: string
    remindAt: Date
    firedAt: Date | null
    createdAt: Date
  },
  conversation: { id: string; name: string; isGroup: boolean },
  snippet: string | null,
): ReminderItem {
  return {
    id: row.id,
    conversationId: row.conversationId,
    messageId: row.messageId,
    note: row.note,
    remindAt: row.remindAt.toISOString(),
    firedAt: row.firedAt ? row.firedAt.toISOString() : null,
    createdAt: row.createdAt.toISOString(),
    conversation,
    snippet,
  }
}

/**
 * Display name for the sheet row: group name, or the DM peer's name
 * (self-chats keep their own label; degenerate rows fall back to "Chat").
 */
async function conversationLabel(
  conversationId: string,
  viewerId: string,
): Promise<{ id: string; name: string; isGroup: boolean } | null> {
  const conv = await db.conversation.findUnique({
    where: { id: conversationId },
    select: {
      id: true,
      name: true,
      isGroup: true,
      isSelf: true,
      participants: { select: { userId: true, user: { select: { name: true } } } },
    },
  })
  if (!conv) return null
  let name = conv.name
  if (!name) {
    if (conv.isSelf) {
      name = 'Note to self'
    } else {
      const peer = conv.participants.find((p) => p.userId !== viewerId)?.user
      name = peer?.name ?? 'Chat'
    }
  }
  return { id: conv.id, name, isGroup: conv.isGroup }
}

/** Anchored-message snippet for the nudge/sheet (null when deleted or missing). */
async function messageSnippet(messageId: string | null): Promise<string | null> {
  if (!messageId) return null
  const msg = await db.message.findUnique({
    where: { id: messageId },
    select: { content: true, deletedAt: true, kind: true },
  })
  if (!msg || msg.deletedAt !== null) return null
  const text = msg.content.replace(/\s+/g, ' ').trim()
  if (text.length > 0) return text.slice(0, SNIPPET_MAX)
  if (msg.kind === 'image') return 'Photo'
  if (msg.kind === 'audio') return 'Voice note'
  return null
}

/**
 * GET /api/reminders?userId=X[&due=1] → { items: ReminderItem[] }
 * The caller's OWN reminders, soonest first. With ?due=1 only rows
 * that are due right now (remindAt <= now) and not yet fired return.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const dueOnly = url.searchParams.get('due') === '1'
  const rows = await db.reminder.findMany({
    where: {
      userId,
      ...(dueOnly ? { remindAt: { lte: new Date() }, firedAt: null } : {}),
    },
    orderBy: { remindAt: 'asc' },
    take: 100,
  })

  const items = await Promise.all(
    rows.map(async (row) => {
      const [conversation, snippet] = await Promise.all([
        conversationLabel(row.conversationId, userId),
        messageSnippet(row.messageId),
      ])
      return toReminderItem(
        row,
        conversation ?? { id: row.conversationId, name: 'Chat', isGroup: false },
        snippet,
      )
    }),
  )

  return NextResponse.json({ items })
}

/**
 * POST /api/reminders  body { userId, conversationId, messageId?, note?, remindAt }
 * → 201 { item: ReminderItem }. Only participants of the conversation may
 * set reminders in it; remindAt must be a sane future-or-now ISO date.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const messageId = strField(body.messageId) || null
  if (messageId) {
    const message = await db.message.findUnique({
      where: { id: messageId },
      select: { id: true, conversationId: true },
    })
    if (!message || message.conversationId !== conversationId) {
      return NextResponse.json(
        { error: 'The anchored message was not found in this conversation.' },
        { status: 400 },
      )
    }
  }

  const note = strField(body.note)
  if (note.length > NOTE_MAX) {
    return NextResponse.json({ error: `Note must be ${NOTE_MAX} characters or fewer.` }, { status: 400 })
  }

  const remindAt = parseIsoDate(body.remindAt)
  if (!remindAt) {
    return NextResponse.json({ error: 'remindAt must be a valid ISO date string.' }, { status: 400 })
  }
  const nowMs = Date.now()
  if (remindAt.getTime() < nowMs - PAST_TOLERANCE_MS) {
    return NextResponse.json({ error: 'remindAt must be in the future.' }, { status: 400 })
  }
  if (remindAt.getTime() > nowMs + MAX_HORIZON_MS) {
    return NextResponse.json(
      { error: 'remindAt must be within the next 90 days.' },
      { status: 400 },
    )
  }

  const row = await db.reminder.create({
    data: { userId, conversationId, messageId, note, remindAt },
  })
  const conversation = await conversationLabel(conversationId, userId)

  return NextResponse.json(
    { item: toReminderItem(row, conversation ?? { id: conversationId, name: 'Chat', isGroup: false }, null) },
    { status: 201 },
  )
}

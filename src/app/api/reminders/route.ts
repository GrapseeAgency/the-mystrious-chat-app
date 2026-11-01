// /api/reminders - Beeper/Zulip-style per-message reminders.
// Each user reminds THEMSELVES about a message (or a whole chat):
// GET lists the viewer's rows, POST creates one. The client due-loop
// marks rows fired via PATCH /api/reminders/[id].
import { NextResponse } from 'next/server'
import { createHash } from 'node:crypto'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { clipUtf16 } from '@/lib/text-limits'
import { reminderPage, reminderAnchor, ReminderContractError } from '@/lib/reminder-contract'
import { parseIsoDate, safeJson, strField } from '@/lib/serializers'
import { clientIdFrom, opHash } from '@/lib/section10-contracts'
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
  source: { available: boolean | null; changed: boolean },
): ReminderItem & { sourceAvailable: boolean | null; sourceChanged: boolean } {
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
    // Section 10 item 12: snapshot integrity - the snippet is the AT-CREATION
    // copy; sourceAvailable/sourceChanged tell the reader whether that
    // snapshot still matches the live anchor.
    sourceAvailable: source.available,
    sourceChanged: source.changed,
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
  const conv = await db.conversation.findFirst({
    where: { id: conversationId, participants: { some: { userId: viewerId } } },
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

/** Anchored-message snippet helper retained for POST replay responses. */

/**
 * GET /api/reminders?userId=X[&due=1] → { items, nextCursor }
 * The caller's OWN reminders, soonest first. With ?due=1 only rows
 * that are due right now (remindAt <= now) and not yet fired return.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const denied = await requireActionOwner(req, userId, 'reminder')
  if (denied) return denied

  const dueOnly = url.searchParams.get('due') === '1'
  let page: Awaited<ReturnType<typeof reminderPage>>
  try { page = await reminderPage(db, userId, dueOnly, url.searchParams.get('cursor')) }
  catch (error) {
    if (error instanceof ReminderContractError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
  const rows = page.rows

  // Item 12: one batched conversation lookup + one batched message lookup
  // per page - never a query pair per row.
  const conversationIds: string[] = [...new Set(rows.map(row => row.conversationId))]
  const conversations = await db.conversation.findMany({
    where: { id: { in: conversationIds }, participants: { some: { userId } } },
    select: {
      id: true, name: true, isGroup: true, isSelf: true,
      participants: { select: { userId: true, user: { select: { name: true } } } },
    },
  })
  const conversationById = new Map(conversations.map(c => [c.id, c]))
  const labels = new Map<string, { id: string; name: string; isGroup: boolean }>()
  for (const conv of conversationById.values()) {
    let name = conv.name
    if (!name) {
      if (conv.isSelf) name = 'Note to self'
      else name = conv.participants.find((p) => p.userId !== userId)?.user?.name ?? 'Chat'
    }
    labels.set(conv.id, { id: conv.id, name, isGroup: conv.isGroup })
  }

  const messageIds: string[] = [...new Set(rows.map(row => row.messageId).filter((id): id is string => !!id))]
  // Item 12: anchors load in ONE query (parents included) and the
  // reminderAnchor lifetime rules are re-checked per row in memory - the
  // snippet contract (expired/deleted/thread-orphan -> null) is unchanged,
  // only its cost profile is bounded now.
  const messages = messageIds.length > 0 ? await db.message.findMany({
    where: { id: { in: messageIds } },
    select: {
      id: true, content: true, kind: true, deletedAt: true, expiresAt: true, parentId: true, conversationId: true,
      parent: { select: { id: true, conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
    },
  }) : []
  const messageById = new Map(messages.map(m => [m.id, m] as const))
  const nowMs = Date.now()
  type AnchorRow = {
    conversationId: string; deletedAt: Date | null; expiresAt: Date | null; content: string; kind: string
    parentId: string | null
    parent?: { conversationId: string; parentId: string | null; deletedAt: Date | null; expiresAt: Date | null } | null
  }
  const anchorUsable = (message: AnchorRow | undefined, conversationId: string): boolean => {
    if (!message || message.conversationId !== conversationId || message.deletedAt !== null ||
        (message.expiresAt && message.expiresAt.getTime() <= nowMs)) return false
    if (message.parentId) {
      const parent = message.parent
      if (!parent || parent.conversationId !== conversationId || parent.parentId !== null ||
          parent.deletedAt !== null || (parent.expiresAt && parent.expiresAt.getTime() <= nowMs)) return false
    }
    return true
  }
  const sourceOf = (row: { messageId: string | null; sourceHash: string | null; conversationId: string }): { available: boolean | null; changed: boolean } => {
    if (!row.messageId) return { available: null, changed: false }
    const message = messageById.get(row.messageId) as AnchorRow | undefined
    if (!anchorUsable(message, row.conversationId)) return { available: false, changed: false }
    if (!row.sourceHash) return { available: true, changed: false } // legacy rows carry no snapshot hash
    const liveHash = createHash('sha256').update(message!.content).digest('hex')
    return { available: true, changed: liveHash !== row.sourceHash }
  }
  const snippetOf = (row: { messageId: string | null; conversationId: string }): string | null => {
    if (!row.messageId) return null
    const message = messageById.get(row.messageId) as AnchorRow | undefined
    if (!anchorUsable(message, row.conversationId)) return null
    const text = message!.content.replace(/\s+/g, ' ').trim()
    if (text.length > 0) return clipUtf16(text, SNIPPET_MAX)
    if (message!.kind === 'image') return 'Photo'
    if (message!.kind === 'audio') return 'Voice note'
    return null
  }

  const items = rows.map((row) => {
    // A conversation the viewer can no longer read hides its anchor context
    // entirely (same rule the per-row conversationLabel gate always enforced).
    const label = labels.get(row.conversationId) ?? null
    const accessible = label !== null
    return toReminderItem(
      row,
      label ?? { id: row.conversationId, name: 'Chat', isGroup: false },
      accessible ? snippetOf(row) : null,
      accessible ? sourceOf(row) : { available: null, changed: false },
    )
  })

  return NextResponse.json({ items, nextCursor: page.nextCursor }, { headers: { 'Cache-Control': 'private, no-store' } })
}

/**
 * POST /api/reminders  body { userId, conversationId, messageId?, note?, remindAt[, clientId] }
 * → 201 { item, replayed: false } | 200 { item, replayed: true } (item 11).
 * Only participants of the conversation may set reminders in it; remindAt
 * must be a sane future-or-now ISO date. The anchored message's text is
 * snapshotted (sourceHash) so later readers can detect drift (item 12).
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

  const denied = await requireActionOwner(req, userId, 'reminder')
  if (denied) return denied

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
  let sourceHash: string | null = null
  if (messageId) {
    const message = await reminderAnchor(db, conversationId, messageId)
    if (!message) {
      return NextResponse.json(
        { error: 'The anchored message was not found in this conversation.' },
        { status: 400 },
      )
    }
    // Item 12: snapshot the anchor text exactly as read at creation.
    sourceHash = createHash('sha256').update(message.content).digest('hex')
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

  const clientIdVerdict = clientIdFrom(body)
  if (!clientIdVerdict.ok) {
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  }
  const clientId = clientIdVerdict.clientId
  const requestHash = opHash(['reminder-create', userId, conversationId, messageId, note, remindAt.getTime()])

  if (clientId) {
    const prior = await db.reminderCreationOperation.findUnique({
      where: { userId_clientId: { userId, clientId } },
    })
    if (prior) {
      if (prior.requestHash !== requestHash) {
        return NextResponse.json({ error: 'This reminder operation ID was already used for different instructions.' }, { status: 409 })
      }
      const existing = await db.reminder.findUnique({ where: { id: prior.reminderId } })
      if (!existing) {
        return NextResponse.json({ error: 'This created reminder no longer exists.' }, { status: 410 })
      }
      const conversation = await conversationLabel(existing.conversationId, userId)
      return NextResponse.json(
        { item: toReminderItem(existing, conversation ?? { id: existing.conversationId, name: 'Chat', isGroup: false }, null, { available: null, changed: false }), replayed: true },
        { status: 200, headers: { 'Cache-Control': 'private, no-store' } },
      )
    }
  }

  const row = await db.reminder.create({
    data: { userId, conversationId, messageId, note, remindAt, sourceHash },
  })
  if (clientId) {
    await db.reminderCreationOperation.create({
      data: { userId, clientId, requestHash, reminderId: row.id },
    })
  }
  const conversation = await conversationLabel(conversationId, userId)

  return NextResponse.json(
    { item: toReminderItem(row, conversation ?? { id: conversationId, name: 'Chat', isGroup: false }, null, { available: null, changed: false }), replayed: false },
    { status: 201, headers: { 'Cache-Control': 'private, no-store' } },
  )
}

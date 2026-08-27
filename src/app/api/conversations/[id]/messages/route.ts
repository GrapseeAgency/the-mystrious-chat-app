// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/messages — history + send
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { stat } from 'node:fs/promises'
import path from 'node:path'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  parseIsoDate,
  MESSAGE_FULL_INCLUDE,
  MESSAGES_DEFAULT_LIMIT,
  MESSAGES_MAX_LIMIT,
  MESSAGE_MAX,
  UPLOADS_DIR,
  AUDIO_EXT_REGEX,
  safeJson,
  strField,
} from '@/lib/serializers'
import { maybeAiReply } from '@/lib/ai-bot'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/conversations/[id]/messages?limit=200&before=<ISO>&q=<text>
 * → { messages: ChatMessage[], hasMore: boolean, total: number }
 *
 * Default window: the NEWEST `limit` messages, returned ascending.
 * `before=<ISO>` pages further back (messages strictly older than the ISO
 * timestamp — use the oldest message's createdAt as the cursor).
 * `q=<text>` switches into SEARCH mode: newest-first scan of the whole
 * conversation, case-insensitive substring match on text content,
 * soft-deleted rows excluded, capped at `limit` (max 100) returned
 * ascending with the FULL match count as `total`.
 * Soft-deleted rows are included (client renders tombstones) outside of search.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const conv = await db.conversation.findUnique({ where: { id }, select: { id: true } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  // Disappearing-message purge: hard-delete anything past its deadline before reading.
  await db.message.deleteMany({
    where: { conversationId: id, expiresAt: { lte: new Date() } },
  })

  const url = new URL(req.url)

  let limit = MESSAGES_DEFAULT_LIMIT
  const limitRaw = url.searchParams.get('limit')
  if (limitRaw !== null) {
    const parsed = Number.parseInt(limitRaw, 10)
    if (!Number.isFinite(parsed)) {
      return NextResponse.json({ error: 'limit must be an integer.' }, { status: 400 })
    }
    limit = Math.min(Math.max(parsed, 1), MESSAGES_MAX_LIMIT)
  }

  const beforeRaw = url.searchParams.get('before')
  let before: Date | undefined
  if (beforeRaw) {
    const parsed = parseIsoDate(beforeRaw)
    if (!parsed) {
      return NextResponse.json(
        { error: 'before must be a valid ISO date string.' },
        { status: 400 },
      )
    }
    before = parsed
  }

  const afterRaw = url.searchParams.get('after')
  if (afterRaw && !parseIsoDate(afterRaw)) {
    return NextResponse.json(
      { error: 'after must be a valid ISO date string.' },
      { status: 400 },
    )
  }

  // Search mode — case-insensitive substring scan (SQLite has no ICU collation,
  // so matching happens in Node over the conversation's rows).
  const q = (url.searchParams.get('q') ?? '').trim()
  if (q.length > 0) {
    const searchLimit = Math.min(limit, 100)
    const needle = q.toLowerCase()
    const rows = await db.message.findMany({
      where: { conversationId: id, deletedAt: null },
      orderBy: { createdAt: 'desc' },
      include: MESSAGE_FULL_INCLUDE,
    })
    const matched = rows.filter((row) => row.content.toLowerCase().includes(needle))
    const total = matched.length
    const window = matched.slice(0, searchLimit)
    window.reverse()
    return NextResponse.json({ messages: window.map((m) => mapMessage(m)), hasMore: false, total })
  }

  if (before) {
    // Older page: take N older than the cursor, then flip to ascending.
    const [rows, total] = await Promise.all([
      db.message.findMany({
        where: { conversationId: id, createdAt: { lt: before } },
        orderBy: { createdAt: 'desc' },
        take: limit,
        include: MESSAGE_FULL_INCLUDE,
      }),
      db.message.count({ where: { conversationId: id } }),
    ])
    rows.reverse()
    return NextResponse.json({ messages: rows.map((m) => mapMessage(m)), hasMore: rows.length === limit, total })
  }

  // Newest window (also the polling path — `after` narrows it when supplied).
  const [messages, total] = await Promise.all([
    db.message.findMany({
      where: {
        conversationId: id,
        ...(afterRaw ? { createdAt: { gt: parseIsoDate(afterRaw) as Date } } : {}),
      },
      orderBy: { createdAt: 'desc' },
      take: limit,
      include: MESSAGE_FULL_INCLUDE,
    }),
    db.message.count({ where: { conversationId: id } }),
  ])
  messages.reverse()
  return NextResponse.json({
    messages: messages.map((m) => mapMessage(m)),
    hasMore: messages.length === limit,
    total,
  })
}

/**
 * POST /api/conversations/[id]/messages { senderId, content }
 * → 201 { message: ChatMessage }; relays message:new to other members.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const senderId = strField(body.senderId)
  if (!senderId) {
    return NextResponse.json({ error: 'senderId is required.' }, { status: 400 })
  }
  const content = strField(body.content)
  if (content.length > MESSAGE_MAX) {
    return NextResponse.json(
      { error: `Message must be ${MESSAGE_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  // Optional image attachment — must reference a previously uploaded file.
  const imagePath = strField(body.imagePath)
  if (imagePath) {
    if (!/^[A-Za-z0-9-]+\.(jpg|jpeg|png|webp)$/.test(imagePath)) {
      return NextResponse.json({ error: 'imagePath is invalid.' }, { status: 400 })
    }
    try {
      await stat(path.join(UPLOADS_DIR, imagePath))
    } catch {
      return NextResponse.json(
        { error: 'imagePath does not reference an uploaded file. POST /api/uploads first.' },
        { status: 400 },
      )
    }
  }

  // Optional voice-note attachment — uploaded via /api/uploads first.
  const audioPath = strField(body.audioPath)
  if (audioPath) {
    if (!AUDIO_EXT_REGEX.test(audioPath)) {
      return NextResponse.json({ error: 'audioPath is invalid.' }, { status: 400 })
    }
    try {
      await stat(path.join(UPLOADS_DIR, audioPath))
    } catch {
      return NextResponse.json(
        { error: 'audioPath does not reference an uploaded file. POST /api/uploads first.' },
        { status: 400 },
      )
    }
  }
  const durationRaw = body.durationMs
  let durationMs: number | null = null
  if (durationRaw !== undefined && durationRaw !== null) {
    if (typeof durationRaw !== 'number' || !Number.isFinite(durationRaw) || durationRaw < 0 || durationRaw > 600_000) {
      return NextResponse.json({ error: 'durationMs must be a number between 0 and 600000.' }, { status: 400 })
    }
    durationMs = Math.round(durationRaw)
  }
  if (durationMs !== null && !audioPath) {
    return NextResponse.json({ error: 'durationMs is only valid together with audioPath.' }, { status: 400 })
  }
  if (!content && !imagePath && !audioPath) {
    return NextResponse.json(
      { error: 'Message needs text content, an image, or a voice note.' },
      { status: 400 },
    )
  }

  // Optional reply parent — must exist inside THIS conversation.
  const replyToId = strField(body.replyToId)
  if (replyToId) {
    const parent = await db.message.findUnique({
      where: { id: replyToId },
      select: { conversationId: true },
    })
    if (!parent || parent.conversationId !== id) {
      return NextResponse.json(
        { error: 'replyToId must reference a message in the same conversation.' },
        { status: 400 },
      )
    }
  }

  // Existence + membership checks up-front → clean 404 / 403 semantics.
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id } }),
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

  // Broadcast mode (Discord stage / Telegram channel): admins only post.
  if (conv.isGroup && conv.broadcastMode && participant.role !== 'admin') {
    return NextResponse.json(
      { error: 'Only admins can send messages while announcement mode is on.' },
      { status: 403 },
    )
  }

  // Optional thread parent (Slack/Zulip): must be a top-level, alive message here.
  const parentId = strField(body.parentId)
  if (parentId) {
    const root = await db.message.findUnique({
      where: { id: parentId },
      select: { conversationId: true, deletedAt: true, parentId: true },
    })
    if (!root || root.conversationId !== id || root.deletedAt) {
      return NextResponse.json(
        { error: 'parentId must reference an existing message in this conversation.' },
        { status: 400 },
      )
    }
    if (root.parentId !== null) {
      return NextResponse.json(
        { error: 'Threads are one level deep — reply to the thread root instead.' },
        { status: 400 },
      )
    }
  }

  // View-once is only meaningful on image messages.
  const viewOnce = body.viewOnce === true
  if (viewOnce && !imagePath) {
    return NextResponse.json(
      { error: 'viewOnce requires an image attachment.' },
      { status: 400 },
    )
  }

  const now = new Date()
  const expiresAt = conv.ttlSeconds > 0 ? new Date(now.getTime() + conv.ttlSeconds * 1000) : null
  const message = await db.$transaction(async (tx) => {
    const created = await tx.message.create({
      data: {
        conversationId: id,
        senderId,
        content,
        ...(replyToId ? { replyToId } : {}),
        ...(parentId ? { parentId } : {}),
        ...(viewOnce ? { viewOnce: true } : {}),
        ...(expiresAt ? { expiresAt } : {}),
        ...(imagePath ? { imagePath } : {}),
        ...(audioPath ? { audioPath, ...(durationMs !== null ? { durationMs } : {}) } : {}),
      },
      include: MESSAGE_FULL_INCLUDE,
    })
    // Bump list ordering — @updatedAt allows an explicit value write.
    await tx.conversation.update({ where: { id }, data: { updatedAt: now } })
    // Sender obviously read their own message.
    await tx.conversationParticipant.update({
      where: { userId_conversationId: { userId: senderId, conversationId: id } },
      data: { lastReadAt: now },
    })
    // A new message from the sender pulls the chat out of every OTHER
    // member's archive (WhatsApp behaviour — archive ≠ mute).
    await tx.conversationParticipant.updateMany({
      where: { conversationId: id, userId: { not: senderId }, archivedAt: { not: null } },
      data: { archivedAt: null },
    })
    return created
  })

  // Realtime relay to every OTHER member (sender handles self via response).
  const recipients = (await memberIdsOf(id)).filter((memberId) => memberId !== senderId)
  const mapped = mapMessage(message, senderId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapped,
    recipientIds: recipients,
    conversationId: id,
  })

  // Pulse AI companion: fire-and-forget evaluation (DMs + @mentions in groups).
  maybeAiReply(id, mapped)

  return NextResponse.json({ message: mapped }, { status: 201 })
}

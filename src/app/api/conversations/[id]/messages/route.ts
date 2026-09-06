// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/messages — history + send
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { stat } from 'node:fs/promises'
import path from 'node:path'
import { db } from '@/lib/db'
import {
  dayKey,
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
  DOC_EXT_REGEX,
  DOC_MAX_BYTES,
  safeJson,
  strField,
} from '@/lib/serializers'
import { XP_DAILY_CAP, XP_PER_MESSAGE } from '@/lib/xp'
import { maybeAiReply } from '@/lib/ai-bot'
import { botWillRespond, maybeBotReply } from '@/lib/bot-engine'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

// ── R24-b: deterministic incognito aliases (Session/SimpleX-style) ──
// Same person + same conversation ALWAYS maps to the same alias —
// FNV-1a over "userId:conversationId" keeps it stable across restarts.
const ANON_ADJECTIVES = ['Swift', 'Quiet', 'Neon', 'Ember', 'Frost', 'Lucky', 'Cosmic', 'Silent'] as const
const ANON_ANIMALS = ['Falcon', 'Otter', 'Panda', 'Wolf', 'Comet', 'Tiger', 'Raven', 'Fox'] as const

/** Stable 32-bit FNV-1a string hash (deterministic, no randomness). */
function stableHash(value: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

/** "Ember the Falcon" — deterministic per (user, conversation) pair. */
function anonAliasFor(userId: string, conversationId: string): string {
  const hash = stableHash(`${userId}:${conversationId}`)
  const adjective = ANON_ADJECTIVES[hash % ANON_ADJECTIVES.length]
  const animal = ANON_ANIMALS[Math.floor(hash / ANON_ADJECTIVES.length) % ANON_ANIMALS.length]
  return `${adjective} the ${animal}`
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

  // R24-b: optional Zulip-topic filter — only alive messages filed under the
  // given topic. Omitted → unchanged whole-room behavior (General included).
  const topicFilterRaw = strField(url.searchParams.get('topicId'))
  const topicFilter = topicFilterRaw
    ? { topicId: topicFilterRaw, deletedAt: null as null }
    : null

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
        where: {
          conversationId: id,
          createdAt: { lt: before },
          ...(topicFilter ?? {}),
        },
        orderBy: { createdAt: 'desc' },
        take: limit,
        include: MESSAGE_FULL_INCLUDE,
      }),
      db.message.count({ where: { conversationId: id, ...(topicFilter ?? {}) } }),
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
        ...(topicFilter ?? {}),
      },
      orderBy: { createdAt: 'desc' },
      take: limit,
      include: MESSAGE_FULL_INCLUDE,
    }),
    db.message.count({ where: { conversationId: id, ...(topicFilter ?? {}) } }),
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

  // Rich message kinds — sticker packs, live location cards, documents and
  // full-screen effects all ride the kind+payload pair (kind defaults to "text").
  const MESSAGE_KINDS = ['text', 'image', 'audio', 'sticker', 'location', 'file'] as const
  const EFFECTS = ['confetti', 'lasers', 'echo', 'sparkles'] as const
  const kind = strField(body.kind) || 'text'
  if (!(MESSAGE_KINDS as readonly string[]).includes(kind)) {
    return NextResponse.json(
      { error: `kind must be one of: ${MESSAGE_KINDS.join(', ')}.` },
      { status: 400 },
    )
  }
  let payload: string | null = null
  if (body.payload !== undefined && body.payload !== null) {
    if (typeof body.payload !== 'object' || Array.isArray(body.payload)) {
      return NextResponse.json({ error: 'payload must be an object.' }, { status: 400 })
    }
    const p = body.payload as Record<string, unknown>
    if (kind === 'sticker') {
      const emoji = strField(p.emoji)
      if (!emoji || emoji.length > 12) {
        return NextResponse.json({ error: 'payload.emoji (≤12 chars) is required for stickers.' }, { status: 400 })
      }
      if (strField(p.pack).length > 40) {
        return NextResponse.json({ error: 'payload.pack must be 40 characters or fewer.' }, { status: 400 })
      }
    } else if (kind === 'location') {
      const lat = p.lat
      const lng = p.lng
      if (
        typeof lat !== 'number' || typeof lng !== 'number' ||
        !Number.isFinite(lat) || !Number.isFinite(lng) ||
        Math.abs(lat) > 90 || Math.abs(lng) > 180
      ) {
        return NextResponse.json(
          { error: 'payload.lat/lng must be valid coordinates (|lat|≤90, |lng|≤180).' },
          { status: 400 },
        )
      }
      if (strField(p.label).length > 80) {
        return NextResponse.json({ error: 'payload.label must be 80 characters or fewer.' }, { status: 400 })
      }
    } else if (p.effect !== undefined) {
      if (typeof p.effect !== 'string' || !(EFFECTS as readonly string[]).includes(p.effect)) {
        return NextResponse.json(
          { error: `payload.effect must be one of: ${EFFECTS.join(', ')}.` },
          { status: 400 },
        )
      }
    }
    try {
      payload = JSON.stringify(p)
    } catch {
      return NextResponse.json({ error: 'payload is not serializable.' }, { status: 400 })
    }
    if (payload && payload.length > 2048) {
      return NextResponse.json({ error: 'payload must be 2KB or smaller.' }, { status: 400 })
    }
  }
  if (kind === 'sticker' && !payload) {
    return NextResponse.json({ error: 'Sticker messages need payload.emoji.' }, { status: 400 })
  }
  if (kind === 'location' && !payload) {
    return NextResponse.json({ error: 'Location messages need payload.lat/lng.' }, { status: 400 })
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

  // ── R40: document attachment (kind 'file') ────────────────
  // filePath references a previously-uploaded document (pdf/txt/csv/zip);
  // fileName is the ORIGINAL filename for display (1–120 chars, sanitized);
  // fileSize is optional metadata (0..10 MB). Non-file kinds must NOT carry
  // a filePath — a document can only travel inside a kind:'file' message.
  const rawFilePath = strField(body.filePath)
  if (rawFilePath && kind !== 'file') {
    return NextResponse.json(
      { error: "filePath is only valid on messages with kind 'file'." },
      { status: 400 },
    )
  }
  const rawFileName = typeof body.fileName === 'string' ? body.fileName : ''
  if ((rawFileName.trim().length > 0 || body.fileName !== undefined) && kind !== 'file') {
    return NextResponse.json(
      { error: "fileName is only valid on messages with kind 'file'." },
      { status: 400 },
    )
  }
  if (body.fileSize !== undefined && body.fileSize !== null && kind !== 'file') {
    return NextResponse.json(
      { error: "fileSize is only valid on messages with kind 'file'." },
      { status: 400 },
    )
  }
  // Sanitize the display name: strip control characters, collapse whitespace.
  const fileName = rawFileName.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim()
  if (kind === 'file' && fileName.length === 0) {
    return NextResponse.json({ error: 'fileName (1-120 characters) is required for file messages.' }, { status: 400 })
  }
  if (kind === 'file' && fileName.length > 120) {
    return NextResponse.json({ error: 'fileName must be 120 characters or fewer.' }, { status: 400 })
  }
  let fileSize: number | null = null
  if (body.fileSize !== undefined && body.fileSize !== null) {
    if (typeof body.fileSize !== 'number' || !Number.isInteger(body.fileSize) || body.fileSize < 0 || body.fileSize > DOC_MAX_BYTES) {
      return NextResponse.json(
        { error: `fileSize must be an integer between 0 and ${DOC_MAX_BYTES} (10 MB).` },
        { status: 400 },
      )
    }
    fileSize = body.fileSize
  }
  let filePath: string | null = null
  if (rawFilePath) {
    if (!DOC_EXT_REGEX.test(rawFilePath)) {
      return NextResponse.json({ error: 'filePath is invalid — documents must be pdf, txt, csv or zip.' }, { status: 400 })
    }
    try {
      await stat(path.join(UPLOADS_DIR, rawFilePath))
    } catch {
      return NextResponse.json(
        { error: 'filePath does not reference an uploaded file. POST /api/uploads first.' },
        { status: 400 },
      )
    }
    filePath = rawFilePath
  }
  if (kind === 'file' && !filePath) {
    return NextResponse.json({ error: 'File messages need a filePath from POST /api/uploads.' }, { status: 400 })
  }
  const needsBody = kind !== 'sticker' && kind !== 'location' && kind !== 'file'
  if (needsBody && !content && !imagePath && !audioPath) {
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

  // R24-b: optional Zulip-style topic filing — the topic must belong to THIS
  // conversation; message.topicId lands inside the create (General = null).
  const topicId = strField(body.topicId)
  if (topicId) {
    const topic = await db.topic.findUnique({
      where: { id: topicId },
      select: { conversationId: true },
    })
    if (!topic || topic.conversationId !== id) {
      return NextResponse.json({ error: 'Invalid topic.' }, { status: 400 })
    }
  }

  // R24-b: optional anonymous send — honored in GROUP chats only (ignored for
  // DMs). The alias is deterministic per (sender, conversation) so the sender
  // keeps one consistent mask per room.
  const anonRequested = body.anon === true

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
  // Anonymous flag only applies inside groups; DM sends stay named.
  const anon = anonRequested && conv.isGroup
  const anonAlias = anon ? anonAliasFor(senderId, id) : null
  const message = await db.$transaction(async (tx) => {
    const created = await tx.message.create({
      data: {
        conversationId: id,
        senderId,
        content,
        kind,
        ...(payload ? { payload } : {}),
        ...(replyToId ? { replyToId } : {}),
        ...(parentId ? { parentId } : {}),
        ...(topicId ? { topicId } : {}),
        ...(anon ? { anon: true, anonAlias } : {}),
        ...(viewOnce ? { viewOnce: true } : {}),
        ...(expiresAt ? { expiresAt } : {}),
        ...(imagePath ? { imagePath } : {}),
        ...(audioPath ? { audioPath, ...(durationMs !== null ? { durationMs } : {}) } : {}),
        ...(filePath ? { filePath, fileName, ...(fileSize !== null ? { fileSize } : {}) } : {}),
      },
      include: MESSAGE_FULL_INCLUDE,
    })
    if (topicId) {
      // Topic rail sort key — newest filed message keeps its topic on top.
      await tx.topic.update({ where: { id: topicId }, data: { lastMessageAt: now } })
    }
    if (anon) {
      await tx.logEvent.create({
        data: {
          userId: senderId,
          kind: 'message',
          message: 'anonymous message posted',
          meta: JSON.stringify({ conversationId: id, messageId: created.id }),
        },
      })
    }
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
  // Stands down when the deterministic bot engine owns this message, so a
  // single user message never earns two replies.
  if (!botWillRespond(content)) maybeAiReply(id, mapped)

  // Pulse bot engine: real command replies (/roll, /math, /wallet, /poll …).
  await maybeBotReply(id, { id: message.id, senderId, content })

  // R39: keyword automations — AFTER the human message is stored + notified.
  // Automation replies are authored directly via db with viaAutomation=true
  // and never re-enter this route, so the engine can never chain. A failure
  // here must never fail the human send (same principle as XP accounting).
  if (message.deletedAt === null) {
    await maybeAutomationReply(id, content)
  }

  // R31-a: gaming-grade daily XP cap — every real send earns XP_PER_MESSAGE
  // up to XP_DAILY_CAP per UTC day (message-XP only; other XP sources keep
  // their own rules). Read-modify-write on the sender row: the race window is
  // one in-flight send per user and the bucket self-corrects at midnight UTC.
  const todayUTC = dayKey(now)
  const yesterdayUTC = dayKey(new Date(now.getTime() - 86_400_000))

  let xpAwarded = 0
  try {
    const sender = await db.user.findUnique({
      where: { id: senderId },
      select: { xpToday: true, xpDay: true },
    })
    if (sender) {
      const effectiveToday = sender.xpDay === todayUTC ? sender.xpToday : 0
      if (effectiveToday < XP_DAILY_CAP) {
        const delta = Math.min(XP_PER_MESSAGE, XP_DAILY_CAP - effectiveToday)
        await db.user.update({
          where: { id: senderId },
          data: {
            xp: { increment: delta },
            xpToday: effectiveToday + delta,
            xpDay: todayUTC,
          },
        })
        xpAwarded = delta
      }
    }
  } catch {
    // XP accounting must never fail a send.
  }

  // R31-a: Snapchat-style chat streak — the FIRST message a user sends in a
  // conversation each UTC day keeps it alive: lastDay === yesterday grows the
  // count, anything older restarts at 1, same-day re-sends change nothing.
  let streak: { count: number; best: number; continued: boolean } | null = null
  try {
    const existing = await db.conversationStreak.findUnique({
      where: { conversationId_userId: { conversationId: id, userId: senderId } },
    })
    if (!existing || existing.lastDay !== todayUTC) {
      const continued = Boolean(existing && existing.lastDay === yesterdayUTC)
      const count = continued && existing ? existing.count + 1 : 1
      const best = Math.max(existing?.best ?? 0, count)
      const row = existing
        ? await db.conversationStreak.update({
            where: { id: existing.id },
            data: { lastDay: todayUTC, count, best },
          })
        : await db.conversationStreak.create({
            data: { conversationId: id, userId: senderId, lastDay: todayUTC, count, best },
          })
      streak = { count: row.count, best: row.best, continued }
    }
  } catch {
    // Streak accounting must never fail a send either.
  }

  // Additive response fields — existing clients keep reading `message`.
  return NextResponse.json(
    { message: mapped, ...(streak ? { streak } : {}), xpAwarded },
    { status: 201 },
  )
}

// ── R39: keyword-triggered auto-replies (ManyChat/Landbot family) ─────

/** Escape a trigger for literal regex use (no special-char surprises). */
function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Match `trigger` as a STANDALONE phrase inside `content`: case-insensitive,
 * with letter/digit lookaround boundaries so "price" never matches inside
 * "pricing" or "compare prices" — but "price" matches in "what's the price?".
 */
function triggerMatches(trigger: string, content: string): boolean {
  const escaped = escapeRegex(trigger.trim())
  if (escaped.length === 0) return false
  return new RegExp(`(?<![A-Za-z0-9])${escaped}(?![A-Za-z0-9])`, 'i').test(content)
}

/**
 * Fire the FIRST enabled automation whose trigger matches the human message.
 * The reply is a real Message row authored by the rule's creator with
 * viaAutomation=true, riding the same pipeline as webhook messages (updatedAt
 * bump, archive unpin, notifySocket to the other members). Best-effort by
 * contract: any failure is logged and swallowed — a broken automation must
 * never fail a human send. Disabled rules, non-matching content and rules in
 * conversations without any rows are all silent no-ops.
 */
async function maybeAutomationReply(conversationId: string, content: string): Promise<void> {
  try {
    const rules = await db.automation.findMany({
      where: { conversationId, enabled: true },
      orderBy: { createdAt: 'asc' },
      select: { id: true, trigger: true, reply: true, createdById: true },
    })
    const rule = rules.find((r) => triggerMatches(r.trigger, content))
    if (!rule) return

    const now = new Date()
    const conv = await db.conversation.findUnique({
      where: { id: conversationId },
      select: { ttlSeconds: true },
    })
    const expiresAt =
      conv && conv.ttlSeconds > 0 ? new Date(now.getTime() + conv.ttlSeconds * 1000) : null

    const created = await db.$transaction(async (tx) => {
      const msg = await tx.message.create({
        data: {
          conversationId,
          senderId: rule.createdById,
          content: rule.reply,
          kind: 'text',
          viaAutomation: true,
          ...(expiresAt ? { expiresAt } : {}),
        },
        include: MESSAGE_FULL_INCLUDE,
      })
      await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
      await tx.conversationParticipant.updateMany({
        where: {
          conversationId,
          userId: { not: rule.createdById },
          archivedAt: { not: null },
        },
        data: { archivedAt: null },
      })
      return msg
    })

    // Unlike an interactive send (sender handles self via the POST response),
    // an automation reply is machine-sent on the creator's behalf — NO member
    // has a response carrying it, so EVERY participant (author included)
    // receives the realtime event. Same notify/emit shape as the webhook path.
    const recipients = await memberIdsOf(conversationId)
    await notifySocket('message:new', recipients, {
      type: 'message:new',
      message: mapMessage(created),
      recipientIds: recipients,
      conversationId,
    })

    // Atomic counters — updateMany keeps the bump single-statement.
    await db.automation.updateMany({
      where: { id: rule.id },
      data: { hits: { increment: 1 }, lastFiredAt: now },
    })
  } catch (error) {
    // Automation accounting must never fail a send.
    console.error('[automations] auto-reply failed:', error)
  }
}

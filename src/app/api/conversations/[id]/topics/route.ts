// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/topics — Zulip-style topic rail (R24-b)
// "Time is a terrible information architecture" — topics give busy
// groups lightweight sub-streams WITHOUT splitting the member graph.
// General is implicit (message.topicId === null) and lives client-side;
// this route only manages real Topic rows.
//
// Contracts:
//   GET  ?userId=
//        → participant-guarded (404 unknown room / 403 non-member)
//        → { topics: TopicSummary[] } — TopicSummary = {
//            id, name, emoji, lastMessageAt: ISO, messageCount }
//          messageCount is a REAL db.message.count({ conversationId,
//          topicId, deletedAt: null }); sorted lastMessageAt desc.
//   POST { userId, name (1..32 trimmed), emoji? (default '💬') }
//        → member-guarded; case-insensitive dedupe — when the name
//          already exists the EXISTING row comes back with 200,
//          a fresh row is 201 TopicSummary.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const NAME_MIN = 1
const NAME_MAX = 32
const EMOJI_MAX = 12

/** Wire shape — mirrors TopicSummary in src/lib/types.ts. */
function serializeTopic(row: {
  id: string
  name: string
  emoji: string
  lastMessageAt: Date
}, messageCount: number) {
  return {
    id: row.id,
    name: row.name,
    emoji: row.emoji,
    lastMessageAt: row.lastMessageAt.toISOString(),
    messageCount,
  }
}

export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
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

  const topics = await db.topic.findMany({
    where: { conversationId: id },
    orderBy: { lastMessageAt: 'desc' },
  })

  // Real per-topic filed-message counts (soft-deleted rows excluded).
  const withCounts = await Promise.all(
    topics.map(async (row) => ({
      row,
      messageCount: await db.message.count({
        where: { conversationId: id, topicId: row.id, deletedAt: null },
      }),
    })),
  )

  withCounts.sort(
    (a, b) => b.row.lastMessageAt.getTime() - a.row.lastMessageAt.getTime(),
  )

  return NextResponse.json(
    { topics: withCounts.map(({ row, messageCount }) => serializeTopic(row, messageCount)) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const name = strField(body.name)
  if (name.length < NAME_MIN || name.length > NAME_MAX) {
    return NextResponse.json(
      { error: `name must be ${NAME_MIN}-${NAME_MAX} characters.` },
      { status: 400 },
    )
  }
  const emojiRaw = strField(body.emoji)
  const emoji = emojiRaw.length > 0 ? emojiRaw : '💬'
  if (emoji.length > EMOJI_MAX) {
    return NextResponse.json(
      { error: `emoji must be ${EMOJI_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
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

  // Case-insensitive dedupe: return the existing row with 200 (idempotent).
  const existing = (await db.topic.findMany({ where: { conversationId: id } })).find(
    (row) => row.name.toLowerCase() === name.toLowerCase(),
  )
  if (existing) {
    return NextResponse.json({ topic: serializeTopic(existing, 0) }, { status: 200 })
  }

  const row = await db.topic.create({
    data: { conversationId: id, name, emoji, createdById: userId },
  })

  return NextResponse.json({ topic: serializeTopic(row, 0) }, { status: 201 })
}

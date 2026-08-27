// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/messages — history + send
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  parseIsoDate,
  MESSAGES_DEFAULT_LIMIT,
  MESSAGES_MAX_LIMIT,
  MESSAGE_MAX,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/conversations/[id]/messages?limit=200&after=<ISO>
 * → { messages: ChatMessage[] } ascending by createdAt.
 * Soft-deleted rows are included (client renders tombstones).
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const conv = await db.conversation.findUnique({ where: { id }, select: { id: true } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

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

  let after: Date | undefined
  const afterRaw = url.searchParams.get('after')
  if (afterRaw) {
    const parsed = parseIsoDate(afterRaw)
    if (!parsed) {
      return NextResponse.json(
        { error: 'after must be a valid ISO date string.' },
        { status: 400 },
      )
    }
    after = parsed
  }

  const messages = await db.message.findMany({
    where: {
      conversationId: id,
      ...(after ? { createdAt: { gt: after } } : {}),
    },
    orderBy: { createdAt: 'asc' },
    take: limit,
    include: { sender: true },
  })

  return NextResponse.json({ messages: messages.map(mapMessage) })
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
  if (!content) {
    return NextResponse.json({ error: 'Message content cannot be empty.' }, { status: 400 })
  }
  if (content.length > MESSAGE_MAX) {
    return NextResponse.json(
      { error: `Message must be ${MESSAGE_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  // Existence + membership checks up-front → clean 404 / 403 semantics.
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: senderId, conversationId: id } },
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

  const now = new Date()
  const message = await db.$transaction(async (tx) => {
    const created = await tx.message.create({
      data: { conversationId: id, senderId, content },
      include: { sender: true },
    })
    // Bump list ordering — @updatedAt allows an explicit value write.
    await tx.conversation.update({ where: { id }, data: { updatedAt: now } })
    // Sender obviously read their own message.
    await tx.conversationParticipant.update({
      where: { userId_conversationId: { userId: senderId, conversationId: id } },
      data: { lastReadAt: now },
    })
    return created
  })

  // Realtime relay to every OTHER member (sender handles self via response).
  const recipients = (await memberIdsOf(id)).filter((memberId) => memberId !== senderId)
  const mapped = mapMessage(message)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapped,
    recipientIds: recipients,
    conversationId: id,
  })

  return NextResponse.json({ message: mapped }, { status: 201 })
}

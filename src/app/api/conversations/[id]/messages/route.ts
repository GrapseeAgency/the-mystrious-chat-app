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
    include: MESSAGE_FULL_INCLUDE,
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
  if (!content && !imagePath) {
    return NextResponse.json(
      { error: 'Message needs text content or an image.' },
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
      data: {
        conversationId: id,
        senderId,
        content,
        ...(replyToId ? { replyToId } : {}),
        ...(imagePath ? { imagePath } : {}),
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

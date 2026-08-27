// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/poll — create a live-poll message
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  safeJson,
  strField,
  MESSAGE_FULL_INCLUDE,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const POLL_QUESTION_MAX = 140
const POLL_OPTION_MAX = 80

/**
 * POST /api/conversations/[id]/poll  body { senderId, question, options: string[] }
 * → 201 { message: ChatMessage } (message.poll populated) · relays message:new.
 * Broadcast mode still applies (admin-only while enabled).
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const senderId = strField(body.senderId)
  if (!senderId) {
    return NextResponse.json({ error: 'senderId is required.' }, { status: 400 })
  }
  const question = strField(body.question)
  if (question.length === 0 || question.length > POLL_QUESTION_MAX) {
    return NextResponse.json(
      { error: `Poll question must be 1-${POLL_QUESTION_MAX} characters.` },
      { status: 400 },
    )
  }
  if (!Array.isArray(body.options)) {
    return NextResponse.json({ error: 'options must be an array of strings.' }, { status: 400 })
  }
  const options = body.options
    .map((o) => (typeof o === 'string' ? o.trim().slice(0, POLL_OPTION_MAX) : ''))
    .filter((o) => o.length > 0)
  if (options.length < 2 || options.length > 6) {
    return NextResponse.json({ error: 'A poll needs between 2 and 6 options.' }, { status: 400 })
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
      { error: 'Only admins can post polls while announcement mode is on.' },
      { status: 403 },
    )
  }

  const now = new Date()
  const created = await db.$transaction(async (tx) => {
    const message = await tx.message.create({
      data: { conversationId: id, senderId, content: '' },
    })
    await tx.poll.create({
      data: {
        messageId: message.id,
        question,
        options: {
          createMany: {
            data: options.map((text, position) => ({ text, position })),
          },
        },
      },
    })
    await tx.conversation.update({ where: { id }, data: { updatedAt: now } })
    return tx.message.findUnique({ where: { id: message.id }, include: MESSAGE_FULL_INCLUDE })
  })
  if (!created) {
    return NextResponse.json({ error: 'Poll could not be created.' }, { status: 500 })
  }

  const recipients = (await memberIdsOf(id)).filter((memberId) => memberId !== senderId)
  const mapped = mapMessage(created, senderId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapped,
    recipientIds: recipients,
    conversationId: id,
  })

  return NextResponse.json({ message: mapped }, { status: 201 })
}

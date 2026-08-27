// ─────────────────────────────────────────────────────────────
// /api/polls/[id]/vote — cast or move a single-choice vote
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

/**
 * POST /api/polls/[id]/vote  body { userId, optionId }
 * → 200 { message: ChatMessage } · relays poll:voted with the fresh tally.
 * Single choice per voter: any previous vote on this poll is replaced.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  const optionId = strField(body.optionId)
  if (!userId || !optionId) {
    return NextResponse.json({ error: 'userId and optionId are required.' }, { status: 400 })
  }

  const poll = await db.poll.findUnique({
    where: { id },
    select: {
      closedAt: true,
      message: { select: { id: true, conversationId: true, senderId: true } },
      options: { select: { id: true } },
    },
  })
  if (!poll) {
    return NextResponse.json({ error: 'Poll not found.' }, { status: 404 })
  }
  if (poll.closedAt !== null) {
    return NextResponse.json({ error: 'This poll has ended — votes are frozen.' }, { status: 400 })
  }
  if (!poll.options.some((o) => o.id === optionId)) {
    return NextResponse.json({ error: 'optionId does not belong to this poll.' }, { status: 400 })
  }

  const membership = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: poll.message.conversationId } },
    select: { id: true },
  })
  if (!membership) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  await db.$transaction([
    // move-the-vote semantics for revotes
    db.pollVote.deleteMany({ where: { pollId: id, userId } }),
    db.pollVote.create({ data: { pollId: id, userId, optionId } }),
  ])

  const fresh = await db.message.findUnique({
    where: { id: poll.message.id },
    include: MESSAGE_FULL_INCLUDE,
  })
  if (!fresh) {
    return NextResponse.json({ error: 'Poll message vanished.' }, { status: 404 })
  }

  const mapped = mapMessage(fresh, userId)
  const recipients = (await memberIdsOf(poll.message.conversationId)).filter(
    (memberId) => memberId !== userId,
  )
  await notifySocket('poll:voted', recipients, {
    type: 'poll:voted',
    message: mapped,
    recipientIds: recipients,
    conversationId: poll.message.conversationId,
  })

  return NextResponse.json({ message: mapped })
}

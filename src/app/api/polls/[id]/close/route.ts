// ─────────────────────────────────────────────────────────────
// /api/polls/[id]/close — end voting (creator only)
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
 * POST /api/polls/[id]/close  body { userId }
 * → 200 { message: ChatMessage } · relays poll:voted (frozen tally).
 * Only the poll's creator (the poll message sender) may close.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const poll = await db.poll.findUnique({
    where: { id },
    select: { closedAt: true, message: { select: { id: true, conversationId: true, senderId: true } } },
  })
  if (!poll) {
    return NextResponse.json({ error: 'Poll not found.' }, { status: 404 })
  }
  if (poll.message.senderId !== userId) {
    return NextResponse.json({ error: 'Only the poll creator can close it.' }, { status: 403 })
  }

  if (poll.closedAt === null) {
    await db.poll.update({ where: { id }, data: { closedAt: new Date() } })
  }

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

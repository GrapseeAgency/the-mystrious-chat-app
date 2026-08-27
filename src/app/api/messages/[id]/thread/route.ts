// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/thread — Slack/Zulip-style thread reader
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  MESSAGE_FULL_INCLUDE,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/messages/[id]/thread?userId=X
 * → { parent: ChatMessage, replies: ChatMessage[] }
 * `id` is the thread ROOT (a top-level message). Participant-guarded.
 * Replies arrive ascending; each already carries full sender/reactions etc.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const root = await db.message.findUnique({
    where: { id },
    include: MESSAGE_FULL_INCLUDE,
  })
  if (!root) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: root.conversationId } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const replies = await db.message.findMany({
    where: { parentId: id },
    orderBy: { createdAt: 'asc' },
    include: MESSAGE_FULL_INCLUDE,
  })

  return NextResponse.json({
    parent: mapMessage(root, userId),
    replies: replies.map((m) => mapMessage(m, userId)),
  })
}

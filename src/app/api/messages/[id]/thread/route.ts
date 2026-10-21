// /api/messages/[id]/thread - Slack/Zulip-style thread reader
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { readThreadSnapshot, ThreadReadError } from '@/lib/message-visibility'
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

  try {
    const page = await readThreadSnapshot(db, id, userId,
      (tx, rootId) => tx.message.findUnique({ where: { id: rootId }, include: MESSAGE_FULL_INCLUDE }),
      (tx, where) => tx.message.findMany({ where, orderBy: [{ createdAt: 'asc' }, { id: 'asc' }], include: MESSAGE_FULL_INCLUDE }),
    )
    return NextResponse.json({ parent: mapMessage(page.parent, userId), replies: page.replies.map(message => mapMessage(message, userId)) })
  } catch (error) {
    if (error instanceof ThreadReadError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

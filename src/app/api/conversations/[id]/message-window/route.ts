import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionActor } from '@/lib/wallet-authorization'
import { readMessageWindow, MessageWindowError } from '@/lib/message-window'
import { MESSAGE_FULL_INCLUDE, mapMessage } from '@/lib/serializers'
export const dynamic = 'force-dynamic'
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const actor = await requireActionActor(req, 'message navigation')
  if (actor instanceof Response) return actor
  const query = new URL(req.url).searchParams
  const headers = { 'Cache-Control': 'private, no-store' }
  try {
    const position = (key: string) => {
      const createdAt = query.get(key), id = query.get(`${key}Id`)
      if (createdAt === null && id === null) return undefined
      if (!createdAt || !id) throw new MessageWindowError(400, 'Both timestamp and identity are required.')
      return { createdAt, id }
    }
    const anchorId = query.get('messageId') ?? undefined, threadId = query.get('threadId') ?? undefined
    if (anchorId === '' || threadId === '') throw new MessageWindowError(400, 'Empty identity.')
    const page = await readMessageWindow(db, id, actor.userId, { anchorId, threadId, before: position('before'), after: position('after'),
      limit: query.has('limit') ? Number(query.get('limit')) : undefined },
      (tx, where, order, take) => tx.message.findMany({ where, orderBy: [{ createdAt: order }, { id: order }], take,
        include: { ...MESSAGE_FULL_INCLUDE, parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } } } }))
    return NextResponse.json({ ...page, parent: page.parent ? mapMessage(page.parent, actor.userId) : null,
      messages: page.messages.map(row => mapMessage(row, actor.userId)) }, { headers })
  } catch (error) {
    if (error instanceof MessageWindowError) return NextResponse.json({ error: error.message, code: 'message_window_unavailable' }, { status: error.status, headers })
    throw error
  }
}

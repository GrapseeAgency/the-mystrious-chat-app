import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { readThreadPage } from '@/lib/thread-page'
import { ThreadReadError } from '@/lib/message-visibility'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const url = new URL(req.url), userId = url.searchParams.get('userId')
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'thread')
    if (denied) return denied
  }
  try {
    const page = await readThreadPage(db, id, userId, Number(url.searchParams.get('limit') ?? 100), url.searchParams.get('cursor'),
      (tx, rootId) => tx.message.findUnique({ where: { id: rootId }, include: MESSAGE_FULL_INCLUDE }),
      (tx, where, take) => tx.message.findMany({ where, take, orderBy: [{ createdAt: 'asc' }, { id: 'asc' }], include: MESSAGE_FULL_INCLUDE }))
    return NextResponse.json({ ...page, parent: mapMessage(page.parent, userId), replies: page.replies.map(row => mapMessage(row, userId)) })
  } catch (error) {
    if (error instanceof ThreadReadError) return NextResponse.json({ error: error.message,
      code: error.status === 404 ? 'thread_missing' : error.status === 403 ? 'thread_access' : error.status === 410 ? 'thread_gone' : 'thread_page' }, { status: error.status })
    throw error
  }
}

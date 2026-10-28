import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionActor } from '@/lib/wallet-authorization'
import { readExportPage } from '@/lib/history-export'
import { MessageWindowError } from '@/lib/message-window'
import { MESSAGE_FULL_INCLUDE, mapMessage } from '@/lib/serializers'
export const dynamic = 'force-dynamic'
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const actor = await requireActionActor(req, 'history export')
  if (actor instanceof Response) return actor
  const { id } = await params
  const headers = { 'Cache-Control': 'private, no-store' }
  try {
    const page = await readExportPage(db, id, actor.userId, new URL(req.url).searchParams.get('cursor'),
      (tx, where, take) => tx.message.findMany({ where, take, orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
        include: { ...MESSAGE_FULL_INCLUDE, parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } } } }))
    return NextResponse.json({ ...page, messages: page.messages.map(row => mapMessage(row, actor.userId)) }, { headers })
  } catch (error) {
    if (error instanceof MessageWindowError) return NextResponse.json({ error: error.message }, { status: error.status, headers })
    throw error
  }
}

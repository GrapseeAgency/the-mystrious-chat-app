// /api/conversations/[id]/pinned - list pinned messages
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { inboxMessagesWhere, visibleInboxPreview } from '@/lib/message-visibility'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/conversations/[id]/pinned?userId=
 * Participant-guarded list of pinned messages (oldest pin first).
 * → { messages: ChatMessage[] } - 403 for non-participants.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'pinned messages')
    if (denied) return denied
  }
  return db.$transaction(async tx => {
    const participant = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
      select: { id: true },
    })
    if (!participant) {
      return NextResponse.json(
        { error: 'You are not a participant of this conversation.' },
        { status: 403 },
      )
    }

    const rows = await tx.message.findMany({
      where: { ...inboxMessagesWhere(id, new Date()), pinnedAt: { not: null }, deletedAt: null, viewOnce: false },
      orderBy: { pinnedAt: 'asc' },
      include: { ...MESSAGE_FULL_INCLUDE, parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } } },
    })

    const deliveredAt = new Date()
    return NextResponse.json({ messages: rows.filter(m => visibleInboxPreview(m, id, deliveredAt) !== null).map((m) => mapMessage(m, userId)) })
  }, { isolationLevel: 'Serializable' })
}

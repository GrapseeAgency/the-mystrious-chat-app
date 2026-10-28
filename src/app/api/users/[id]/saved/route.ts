// /api/users/[id]/saved - saved/starred messages library
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { savedMessagesWhere, visibleInboxPreview } from '@/lib/message-visibility'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'

import { scanFeed, feedScope, feedBounds, FeedCursorError } from '@/lib/feed-pagination'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/users/[id]/saved → { items: SavedItem[] } (newest first, 100 per page)
 * Each item carries its message + resolved conversation display info
 * (DMs resolve to the partner's name server-side).
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: userId } = await params

  const denied = await requireActionOwner(req, userId, 'saved messages')
  if (denied) return denied
  const headers = { 'Cache-Control': 'private, no-store' }
  try {
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const items = await db.$transaction(async tx => {
    const now = new Date()
    const page = await scanFeed({ cursor: new URL(req.url).searchParams.get('cursor'), scope: feedScope('saved', userId), limit: 100,
      load: (before, upper, take) => tx.savedMessage.findMany({
      where: { AND: [savedMessagesWhere(userId, now), ...feedBounds(before, upper, 'savedAt', 'messageId')] },
      orderBy: [{ savedAt: 'desc' }, { messageId: 'desc' }], take,
      include: {
        message: {
          include: {
            ...MESSAGE_FULL_INCLUDE,
            parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
            conversation: {
              select: {
                id: true,
                isGroup: true,
                name: true,
                participants: { select: { userId: true, user: { select: { name: true } } } },
              },
            },
          },
        },
      },
    }),
    position: row => ({ id: row.messageId, at: row.savedAt.toISOString() }),
    project: row => {
      const visible = visibleInboxPreview(row.message, row.message.conversationId, new Date())
      if (!visible) return null
      const conv = row.message.conversation
      const partner =
        !conv.isGroup && conv.name === null
          ? (conv.participants.find((p) => p.userId !== userId)?.user.name ?? null)
          : null
      return {
        savedAt: row.savedAt.toISOString(),
        conversation: {
          id: conv.id,
          isGroup: conv.isGroup,
          name: conv.isGroup ? conv.name : (partner ?? conv.name ?? 'Direct message'),
        },
        message: mapMessage(visible, userId),
      }
    } })
    const states = await tx.savedMessageState.findMany({ where: { userId, messageId: { in: page.items.map(item => item.message.id) } } })
    const revisions = new Map(states.map(state => [state.messageId, state.revision]))
    return { ...page, items: page.items.map(item => ({ ...item, revision: revisions.get(item.message.id) ?? '0' })) }
  }, { isolationLevel: 'Serializable', timeout: 15000 })
  return NextResponse.json(items, { headers })
  } catch (error) {
    if (error instanceof FeedCursorError) return NextResponse.json({ error: error.message }, { status: 400, headers })
    throw error
  }
}

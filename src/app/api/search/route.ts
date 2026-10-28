// Unicode-insensitive substring search, exhaustively navigable by scan cursor.
import { NextResponse } from 'next/server'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { db } from '@/lib/db'
import { visibleSearchHit } from '@/lib/search-visibility'
import { unexpiredMessages, visibleInboxPreview } from '@/lib/message-visibility'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
import { scanFeed, feedScope, readFeedBatch, FeedCursorError } from '@/lib/feed-pagination'
export const dynamic = 'force-dynamic'
const headers = { 'Cache-Control': 'private, no-store' }
export async function GET(req: Request) {
  const query = new URL(req.url).searchParams
  const userId = (query.get('userId') ?? '').trim(), q = (query.get('q') ?? '').trim()
  if (!userId || !q || q.length > 64) return NextResponse.json({ error: 'A user and search text of 1–64 UTF-16 units are required.' }, { status: 400, headers })
  const denied = await requireActionOwner(req, userId, 'message search')
  if (denied) return denied
  try {
    const page = await db.$transaction(async tx => {
      const now = new Date(), needle = q.toLowerCase()
      return scanFeed({ cursor: query.get('cursor'), scope: feedScope('search', userId, needle), limit: 30,
        load: (before, upper, take) => readFeedBatch(before, upper, take, (range, take) => tx.message.findMany({
          where: { deletedAt: null, viewOnce: false, ...unexpiredMessages(now),
            conversation: { participants: { some: { userId } } },
            AND: [{ createdAt: range.time, id: range.id }, { OR: [{ parentId: null }, { parent: { is: { parentId: null, deletedAt: null, ...unexpiredMessages(now) } } }] }],
          }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take,
          include: { ...MESSAGE_FULL_INCLUDE,
            parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
            conversation: { select: { name: true, isGroup: true, participants: { where: { userId: { not: userId } }, take: 1, select: { user: { select: { name: true } } } } } },
          },
        })), position: row => ({ id: row.id, at: row.createdAt.toISOString() }),
        project: row => {
          if (!(row.content.toLowerCase().includes(needle) || (row.fileName ?? '').toLowerCase().includes(needle))) return null
          const visible = visibleInboxPreview(row, row.conversationId, new Date())
          if (!visible) return null
          return visibleSearchHit({ ...mapMessage(visible, userId),
            conversationName: row.conversation.name?.trim() || (row.conversation.isGroup ? 'Group' : row.conversation.participants[0]?.user.name ?? 'Chat'),
            isGroup: row.conversation.isGroup }, needle)
        },
      })
    }, { isolationLevel: 'Serializable', timeout: 15000 })
    const { items: messages, ...paging } = page
    return NextResponse.json({ messages, total: messages.length, ...paging }, { headers })
  } catch (error) {
    if (error instanceof FeedCursorError) return NextResponse.json({ error: error.message }, { status: 400, headers })
    throw error
  }
}

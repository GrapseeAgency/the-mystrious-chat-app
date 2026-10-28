// All historical mentions, with bounded scans and explicit continuation.
import { NextResponse } from 'next/server'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { unexpiredMessages, visibleInboxPreview } from '@/lib/message-visibility'
import { clipUtf16 } from '@/lib/text-limits'
import { db } from '@/lib/db'
import { scanFeed, feedScope, readFeedBatch, FeedCursorError } from '@/lib/feed-pagination'
export const dynamic = 'force-dynamic'
const headers = { 'Cache-Control': 'private, no-store' }
export async function GET(req: Request) {
  const query = new URL(req.url).searchParams, userId = query.get('userId')?.trim()
  const rawLimit = query.get('limit') ?? '50', limit = Number(rawLimit)
  if (!userId || !/^\d+$/.test(rawLimit) || !Number.isInteger(limit) || limit < 1 || limit > 100) {
    return NextResponse.json({ error: 'A user and page size between 1 and 100 are required.' }, { status: 400, headers })
  }
  const denied = await requireActionOwner(req, userId, 'mentions')
  if (denied) return denied
  try {
    const page = await db.$transaction(async tx => {
      const me = await tx.user.findUnique({ where: { id: userId }, select: { name: true } })
      if (!me) return null
      const name = me.name.normalize('NFC')
      const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const pattern = new RegExp(`@${escaped}(?![\\p{L}\\p{N}\\p{M}_\\u200c\\u200d])`, 'iu'), now = new Date()
      return scanFeed({ cursor: query.get('cursor'), scope: feedScope('mentions', userId, `unicode-v1:${name}`), limit,
        load: (before, upper, take) => readFeedBatch(before, upper, take, (range, take) => tx.message.findMany({
          where: { deletedAt: null, viewOnce: false, senderId: { not: userId }, content: { contains: '@' }, ...unexpiredMessages(now),
            conversation: { participants: { some: { userId } } },
            AND: [{ createdAt: range.time, id: range.id }, { OR: [{ parentId: null }, { parent: { is: { parentId: null, deletedAt: null, ...unexpiredMessages(now) } } }] }],
          }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take,
          select: { id: true, conversationId: true, content: true, createdAt: true, expiresAt: true, parentId: true, anon: true, anonAlias: true,
            parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
            sender: { select: { id: true, name: true, color: true, avatar: true } }, conversation: { select: { isGroup: true, name: true } } },
        })), position: row => ({ id: row.id, at: row.createdAt.toISOString() }),
        project: row => {
          const m = visibleInboxPreview(row, row.conversationId, new Date())
          if (!m || !m.content || !pattern.test(m.content.normalize('NFC'))) return null
          return { messageId: m.id, conversationId: m.conversationId, conversationName: m.conversation.name, isGroup: m.conversation.isGroup,
            author: m.anon ? { id: '', name: m.anonAlias || 'Anonymous', color: 'orange', avatar: null } : m.sender,
            snippet: clipUtf16(m.content, 160), expiresAt: m.expiresAt?.toISOString() ?? null, createdAt: m.createdAt.toISOString() }
        },
      })
    }, { isolationLevel: 'Serializable', timeout: 15000 })
    return page ? NextResponse.json(page, { headers }) : NextResponse.json({ error: 'User not found.' }, { status: 404, headers })
  } catch (error) {
    if (error instanceof FeedCursorError) return NextResponse.json({ error: error.message }, { status: 400, headers })
    throw error
  }
}

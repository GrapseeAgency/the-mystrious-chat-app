// /api/search - global message search across ALL of a user's
// conversations. Case-insensitive substring on non-deleted text
// content AND document fileName (R41 - a kind 'file' message also
// matches when its original filename contains the query; SQLite has
// no ICU collation → match happens in Node).
// GET /api/search?userId=<id>&q=<text>
//   → { messages: SearchResultMessage[], total: number }
import { NextResponse } from 'next/server'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { db } from '@/lib/db'
import { visibleSearchHit } from '@/lib/search-visibility'
import { unexpiredMessages, visibleInboxPreview } from '@/lib/message-visibility'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
import type { SearchResultMessage } from '@/lib/types'

export const dynamic = 'force-dynamic'

/** How many newest rows we scan per query round (bounded work). */
const SCAN_WINDOW = 1200
/** Hard cap on returned hits. */
const RESULT_CAP = 30
const Q_MAX = 64

export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = (url.searchParams.get('userId') ?? '').trim()
  const q = (url.searchParams.get('q') ?? '').trim()

  if (userId.length === 0) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (q.length === 0) {
    return NextResponse.json({ error: 'q (search text) is required.' }, { status: 400 })
  }

  const denied = await requireActionOwner(req, userId, 'message search')
  if (denied) return denied
  if (q.length > Q_MAX) return NextResponse.json({ error: `Search text must be ${Q_MAX} UTF-16 units or fewer.` }, { status: 400 })

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  // Every conversation the requester participates in.
  const participations = await db.conversationParticipant.findMany({
    where: { userId },
    select: {
      conversationId: true,
      conversation: {
        select: {
          isGroup: true,
          name: true,
          participants: { select: { user: { select: { id: true, name: true } } } },
        },
      },
    },
  })
  if (participations.length === 0) {
    return NextResponse.json({ messages: [], total: 0 }, { headers: { 'Cache-Control': 'private, no-store' } })
  }

  // Resolve a display title per conversation (group name / DM partner name).
  const titleByConv = new Map<string, string>()
  const groupByConv = new Map<string, boolean>()
  for (const p of participations) {
    titleByConv.set(p.conversationId, p.conversation.name?.trim() || 'Group')
    groupByConv.set(p.conversationId, p.conversation.isGroup)
    if (!p.conversation.isGroup) {
      const other = p.conversation.participants.find((m) => m.user.id !== userId)
      titleByConv.set(p.conversationId, other?.user.name ?? 'Chat')
    }
  }

  const needle = q.toLowerCase()
  const convIds = participations.map((p) => p.conversationId)

  // Scan the newest rows once (bounded), filter in Node, keep the best hits.
  const now = new Date()
  const rows = await db.message.findMany({
    where: { conversationId: { in: convIds }, deletedAt: null, viewOnce: false, ...unexpiredMessages(now),
      conversation: { participants: { some: { userId } } },
      AND: [{ OR: [{ parentId: null }, { parent: { is: { parentId: null, deletedAt: null, ...unexpiredMessages(now) } } }] }],
    },
    orderBy: { createdAt: 'desc' },
    take: SCAN_WINDOW,
    include: { ...MESSAGE_FULL_INCLUDE, parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } } },
  })

  const messages: SearchResultMessage[] = []
  for (const row of rows) {
    // Avoid serializing up to 1,200 rich messages when only 30 previews are needed.
    if (messages.length === RESULT_CAP) break
    if (row.viewOnce || !(row.content.toLowerCase().includes(needle) || (row.fileName ?? '').toLowerCase().includes(needle))) continue
    const visible = visibleInboxPreview(row, row.conversationId, new Date())
    if (!visible) continue
    const hit = visibleSearchHit({
      ...mapMessage(visible, userId),
      conversationName: titleByConv.get(row.conversationId) ?? 'Chat',
      isGroup: groupByConv.get(row.conversationId) ?? false,
    }, needle)
    if (hit) messages.push(hit)
  }

  return NextResponse.json({ messages, total: messages.length }, { headers: { 'Cache-Control': 'private, no-store' } })
}

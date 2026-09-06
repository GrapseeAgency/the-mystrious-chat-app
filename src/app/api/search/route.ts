// ─────────────────────────────────────────────────────────────
// /api/search — global message search across ALL of a user's
// conversations. Case-insensitive substring on non-deleted text
// content AND document fileName (R41 — a kind 'file' message also
// matches when its original filename contains the query; SQLite has
// no ICU collation → match happens in Node).
// GET /api/search?userId=<id>&q=<text>
//   → { messages: SearchResultMessage[], total: number }
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
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
  const q = (url.searchParams.get('q') ?? '').trim().slice(0, Q_MAX)

  if (userId.length === 0) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (q.length === 0) {
    return NextResponse.json({ error: 'q (search text) is required.' }, { status: 400 })
  }

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
    return NextResponse.json({ messages: [], total: 0 })
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
  const rows = await db.message.findMany({
    where: { conversationId: { in: convIds }, deletedAt: null },
    orderBy: { createdAt: 'desc' },
    take: SCAN_WINDOW,
    include: MESSAGE_FULL_INCLUDE,
  })

  const hits = rows
    .filter(
      (row) =>
        (row.content.length > 0 && row.content.toLowerCase().includes(needle)) ||
        // R41 — document fileName is a first-class match field (same
        // case-insensitive substring rule as content).
        (row.fileName !== null && row.fileName.length > 0 && row.fileName.toLowerCase().includes(needle)),
    )
    .slice(0, RESULT_CAP)

  const messages: SearchResultMessage[] = hits.map((row) => ({
    ...mapMessage(row),
    conversationName: titleByConv.get(row.conversationId) ?? 'Chat',
    isGroup: groupByConv.get(row.conversationId) ?? false,
  }))

  return NextResponse.json({ messages, total: hits.length })
}

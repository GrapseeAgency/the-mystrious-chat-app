// ─────────────────────────────────────────────────────────────
// /api/mentions — Discord mobile "Mentions" feed (R35-b).
//
// GET ?userId=X&limit=50 → 200 { items: MentionItem[] } newest first:
//   { messageId, conversationId, conversationName: string|null, isGroup,
//     author: { id, name, color, avatar: string|null },
//     snippet: string (≤160 chars), createdAt: ISO }
//
// A message is a MENTION of viewer X when X participates in the conversation,
// the message is not deleted, someone ELSE sent it within the last 14 days,
// and its text mentions X by display name. Exact matching rule (also mirrored
// client-side in mentions-page.tsx — keep the two in sync):
//
//   new RegExp(`@${escapeRegex(me.name)}(?=\\s|$|[^A-Za-z0-9])`, 'i')
//
//   • an '@' immediately followed by the user's FULL display name,
//   • matched case-insensitively ("@alice" mentions "Alice"),
//   • the character right after the name must be whitespace, end-of-string,
//     or any non-alphanumeric char — so "@AliceChen" NEVER mentions "Alice"
//     while "@Alice," / "@Alice!" / "@Alice" (end) all do,
//   • applied in JS: SQLite's Prisma `contains` cannot do per-message
//     case-insensitive + boundary matching (and `mode: 'insensitive'` is
//     unsupported on SQLite), so the DB query only fetches a pragmatic
//     superset (content contains '@') and this regex decides.
//
// Scale assumptions (honest): per conversation we scan at most the 200 most
// recent '@'-containing messages of the 14-day window (index
// Message@@index([conversationId, createdAt]) makes this a cheap tail scan).
// Rooms with more than 200 '@'-bearing messages in 14 days may miss older
// mentions — acceptable for a mentions inbox; no full-table scans.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

const DEFAULT_LIMIT = 50
const MAX_LIMIT = 100
const WINDOW_DAYS = 14
const PER_CONVERSATION_SUPERSET = 200
const SNIPPET_MAX_CHARS = 160

/** Escape every regex metacharacter so user names match literally. */
function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId || userId.trim() === '') {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const rawLimit = url.searchParams.get('limit')
  let limit = DEFAULT_LIMIT
  if (rawLimit !== null) {
    if (!/^\d+$/.test(rawLimit)) {
      return NextResponse.json({ error: 'limit must be a positive integer.' }, { status: 400 })
    }
    const parsed = Number.parseInt(rawLimit, 10)
    if (parsed < 1 || parsed > MAX_LIMIT) {
      return NextResponse.json(
        { error: `limit must be between 1 and ${MAX_LIMIT}.` },
        { status: 400 },
      )
    }
    limit = parsed
  }

  const me = await db.user.findUnique({
    where: { id: userId },
    select: { id: true, name: true },
  })
  if (!me) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const participations = await db.conversationParticipant.findMany({
    where: { userId },
    select: { conversationId: true },
  })
  const conversationIds = participations.map((p) => p.conversationId)
  if (conversationIds.length === 0) {
    return NextResponse.json({ items: [] })
  }

  const since = new Date(Date.now() - WINDOW_DAYS * 24 * 3600 * 1000)
  const windows = await Promise.all(
    conversationIds.map((conversationId) =>
      db.message.findMany({
        where: {
          conversationId,
          deletedAt: null,
          senderId: { not: userId },
          createdAt: { gte: since },
          content: { contains: '@' },
        },
        orderBy: { createdAt: 'desc' },
        take: PER_CONVERSATION_SUPERSET,
        select: {
          id: true,
          conversationId: true,
          content: true,
          createdAt: true,
          sender: { select: { id: true, name: true, color: true, avatar: true } },
          conversation: { select: { isGroup: true, name: true } },
        },
      }),
    ),
  )

  const mentionPattern = new RegExp(`@${escapeRegex(me.name)}(?=\\s|$|[^A-Za-z0-9])`, 'i')

  const items = windows
    .flat()
    .filter((m) => m.content.length > 0 && mentionPattern.test(m.content))
    .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime()) // newest first
    .slice(0, limit)
    .map((m) => ({
      messageId: m.id,
      conversationId: m.conversationId,
      conversationName: m.conversation.name,
      isGroup: m.conversation.isGroup,
      author: {
        id: m.sender.id,
        name: m.sender.name,
        color: m.sender.color,
        avatar: m.sender.avatar,
      },
      snippet: m.content.slice(0, SNIPPET_MAX_CHARS),
      createdAt: m.createdAt.toISOString(),
    }))

  return NextResponse.json({ items })
}

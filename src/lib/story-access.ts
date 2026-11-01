import { Prisma } from '../../prisma/generated-client'

/** Trusted SQL expressions only. Request IDs are bound values, never SQL text. */
export function storyUnblocked(left: Prisma.Sql, right: Prisma.Sql): Prisma.Sql {
  return Prisma.sql`NOT EXISTS (
    SELECT 1 FROM UserBlock b WHERE
      (b.blockerId = ${left} AND b.blockedId = ${right}) OR
      (b.blockerId = ${right} AND b.blockedId = ${left})
  )`
}

/** A story's live audience: its owner, or an unblocked current conversation peer.
 * Use the same predicate for feed reads and view mutations, not just client filtering. */
export function storyAudience(requesterId: string, author: Prisma.Sql): Prisma.Sql {
  const viewer = Prisma.sql`${requesterId}`
  // IN (subquery), not an OR/correlated scan over all historical stories: SQLite
  // can still use StatusStory(userId, expiresAt), without a JS-sized bind list.
  return Prisma.sql`${author} IN (
    SELECT ${viewer} AS userId
    UNION
    SELECT peer.userId FROM ConversationParticipant mine
    JOIN ConversationParticipant peer ON peer.conversationId = mine.conversationId
    WHERE mine.userId = ${viewer} AND ${storyUnblocked(viewer, Prisma.sql`peer.userId`)}
  )`
}

export function privateStoryResponse(response: Response): Response {
  response.headers.set('Cache-Control', 'private, no-store')
  response.headers.set('Vary', 'Authorization')
  return response
}
export function storyJson(body: unknown, init?: ResponseInit): Response {
  return privateStoryResponse(Response.json(body, init))
}

// Section 9 item 2 - keyset pagination over (createdAt, id).
// SQLite stores story/view timestamps as INTEGER epoch ms (see the route
// header), so one opaque cursor token `${epochMs}.${id}` orders every page
// with zero wall-clock drift. Malformed or oversized tokens are rejected,
// never loosely parsed.

export interface StoryCursor {
  atMs: number
  id: string
}

const CURSOR_RE = /^\d{1,15}\.[A-Za-z0-9-]{1,64}$/

export function encodeStoryCursor(atMs: number | bigint, id: string): string {
  return `${Number(atMs)}.${id}`
}

export function parseStoryCursor(raw: string | null): StoryCursor | null {
  if (!raw) return null
  if (!CURSOR_RE.test(raw)) return null
  const dot = raw.indexOf('.')
  const atMs = Number(raw.slice(0, dot))
  if (!Number.isSafeInteger(atMs) || atMs < 0) return null
  return { atMs, id: raw.slice(dot + 1) }
}

/** Bounded page sizes: the feed walks story rows, the sheet walks viewers. */
export const STORY_FEED_PAGE_MAX = 60
export const STORY_VIEWERS_PAGE_MAX = 100

export function parseStoryPageLimit(raw: string | null, max: number): number | null {
  if (raw === null) return null // legacy: unpaginated caller
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 1 || n > max) return null
  return n
}

/** Story views disclose to the author only while the viewer allows receipts
 * (Section 9 item 3 - the live User.storyViewReceipts column). Every
 * disclosure surface (viewer list, both view-count queries) uses THIS
 * predicate so a list and its count can never disagree. */
export function storyReceiptDisclosed(viewer: Prisma.Sql): Prisma.Sql {
  return Prisma.sql`${viewer} IN (SELECT id FROM User WHERE storyViewReceipts = 1)`
}

/** Section 9 item 8 (revocation tracing): a receipt also discloses only while
 * the viewer is STILL in the author's current audience (shared conversation +
 * unblocked). A removed or blocked viewer cannot stay visible to an owner
 * whose story they can no longer even open. Same predicate on every surface. */
export function storyViewerInAuthorAudience(author: Prisma.Sql, viewer: Prisma.Sql): Prisma.Sql {
  return Prisma.sql`${viewer} IN (
    SELECT peer.userId FROM ConversationParticipant mine
    JOIN ConversationParticipant peer ON peer.conversationId = mine.conversationId
    WHERE mine.userId = ${author} AND ${storyUnblocked(viewer, Prisma.sql`peer.userId`)}
  )`
}

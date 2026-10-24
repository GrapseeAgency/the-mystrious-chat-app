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

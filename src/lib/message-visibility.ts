import type { Prisma } from '../../prisma/generated-client'

/** Evaluate per request, not once when the server module loads. */
export function unexpiredMessages(at: Date): Prisma.MessageWhereInput {
  return { OR: [{ expiresAt: null }, { expiresAt: { gt: at } }] }
}
/** Tombstones may remain as redacted previews, but inaccessible replies may not. */
export function inboxMessagesWhere(room: string, at: Date): Prisma.MessageWhereInput {
  return { conversationId: room, ...unexpiredMessages(at),
    AND: [{ OR: [{ parentId: null }, { parent: { is: {
      conversationId: room, parentId: null, deletedAt: null, ...unexpiredMessages(at),
    } } }] }],
  }
}
export function unreadMessages(room: string, viewer: string, readAt: Date, at: Date): Prisma.MessageWhereInput {
  // An integration may use a member's senderId without being that member's
  // outgoing action. Only the persisted server flag changes unread semantics.
  return { AND: [inboxMessagesWhere(room, at), { OR: [{ senderId: { not: viewer } }, { viaAutomation: true }] }],
    deletedAt: null, createdAt: { gt: readAt } }
}
export function isExpiredMessage(message: { expiresAt: Date | null }, at: Date): boolean {
  return message.expiresAt !== null && message.expiresAt <= at
}

/** Shared read/send rule. A live reply cannot keep an expired root accessible. */
export function threadRootAvailable(root: {
  conversationId: string; deletedAt: Date | null; parentId: string | null; expiresAt: Date | null
} | null, room: string, at: Date): boolean {
  return root !== null && root.conversationId === room && root.deletedAt === null &&
    root.parentId === null && !isExpiredMessage(root, at)
}

/** Preview-only projection: do not overwrite the canonical message in storage.
 * Existing native expiry schedulers can retire the preview at the ROOT deadline
 * even when no thread history was ever downloaded on this device.
 */
export function visibleInboxPreview<T extends {
  conversationId: string; parentId: string | null; expiresAt: Date | null;
  parent?: { conversationId: string; parentId: string | null; deletedAt: Date | null; expiresAt: Date | null } | null;
}>(message: T | null | undefined, room: string, at: Date): T | null {
  if (!message || message.conversationId !== room || isExpiredMessage(message, at)) return null
  if (message.parentId === null) return message
  if (!threadRootAvailable(message.parent ?? null, room, at)) return null
  const deadline = message.parent!.expiresAt
  if (deadline !== null && (message.expiresAt === null || deadline < message.expiresAt)) {
    return { ...message, expiresAt: deadline }
  }
  return message
}

/** A malformed cross-room parent link must not bypass the root's membership check. */
export function threadRepliesWhere(rootId: string, roomId: string, at: Date): Prisma.MessageWhereInput {
  return { parentId: rootId, conversationId: roomId, ...unexpiredMessages(at) }
}

/** One aggregate snapshot supplies BOTH the badge and its offline expiry schedule.
 * Never derive unread from a client's partial message page or the latest preview.
 */
export async function unreadSnapshot(
  db: Pick<import('../../prisma/generated-client').PrismaClient, '$queryRaw'>,
  room: string, viewer: string, readAt: Date, at: Date,
): Promise<{ unreadCount: number; unreadExpirations: { expiresAt: string; count: number }[] }> {
  // SQLite is the production datasource. Aggregate in ONE statement: fetching
  // roots after a child-only count could mix deletion/expiry snapshots. A reply
  // expires for inbox purposes at min(its own deadline, its canonical root's).
  // No message bodies or full history are materialized in the application.
  const groups = await db.$queryRaw<{ expiresAt: number | null; count: bigint }[]>`
    SELECT CAST(deadline AS REAL) AS expiresAt, COUNT(*) AS count
    FROM (
      SELECT CASE
        WHEN m.parentId IS NULL OR p.expiresAt IS NULL THEN m.expiresAt
        WHEN m.expiresAt IS NULL OR p.expiresAt < m.expiresAt THEN p.expiresAt
        ELSE m.expiresAt END AS deadline
      FROM Message m
      LEFT JOIN Message p ON p.id = m.parentId
      WHERE m.conversationId = ${room} AND (m.senderId <> ${viewer} OR m.viaAutomation = 1)
        AND m.deletedAt IS NULL AND m.createdAt > ${readAt}
        AND (m.expiresAt IS NULL OR m.expiresAt > ${at})
        AND (m.parentId IS NULL OR (
          p.id IS NOT NULL AND p.conversationId = m.conversationId
          AND p.parentId IS NULL AND p.deletedAt IS NULL
          AND (p.expiresAt IS NULL OR p.expiresAt > ${at})
        ))
    ) GROUP BY deadline ORDER BY deadline ASC
  `
  return {
    unreadCount: groups.reduce((total, group) => total + Number(group.count), 0),
    unreadExpirations: groups.flatMap(group => group.expiresAt === null ? [] : [{
      expiresAt: new Date(group.expiresAt).toISOString(), count: Number(group.count),
    }]),
  }
}

/** Database prefilter for the saved library; validate the parent/child room
 * relationship on the joined snapshot too (Prisma cannot compare those aliases). */
export function savedMessagesWhere(userId: string, at: Date): Prisma.SavedMessageWhereInput {
  return { userId, message: { deletedAt: null, viewOnce: false, ...unexpiredMessages(at),
    conversation: { participants: { some: { userId } } },
    AND: [{ OR: [{ parentId: null }, { parent: { is: {
      parentId: null, deletedAt: null, ...unexpiredMessages(at),
    } } }] }],
  } }
}

/** Explicit removal is repeat-safe, including after message deletion or leaving
 * its conversation. The caller must authenticate ownership of this user ID. */
export async function removeSavedMessage(
  db: Pick<import('../../prisma/generated-client').PrismaClient, 'savedMessage'>,
  userId: string, messageId: string,
): Promise<{ saved: false }> {
  await db.savedMessage.deleteMany({ where: { userId, messageId } })
  return { saved: false }
}

export class ThreadReadError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

type ThreadRootRecord = {
  id: string; conversationId: string; deletedAt: Date | null; parentId: string | null; expiresAt: Date | null
}

/** Membership, root lifetime and replies must belong to ONE database snapshot.
 * Separate reads can otherwise return cached root permissions alongside replies
 * read after removal/deletion. The callbacks only select the route's wire fields.
 */
export async function readThreadSnapshot<Root extends ThreadRootRecord, Reply extends { expiresAt: Date | null }>(
  db: import('../../prisma/generated-client').PrismaClient, rootId: string, viewerId: string,
  readRoot: (tx: Prisma.TransactionClient, id: string) => Promise<Root | null>,
  readReplies: (tx: Prisma.TransactionClient, where: Prisma.MessageWhereInput) => Promise<Reply[]>,
  clock: () => Date = () => new Date(),
): Promise<{ parent: Root; replies: Reply[] }> {
  try {
    return await db.$transaction(async tx => {
      const root = await readRoot(tx, rootId)
      if (!root || root.id !== rootId) throw new ThreadReadError(404, 'Message not found.')
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: viewerId, conversationId: root.conversationId } }, select: { id: true },
      })
      if (!participant) throw new ThreadReadError(403, 'You are not a participant of this conversation.')
      const now = clock()
      if (!threadRootAvailable(root, root.conversationId, now)) {
        throw new ThreadReadError(410, 'This thread is no longer available.')
      }
      const replies = await readReplies(tx, threadRepliesWhere(rootId, root.conversationId, now))
      const deliveredAt = clock()
      if (!threadRootAvailable(root, root.conversationId, deliveredAt)) {
        throw new ThreadReadError(410, 'This thread is no longer available.')
      }
      return { parent: root, replies: replies.filter(message => !isExpiredMessage(message, deliveredAt)) }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      // Busy/conflicting reads are not evidence of lost access: native denial ledgers
      // must not turn an operational retry into a durable 403/404/410 refusal.
      throw new ThreadReadError(409, 'This thread changed or is busy. Try again.')
    }
    throw error
  }
}

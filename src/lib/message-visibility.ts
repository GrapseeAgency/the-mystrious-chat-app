import type { Prisma } from '../../prisma/generated-client'

/** Evaluate per request, not once when the server module loads. */
export function unexpiredMessages(at: Date): Prisma.MessageWhereInput {
  return { OR: [{ expiresAt: null }, { expiresAt: { gt: at } }] }
}
export function unreadMessages(room: string, viewer: string, readAt: Date, at: Date): Prisma.MessageWhereInput {
  return { conversationId: room, senderId: { not: viewer }, deletedAt: null,
    createdAt: { gt: readAt }, ...unexpiredMessages(at) }
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

/** A malformed cross-room parent link must not bypass the root's membership check. */
export function threadRepliesWhere(rootId: string, roomId: string, at: Date): Prisma.MessageWhereInput {
  return { parentId: rootId, conversationId: roomId, ...unexpiredMessages(at) }
}

/** One aggregate snapshot supplies BOTH the badge and its offline expiry schedule.
 * Never derive unread from a client's partial message page or the latest preview.
 */
export async function unreadSnapshot(
  db: Pick<import('../../prisma/generated-client').PrismaClient, 'message'>,
  room: string, viewer: string, readAt: Date, at: Date,
): Promise<{ unreadCount: number; unreadExpirations: { expiresAt: string; count: number }[] }> {
  const groups = await db.message.groupBy({
    by: ['expiresAt'], where: unreadMessages(room, viewer, readAt, at), _count: { _all: true },
  })
  return {
    unreadCount: groups.reduce((total, group) => total + group._count._all, 0),
    unreadExpirations: groups.flatMap(group => group.expiresAt === null ? [] : [{
      expiresAt: group.expiresAt.toISOString(), count: group._count._all,
    }]).sort((a, b) => a.expiresAt.localeCompare(b.expiresAt)),
  }
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

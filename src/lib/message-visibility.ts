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

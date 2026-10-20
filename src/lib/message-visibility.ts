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

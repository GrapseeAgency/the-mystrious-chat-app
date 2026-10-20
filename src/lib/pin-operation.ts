import type { PrismaClient } from '../../prisma/generated-client'

export class PinAccessError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

/** Explicit Unpin, not a toggle. Membership and mutation share a transaction. */
export async function removeMessagePin(db: PrismaClient, userId: string, messageId: string): Promise<string> {
  return db.$transaction(async tx => {
    const message = await tx.message.findUnique({ where: { id: messageId }, select: { conversationId: true } })
    if (!message) throw new PinAccessError('Message not found.', 404)
    const member = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: message.conversationId } }, select: { id: true },
    })
    if (!member) throw new PinAccessError('You are not a participant of this conversation.', 403)
    await tx.message.updateMany({ where: { id: messageId, pinnedAt: { not: null } }, data: { pinnedAt: null, pinnedBy: null } })
    return message.conversationId
  })
}

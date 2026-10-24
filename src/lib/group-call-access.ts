import type { PrismaClient } from '../../prisma/generated-client'

/** Room membership is independent of being registered on the signaling socket. */
export async function canJoinConversationCall(db: PrismaClient, conversationId: string, userId: string): Promise<boolean> {
  return (await db.conversation.findFirst({
    where: { id: conversationId, participants: { some: { userId } } }, select: { id: true },
  })) !== null
}

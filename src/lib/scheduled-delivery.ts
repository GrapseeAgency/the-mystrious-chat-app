import type { Prisma } from '../../prisma/generated-client'

type Decision = { state: 'send'; expiresAt: Date | null } | { state: 'wait' } |
  { state: 'cancel'; reason: 'not_member' | 'announcement_only' | 'blocked' | 'conversation_missing' }

/** Re-evaluate permissions at dispatch, not only when the user scheduled it. */
export async function scheduledDeliveryDecision(tx: Prisma.TransactionClient, room: string, sender: string, now: Date): Promise<Decision> {
  const conversation = await tx.conversation.findUnique({ where: { id: room },
    select: { isGroup: true, broadcastMode: true, ttlSeconds: true, slowModeSeconds: true } })
  if (!conversation) return { state: 'cancel', reason: 'conversation_missing' }
  const participant = await tx.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: sender, conversationId: room } }, select: { role: true } })
  if (!participant) return { state: 'cancel', reason: 'not_member' }
  if (conversation.isGroup && conversation.broadcastMode && participant.role !== 'admin') {
    return { state: 'cancel', reason: 'announcement_only' }
  }
  if (!conversation.isGroup) {
    const others = await tx.conversationParticipant.findMany({ where: { conversationId: room, userId: { not: sender } }, select: { userId: true } })
    if (others.length && await tx.userBlock.findFirst({ where: { OR: others.flatMap(other => [
      { blockerId: sender, blockedId: other.userId }, { blockerId: other.userId, blockedId: sender },
    ]) }, select: { id: true } })) return { state: 'cancel', reason: 'blocked' }
  }
  if (conversation.slowModeSeconds > 0 && participant.role !== 'admin') {
    const last = await tx.message.findFirst({ where: { conversationId: room, senderId: sender, viaAutomation: false },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], select: { createdAt: true } })
    if (last && now.getTime() - last.createdAt.getTime() < conversation.slowModeSeconds * 1000) return { state: 'wait' }
  }
  return { state: 'send', expiresAt: conversation.ttlSeconds > 0 ? new Date(now.getTime() + conversation.ttlSeconds * 1000) : null }
}

import type { PrismaClient } from '../../prisma/generated-client'

export class ConversationPinError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

/** New clients set a desired state; omission retains the legacy web toggle.
 * Replaying Pin preserves its original timestamp instead of moving it again.
 */
export async function setConversationPin(db: PrismaClient, room: string, owner: string, pinned?: boolean) {
  try {
    return await db.$transaction(async tx => {
      const conversation = await tx.conversation.findUnique({ where: { id: room }, select: { id: true } })
      if (!conversation) throw new ConversationPinError(404, 'Conversation not found.')
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: room } },
        select: { id: true, pinnedAt: true },
      })
      if (!participant) throw new ConversationPinError(403, 'You are not a participant of this conversation.')
      const desired = pinned ?? (participant.pinnedAt === null)
      const pinnedAt = desired ? participant.pinnedAt ?? new Date() : null
      if ((participant.pinnedAt !== null) !== desired) {
        await tx.conversationParticipant.update({ where: { id: participant.id }, data: { pinnedAt }, select: { id: true } })
      }
      return { ok: true, pinned: desired, pinnedAt: pinnedAt?.toISOString() ?? null }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      throw new ConversationPinError(409, 'The pin changed or is busy. Retry the same setting.')
    }
    throw error
  }
}

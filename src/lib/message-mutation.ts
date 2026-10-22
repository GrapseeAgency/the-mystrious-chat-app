import type { PrismaClient, Prisma } from '../../prisma/generated-client'

export class MessageMutationError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

type Action = { kind: 'edit'; content: string } | { kind: 'delete' } |
  { kind: 'react'; emoji: string; present?: boolean } | { kind: 'view' }
const fields = { id: true, conversationId: true, senderId: true, deletedAt: true,
  expiresAt: true, parentId: true, viewOnce: true, viewedAt: true } as const

/** Membership, parent lifetime, mutation and returned snapshot have one verdict.
 * Identity/session verification remains the caller's existing transport contract.
 * Never retry a toggle automatically: a lost response is not a new user action.
 */
export async function mutateMessage<T>(db: PrismaClient, id: string, owner: string, action: Action,
  read: (tx: Prisma.TransactionClient, id: string) => Promise<T>, clock: () => Date = () => new Date(),
): Promise<{ message: T; conversationId: string; changed: boolean }> {
  try {
    return await db.$transaction(async tx => {
      const message = await tx.message.findUnique({ where: { id }, select: fields })
      if (!message) throw new MessageMutationError(404, 'Message not found.')
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: message.conversationId } }, select: { id: true },
      })
      if (!participant) throw new MessageMutationError(403, 'You are not a participant of this conversation.')
      if ((action.kind === 'edit' || action.kind === 'delete') && message.senderId !== owner) {
        throw new MessageMutationError(403, 'Only the sender can change this message.')
      }
      // Authenticate the actor before the repeat-delete acknowledgement, too.
      if (message.deletedAt) {
        if (action.kind !== 'delete') throw new MessageMutationError(410, 'This message was deleted.')
        return { message: await read(tx, id), conversationId: message.conversationId, changed: false }
      }
      const parent = message.parentId ? await tx.message.findUnique({ where: { id: message.parentId }, select: fields }) : null
      const now = clock()
      if ((message.expiresAt && message.expiresAt <= now) ||
          (message.parentId && (!parent || parent.conversationId !== message.conversationId || parent.parentId !== null ||
            parent.deletedAt !== null || (parent.expiresAt !== null && parent.expiresAt <= now)))) {
        throw new MessageMutationError(410, 'This message or its thread is no longer available.')
      }
      let changed = true
      switch (action.kind) {
        case 'edit':
          await tx.message.update({ where: { id }, data: { content: action.content, editedAt: now }, select: { id: true } })
          break
        case 'delete':
          await tx.message.update({ where: { id }, data: { deletedAt: now, pinnedAt: null, pinnedBy: null }, select: { id: true } })
          break
        case 'react': {
          const existing = await tx.reaction.findUnique({
            where: { messageId_userId_emoji: { messageId: id, userId: owner, emoji: action.emoji } }, select: { id: true },
          })
          // Native state requests are replay-safe. Keep legacy web toggle semantics
          // only when the desired state is absent.
          const present = action.present ?? !existing
          changed = present !== Boolean(existing)
          if (!present && existing) await tx.reaction.delete({ where: { id: existing.id } })
          else if (present && !existing) await tx.reaction.create({ data: { messageId: id, userId: owner, emoji: action.emoji } })
          break
        }
        case 'view': {
          if (!message.viewOnce) throw new MessageMutationError(400, 'This message is not view-once.')
          if (message.senderId === owner) throw new MessageMutationError(400, 'The sender always sees their own photo.')
          // Conditional write preserves the FIRST consumer, even across devices.
          const result = await tx.message.updateMany({ where: { id, viewedAt: null }, data: { viewedAt: now, viewedBy: owner } })
          changed = result.count === 1
          break
        }
      }
      return { message: await read(tx, id), conversationId: message.conversationId, changed }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    const code = (error as { code?: string })?.code
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes(code ?? '')) {
      throw new MessageMutationError(409, 'The message changed or is busy. Refresh before trying again.')
    }
    throw error
  }
}

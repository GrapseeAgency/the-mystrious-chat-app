import type { PrismaClient, Prisma } from '../../prisma/generated-client'

export class PollMutationError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
const messageFields = { id: true, conversationId: true, senderId: true, parentId: true, deletedAt: true, expiresAt: true } as const

/** Membership, source lifetime, open/closed state, vote and returned tally share
 * one serialization boundary. Closing cannot race a prechecked vote into the result. */
export async function mutatePoll<T>(db: PrismaClient, pollId: string, owner: string,
  action: { kind: 'vote'; optionId: string } | { kind: 'close' },
  read: (tx: Prisma.TransactionClient, messageId: string) => Promise<T>, clock: () => Date = () => new Date(),
): Promise<{ message: T; conversationId: string; changed: boolean }> {
  try {
    return await db.$transaction(async tx => {
      const poll = await tx.poll.findUnique({ where: { id: pollId }, select: {
        closedAt: true, message: { select: messageFields }, options: { select: { id: true } },
      } })
      if (!poll) throw new PollMutationError(404, 'Poll not found.')
      const message = poll.message
      const member = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId: owner, conversationId: message.conversationId } }, select: { id: true },
      })
      if (!member) throw new PollMutationError(403, 'You are not a participant of this conversation.')
      if (action.kind === 'close' && message.senderId !== owner) throw new PollMutationError(403, 'Only the poll creator can close it.')
      const now = clock()
      const parent = message.parentId ? await tx.message.findUnique({ where: { id: message.parentId }, select: messageFields }) : null
      if (message.deletedAt || (message.expiresAt && message.expiresAt <= now) ||
        (message.parentId && (!parent || parent.conversationId !== message.conversationId || parent.parentId !== null ||
          parent.deletedAt || (parent.expiresAt && parent.expiresAt <= now)))) {
        throw new PollMutationError(410, 'This poll or its thread is no longer available.')
      }
      let changed = false
      if (action.kind === 'close') {
        if (!poll.closedAt) { await tx.poll.update({ where: { id: pollId }, data: { closedAt: now } }); changed = true }
      } else {
        if (poll.closedAt) throw new PollMutationError(400, 'This poll has ended - votes are frozen.')
        if (!poll.options.some(option => option.id === action.optionId)) throw new PollMutationError(400, 'optionId does not belong to this poll.')
        const existing = await tx.pollVote.findUnique({ where: { pollId_userId: { pollId, userId: owner } }, select: { id: true, optionId: true } })
        if (existing?.optionId !== action.optionId) {
          if (existing) await tx.pollVote.update({ where: { id: existing.id }, data: { optionId: action.optionId } })
          else await tx.pollVote.create({ data: { pollId, userId: owner, optionId: action.optionId } })
          changed = true
        }
      }
      return { message: await read(tx, message.id), conversationId: message.conversationId, changed }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
      throw new PollMutationError(409, 'The poll changed or is busy. Refresh before trying again.')
    }
    throw error
  }
}

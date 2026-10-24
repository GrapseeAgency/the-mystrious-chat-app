import type { PrismaClient } from '../../prisma/generated-client'
import { mutateMessage, MessageMutationError } from './message-mutation.ts'

export class PinAccessError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

/** Compatibility helper; pin authorization and writes use the shared transaction. */
export async function removeMessagePin(db: PrismaClient, userId: string, messageId: string): Promise<string> {
  try {
    return (await mutateMessage(db, messageId, userId, { kind: 'pin', present: false },
      (tx, id) => tx.message.findUniqueOrThrow({ where: { id }, select: { id: true } }))).conversationId
  } catch (error) {
    if (error instanceof MessageMutationError) throw new PinAccessError(error.message, error.status)
    throw error
  }
}

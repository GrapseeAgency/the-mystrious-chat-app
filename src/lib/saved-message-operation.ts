import type { PrismaClient, Prisma } from '../../prisma/generated-client'
import { threadRootAvailable } from './message-visibility.ts'

export class SavedMessageError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
const readSaved = (tx: Prisma.TransactionClient, userId: string, messageId: string) =>
  tx.savedMessage.findUnique({ where: { userId_messageId: { userId, messageId } }, select: { savedAt: true } })

/** Explicit state is replay-safe; undefined retains the legacy web toggle.
 * Removing one's own bookmark is allowed after leaving/deletion. Adding requires
 * live message/root and current membership, in the same transaction as the write.
 * Caller must bind credentials to userId before invoking this helper. */
export async function mutateSavedMessage(db: PrismaClient, userId: string, messageId: string, desired?: boolean,
  readReceipt: (tx: Prisma.TransactionClient, userId: string, messageId: string) => Promise<{ savedAt: Date } | null> = readSaved, clock: () => Date = () => new Date()) {
  try {
    return await db.$transaction(async tx => {
      const existing = await readSaved(tx, userId, messageId)
      const saved = desired ?? !existing
      if (saved) {
        const message = await tx.message.findUnique({ where: { id: messageId }, select: {
          conversationId: true, deletedAt: true, expiresAt: true, viewOnce: true, parentId: true,
          parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
        } })
        if (!message) throw new SavedMessageError(404, 'Message not found.')
        const member = await tx.conversationParticipant.findUnique({
          where: { userId_conversationId: { userId, conversationId: message.conversationId } }, select: { id: true },
        })
        if (!member) throw new SavedMessageError(403, 'You are not a participant of this conversation.')
        const now = clock()
        if (message.deletedAt || (message.expiresAt && message.expiresAt <= now) ||
            (message.parentId && !threadRootAvailable(message.parent, message.conversationId, now))) {
          throw new SavedMessageError(410, 'This message or its thread is no longer available.')
        }
        if (message.viewOnce) throw new SavedMessageError(400, 'View-once messages cannot be saved.')
        if (!existing) await tx.savedMessage.create({ data: { userId, messageId, savedAt: now } })
      } else {
        await tx.savedMessage.deleteMany({ where: { userId, messageId } })
      }
      const receipt = await readReceipt(tx, userId, messageId)
      if (Boolean(receipt) !== saved) throw new SavedMessageError(409, 'Saved state was not confirmed.')
      return { saved, savedAt: receipt?.savedAt.toISOString() ?? null, userId, messageId, savedReceiptVersion: 1 as const }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
      throw new SavedMessageError(409, 'Saved state changed or is busy. Retry the same action.')
    }
    throw error
  }
}

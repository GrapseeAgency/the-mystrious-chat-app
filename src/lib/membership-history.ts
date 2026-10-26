import { Prisma } from '../../prisma/generated-client/index.js'

/** Timestamp history exclusion cannot distinguish an existing equal-time row
 * from an arrival after joining. Freeze only the exceptional boundary/future
 * identities; strictly older history already has a compact joinedAt exemption.
 * 'legacy' denotes non-Seen evidence and is upgraded only by an actual read.
 * Call only for genuinely new members, in their creation transaction. */
export async function preserveJoiningHistory(tx: Prisma.TransactionClient, room: string, users: string[], now: Date) {
  if (users.length === 0) return
  await tx.$executeRaw`
    INSERT OR IGNORE INTO MessageReadObservation (participantId, messageId, observedAt, source)
    SELECT p.id, m.id, ${now}, 'legacy'
    FROM ConversationParticipant p JOIN Message m
      ON m.conversationId = p.conversationId AND m.createdAt >= p.joinedAt
    WHERE p.conversationId = ${room} AND p.userId IN (${Prisma.join(users)})
  `
}


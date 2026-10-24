import { createHash } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class ConversationCreationError extends Error {
  constructor(public readonly status: number, message: string,
    public readonly code?: string, public readonly clientId?: string) { super(message) }
}

/** No TTL or cascading FK: deleting a room must not release its creation key.
 * Ordinary groups and channels cannot use DM pair deduplication (users may
 * intentionally create several rooms with the same name/people). */
export async function createConversationOnce(
  db: PrismaClient, owner: string, clientId: unknown, kind: 'group' | 'channel',
  instructions: unknown[], create: (tx: Prisma.TransactionClient) => Promise<{ id: string }>,
): Promise<{ id: string; replayed: boolean; clientId?: string }> {
  if (clientId !== undefined && (typeof clientId !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(clientId))) {
    throw new ConversationCreationError(400, 'clientId must contain 16–128 ASCII letters, digits, underscores or hyphens.')
  }
  const key = clientId as string | undefined
  const hash = createHash('sha256').update(JSON.stringify([kind, ...instructions])).digest('hex')
  for (let attempt = 0; ; attempt++) {
    try {
      return await db.$transaction(async tx => {
        if (key !== undefined) {
          const rows = await tx.$queryRaw<Array<{ requestHash: string; conversationId: string }>>`
            SELECT requestHash, conversationId FROM ConversationCreationOperation
            WHERE userId = ${owner} AND clientId = ${key}`
          const prior = rows[0]
          if (prior) {
            if (prior.requestHash !== hash) throw new ConversationCreationError(409, 'This creation ID was already used for different instructions.')
            const room = await tx.conversation.findUnique({ where: { id: prior.conversationId },
              select: { id: true, isGroup: true, isSelf: true, broadcastMode: true,
                participants: { where: { userId: owner }, select: { userId: true } } } })
            if (!room) throw new ConversationCreationError(410,
              'This creation completed, but the room was subsequently removed.', 'creation_gone', key)
            if (!room.isGroup || room.isSelf || room.broadcastMode !== (kind === 'channel') || room.participants.length !== 1) {
              throw new ConversationCreationError(403, 'The created room is no longer available to you.')
            }
            return { id: room.id, replayed: true, clientId: key }
          }
        }
        const room = await create(tx)
        if (key !== undefined) await tx.$executeRaw`
          INSERT INTO ConversationCreationOperation (userId, clientId, requestHash, conversationId)
          VALUES (${owner}, ${key}, ${hash}, ${room.id})`
        return { id: room.id, replayed: false, clientId: key }
      }, { isolationLevel: 'Serializable' })
    } catch (error) {
      if (error instanceof Error && /no such table: (?:main\.)?ConversationCreationOperation/.test(error.message)) {
        throw new ConversationCreationError(503, 'The server needs its conversation-creation receipt upgrade. Nothing was created; retry after the upgrade.')
      }
      if (!['P2034', 'P1008', 'P2028', 'P2002'].includes((error as { code?: string })?.code ?? '')) throw error
      if (attempt >= 2) throw new ConversationCreationError(409, 'Creation is busy. Retry the same instructions and operation ID.')
      await new Promise(resolve => setTimeout(resolve, 25 * (attempt + 1)))
    }
  }
}

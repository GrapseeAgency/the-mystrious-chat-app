import { createHash } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class WhiteboardConflict extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export type BoardStrokeInput = {
  color: string; width: number; points: string; clientId?: string; baseResetAt?: number
}
export async function boardResetAt(tx: Prisma.TransactionClient, conversationId: string): Promise<number> {
  const row = await tx.logEvent.findFirst({
    where: { kind: 'whiteboard-clear', message: conversationId },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], select: { createdAt: true },
  })
  return row?.createdAt.getTime() ?? 0
}

// Receipt lives independently of the stroke: Undo/Clear must never release a key.
// The reset check and both inserts share the same SQLite transaction as each other.
export async function commitBoardStrokes(db: PrismaClient, conversationId: string, senderId: string, strokes: BoardStrokeInput[]) {
  return db.$transaction(async tx => {
    const epoch = await boardResetAt(tx, conversationId)
    const ids: string[] = []
    let created = 0
    for (const stroke of strokes) {
      const { clientId, baseResetAt, ...drawing } = stroke
      if (clientId !== undefined) {
        if (!/^[A-Za-z0-9_-]{16,128}$/.test(clientId) || !Number.isSafeInteger(baseResetAt) || baseResetAt! < 0) {
          throw new WhiteboardConflict(400, 'A stroke operation needs a valid clientId and baseResetAt.')
        }
        const hash = createHash('sha256').update(JSON.stringify([conversationId, baseResetAt, drawing.color, drawing.width, drawing.points])).digest('hex')
        const prior = await tx.$queryRaw<Array<{ requestHash: string; strokeId: string }>>`
          SELECT requestHash, strokeId FROM WhiteboardStrokeOperation WHERE senderId = ${senderId} AND clientId = ${clientId}`
        if (prior[0]) {
          if (prior[0].requestHash !== hash) throw new WhiteboardConflict(409, 'Stroke operation was reused with different content.')
          ids.push(prior[0].strokeId) // ACK even if subsequently undone/cleared; NEVER recreate it.
          continue
        }
        if (baseResetAt !== epoch) throw new WhiteboardConflict(409, 'Board was cleared. Refresh before drawing again.')
        const strokeId = `wb:${senderId}:${clientId}`
        await tx.whiteboardStroke.create({ data: { id: strokeId, conversationId, userId: senderId, ...drawing } })
        await tx.$executeRaw`INSERT INTO WhiteboardStrokeOperation (senderId, clientId, requestHash, strokeId)
          VALUES (${senderId}, ${clientId}, ${hash}, ${strokeId})`
        ids.push(strokeId)
      } else {
        // Old clients remain usable, but do not have replay/reset guarantees.
        const row = await tx.whiteboardStroke.create({ data: { conversationId, userId: senderId, ...drawing } })
        ids.push(row.id)
      }
      created++
    }
    return { ids, created }
  })
}

export async function clearBoard(db: PrismaClient, conversationId: string, senderId: string) {
  return db.$transaction(async tx => {
    // Strictly increasing even with two Clears in the same millisecond.
    const at = Math.max(Date.now(), (await boardResetAt(tx, conversationId)) + 1)
    const removed = await tx.whiteboardStroke.deleteMany({ where: { conversationId } })
    await tx.logEvent.create({ data: { userId: senderId, kind: 'whiteboard-clear', message: conversationId, createdAt: new Date(at) } })
    return { at, cleared: removed.count }
  })
}

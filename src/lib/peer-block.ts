import type { PrismaClient } from '../../prisma/generated-client'

export class PeerBlockError extends Error {
  readonly status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}
/** Read and desired-state writes share one pair snapshot. Authentication belongs
 * to the route; neither a missing row nor a failed write is an unblock receipt. */
export async function peerBlock(db: PrismaClient, actorId: string, targetId: string, desired: boolean | null) {
  if (desired !== null && actorId === targetId) throw new PeerBlockError(400, 'You cannot block yourself.')
  try {
    return await db.$transaction(async tx => {
      const users = await tx.user.findMany({ where: { id: { in: [actorId, targetId] } }, select: { id: true } })
      if (!users.some(user => user.id === actorId) || !users.some(user => user.id === targetId))
        throw new PeerBlockError(404, 'Account not found.')
      const pair = { blockerId: actorId, blockedId: targetId }
      if (desired === true) await tx.userBlock.upsert({ where: { blockerId_blockedId: pair }, create: pair, update: {} })
      if (desired === false) await tx.userBlock.deleteMany({ where: pair })
      const row = await tx.userBlock.findUnique({ where: { blockerId_blockedId: pair }, select: { id: true } })
      return { ok: true, blockReceiptVersion: 1, actorId, targetId, blocked: row !== null }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2002', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      throw new PeerBlockError(409, 'The block state changed or is busy. Refresh before retrying.')
    throw error
  }
}

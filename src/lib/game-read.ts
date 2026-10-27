import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class GameReadError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export function gameReadErrorResponse(error: unknown): Response {
  if (error instanceof GameReadError) return Response.json({ error: error.message }, { status: error.status })
  if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
    return Response.json({ error: 'Game state is busy. Refresh before retrying.' }, { status: 409 })
  }
  throw error
}
export const gameReadHeaders = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }
/** Credential binding happens at the HTTP entry point. Membership and every
 * private row/standing/profile read share one SQLite snapshot here. */
export async function readRoomGame<T>(db: PrismaClient, userId: string,
  target: { conversationId: string } | { matchId: string } | { tournamentId: string },
  read: (tx: Prisma.TransactionClient, conversationId: string) => Promise<T>): Promise<T> {
  return db.$transaction(async tx => {
    let conversationId: string
    if ('conversationId' in target) conversationId = target.conversationId
    else {
      const resource = 'matchId' in target
        ? await tx.gameMatch.findUnique({ where: { id: target.matchId }, select: { conversationId: true } })
        : await tx.tournament.findUnique({ where: { id: target.tournamentId }, select: { conversationId: true } })
      if (!resource) throw new GameReadError(404, 'Game or tournament not found.')
      conversationId = resource.conversationId
    }
    const member = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } }, select: { id: true },
    })
    if (!member) throw new GameReadError(403, 'You are not a participant of this conversation.')
    return read(tx, conversationId)
  }, { isolationLevel: 'Serializable' })
}

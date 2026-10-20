import type { PrismaClient, Prisma } from '../../prisma/generated-client'

export class GameOperationError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
async function gameTransaction<T>(db: PrismaClient, work: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> {
  try { return await db.$transaction(work) }
  catch (error) {
    if (error && typeof error === 'object' && 'code' in error && ['P1008', 'P2028', 'P2034'].includes(String(error.code))) {
      throw new GameOperationError(409, 'Game state is busy or changed. Refresh before retrying.')
    }
    throw error
  }
}
const LINES = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]]
const MAX = 2_147_483_647
async function member(tx: Prisma.TransactionClient, userId: string, conversationId: string) {
  const row = await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId, conversationId } }, select: { id: true } })
  if (!row) throw new GameOperationError(403, 'You are not a participant of this conversation.')
}

/** The board, XP, standings and log are one verdict, not best-effort side effects. */
export async function playGame(db: PrismaClient, id: string, userId: string, cell: number) {
  if (!Number.isInteger(cell) || cell < 0 || cell > 8) throw new GameOperationError(400, 'cell must be an integer between 0 and 8.')
  return gameTransaction(db, async tx => {
    const match = await tx.gameMatch.findUnique({ where: { id } })
    if (!match) throw new GameOperationError(404, 'Game match not found.')
    await member(tx, userId, match.conversationId)
    const side = match.playerXId === userId ? 'X' : match.playerOId === userId ? 'O' : null
    if (!side) throw new GameOperationError(403, 'You are not a player in this match.')
    if (match.game !== 'tictactoe' || match.board.length !== 9 || !/^[XO ]{9}$/.test(match.board) || match.moveCount !== [...match.board].filter(c => c !== ' ').length) {
      throw new GameOperationError(409, 'Invalid board state. Refresh before playing.')
    }
    // A square is immutable. A lost response may be acknowledged again even
    // after the opponent moves or the game ends, without awarding anything twice.
    if (match.board[cell] === side) return match
    if (match.status !== 'active') throw new GameOperationError(409, 'This match is not active anymore.')
    if (!match.playerOId || match.playerOId === match.playerXId) throw new GameOperationError(409, 'Wait for an opponent to join.')
    if (match.turn !== side) throw new GameOperationError(409, 'Not your turn.')
    if (match.board[cell] !== ' ') throw new GameOperationError(409, 'Cell taken.')
    const x = [...match.board].filter(c => c === 'X').length
    const o = [...match.board].filter(c => c === 'O').length
    if ((side === 'X' ? x !== o : x !== o + 1) || LINES.some(line => ['X','O'].some(mark => line.every(i => match.board[i] === mark)))) {
      throw new GameOperationError(409, 'Invalid board state. Refresh before playing.')
    }
    const board = match.board.slice(0, cell) + side + match.board.slice(cell + 1)
    const line = LINES.find(triple => triple.every(i => board[i] === side))
    const draw = !line && match.moveCount + 1 === 9
    const status = line ? (side === 'X' ? 'x_won' : 'o_won') : draw ? 'draw' : 'active'
    const changed = await tx.gameMatch.updateMany({ where: {
      id, board: match.board, turn: side, status: 'active', playerOId: match.playerOId, moveCount: match.moveCount,
    }, data: { board, moveCount: { increment: 1 }, status,
      turn: status === 'active' ? (side === 'X' ? 'O' : 'X') : side,
      winnerId: line ? userId : null, winLine: line ? JSON.stringify(line) : null } })
    if (changed.count !== 1) throw new GameOperationError(409, 'Board changed. Refresh before retrying.')
    if (status !== 'active') {
      const players = [match.playerXId, match.playerOId]
      const awards = draw ? players : [userId]
      const xp = draw ? 10 : 25
      for (const playerId of awards) {
        const credited = await tx.user.updateMany({ where: { id: playerId, xp: { gte: 0, lte: MAX - xp } }, data: { xp: { increment: xp } } })
        if (credited.count !== 1) throw new GameOperationError(409, 'Player score cannot be updated. The move was not committed.')
      }
      const season = await tx.tournament.findFirst({ where: { conversationId: match.conversationId, status: 'running', game: 'tictactoe' }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], select: { id: true } })
      if (season) {
        for (const playerId of players) {
          await tx.tournamentPlayer.upsert({ where: { tournamentId_userId: { tournamentId: season.id, userId: playerId } }, create: { tournamentId: season.id, userId: playerId }, update: {} })
          const won = !draw && playerId === userId
          const field = draw ? 'draws' : won ? 'wins' : 'losses'
          const scored = await tx.tournamentPlayer.updateMany({ where: { tournamentId: season.id, userId: playerId,
            [field]: { gte: 0, lt: MAX }, ...(won ? { points: { gte: 0, lt: MAX } } : {}) },
            data: { [field]: { increment: 1 }, ...(won ? { points: { increment: 1 } } : {}) } })
          if (scored.count !== 1) throw new GameOperationError(409, 'Season score limit reached. The move was not committed.')
        }
      }
      for (const playerId of awards) await tx.logEvent.create({ data: {
        userId: playerId, kind: 'game', message: draw ? 'drew a tic-tac-toe match (+10 xp)' : 'won a tic-tac-toe match (+25 xp)',
        meta: JSON.stringify({ matchId: id, conversationId: match.conversationId, tournamentId: season?.id ?? null, xp }),
      } })
    }
    return tx.gameMatch.findUniqueOrThrow({ where: { id } })
  })
}

export async function joinGame(db: PrismaClient, id: string, userId: string) {
  return gameTransaction(db, async tx => {
    const match = await tx.gameMatch.findUnique({ where: { id } })
    if (!match) throw new GameOperationError(404, 'Game match not found.')
    await member(tx, userId, match.conversationId)
    if (match.playerOId === userId) return match
    if (match.status !== 'active' || match.playerOId != null || match.playerXId === userId) throw new GameOperationError(409, 'This challenge is no longer open to join.')
    const changed = await tx.gameMatch.updateMany({ where: { id, status: 'active', playerOId: null }, data: { playerOId: userId } })
    if (changed.count !== 1) throw new GameOperationError(409, 'Someone else already took the O seat.')
    return tx.gameMatch.findUniqueOrThrow({ where: { id } })
  })
}

export async function finishTournament(db: PrismaClient, id: string, userId: string) {
  return gameTransaction(db, async tx => {
    const row = await tx.tournament.findUnique({ where: { id } })
    if (!row) throw new GameOperationError(404, 'Tournament not found.')
    const membership = await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId, conversationId: row.conversationId } }, select: { role: true } })
    if (!membership || (row.createdById !== userId && membership.role !== 'admin')) throw new GameOperationError(403, 'Only a current member who created the season or a group admin can finish it.')
    if (row.status === 'finished') return
    const finished = await tx.tournament.updateMany({ where: { id, status: 'running' }, data: { status: 'finished', endsAt: row.endsAt ?? new Date() } })
    if (finished.count !== 1) throw new GameOperationError(409, 'Season changed. Refresh before retrying.')
    await tx.logEvent.create({ data: { userId, kind: 'tournament', message: `finished tournament "${row.name}"`, meta: JSON.stringify({ tournamentId: id, conversationId: row.conversationId }) } })
  })
}

export async function joinTournament(db: PrismaClient, id: string, userId: string) {
  return gameTransaction(db, async tx => {
    const season = await tx.tournament.findUnique({ where: { id } })
    if (!season) throw new GameOperationError(404, 'Tournament not found.')
    await member(tx, userId, season.conversationId)
    const existing = await tx.tournamentPlayer.findUnique({ where: { tournamentId_userId: { tournamentId: id, userId } } })
    if (existing) return existing // a lost join response stays replayable after finish
    if (!(await tx.user.findUnique({ where: { id: userId }, select: { id: true } }))) throw new GameOperationError(404, 'User not found.')
    if (season.status !== 'running') throw new GameOperationError(400, 'Tournament finished')
    return tx.tournamentPlayer.create({ data: { tournamentId: id, userId } })
  })
}

import type { PrismaClient } from '../../prisma/generated-client'

export type GameAuditAnomaly = { id: string; reason: string }
export type GameAuditReport = {
  checkedAt: string
  matches: { total: number; anomalies: GameAuditAnomaly[] }
  tournaments: { total: number; anomalies: GameAuditAnomaly[] }
}

const LINES = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]]

function winMark(board: string): string | null {
  for (const line of LINES) if (['X','O'].some(mark => line.every(i => board[i] === mark))) return board[line[0]]
  return null
}

/**
 * Section 11 item 13 (REVIEW → explicit reconciliation review) — read-only
 * audit of historical game/reward state. This REPORTS, it never repairs:
 * legacy completed resources and inconsistent historic records are listed
 * with a stable reason so a human (or a later, explicitly-approved sweep)
 * can reconcile them deliberately instead of the server silently rewriting
 * history. Checks:
 *
 *  1. matches whose players reference users that no longer exist (the
 *     playerXId/playerOId columns carry no FK, so orphaning is possible);
 *  2. completed matches whose board contradicts the verdict (missing win
 *     line, wrong winner mark, impossible move count);
 *  3. draws that are not full boards, active matches that are already full;
 *  4. season entries with negative counters.
 */
export async function auditGameHistory(db: PrismaClient, since?: Date): Promise<GameAuditReport> {
  const matchWhere = since ? { createdAt: { gte: since } } : {}
  const [matches, matchTotal, tournaments, tournamentTotal] = await Promise.all([
    db.gameMatch.findMany({ where: matchWhere, orderBy: [{ createdAt: 'asc' }, { id: 'asc' }] }),
    db.gameMatch.count({ where: matchWhere }),
    db.tournament.findMany({ where: matchWhere, include: { entries: true }, orderBy: [{ createdAt: 'asc' }, { id: 'asc' }] }),
    db.tournament.count({ where: matchWhere }),
  ])

  const userIds = new Set<string>()
  for (const match of matches) { userIds.add(match.playerXId); if (match.playerOId) userIds.add(match.playerOId) }
  for (const season of tournaments) for (const entry of season.entries) userIds.add(entry.userId)
  const known = new Set((userIds.size
    ? await db.user.findMany({ where: { id: { in: [...userIds] } }, select: { id: true } })
    : []).map(row => row.id))

  const matchAnomalies: GameAuditAnomaly[] = []
  for (const match of matches) {
    for (const playerId of [match.playerXId, match.playerOId]) {
      if (playerId && !known.has(playerId)) {
        matchAnomalies.push({ id: match.id, reason: `player ${playerId} no longer exists (historic orphan)` })
      }
    }
    const boardOk = match.board.length === 9 && /^[XO ]{9}$/.test(match.board)
    const marks = boardOk ? [...match.board].filter(c => c !== ' ').length : -1
    if (!boardOk || marks !== match.moveCount) {
      matchAnomalies.push({ id: match.id, reason: `board/moveCount mismatch (moveCount=${match.moveCount})` })
      continue
    }
    const lineMark = boardOk ? winMark(match.board) : null
    if (match.status === 'x_won' || match.status === 'o_won') {
      const expected = match.status === 'x_won' ? 'X' : 'O'
      if (lineMark !== expected) matchAnomalies.push({ id: match.id, reason: `${match.status} board without a ${expected} win line` })
      if (!match.winnerId) matchAnomalies.push({ id: match.id, reason: 'completed match without winnerId' })
      if (!match.winLine) matchAnomalies.push({ id: match.id, reason: 'completed match without winLine' })
    } else if (match.status === 'draw') {
      if (lineMark) matchAnomalies.push({ id: match.id, reason: 'draw board contains a win line' })
      if (marks !== 9) matchAnomalies.push({ id: match.id, reason: `draw with ${marks}/9 cells filled` })
    } else if (match.status === 'active' && marks === 9) {
      matchAnomalies.push({ id: match.id, reason: 'active match with a full board' })
    }
  }

  const tournamentAnomalies: GameAuditAnomaly[] = []
  for (const season of tournaments) {
    for (const entry of season.entries) {
      if (entry.points < 0 || entry.wins < 0 || entry.losses < 0 || entry.draws < 0) {
        tournamentAnomalies.push({ id: `${season.id}/${entry.userId}`, reason: 'negative standings counter (historic reward inconsistency)' })
      }
      if (!known.has(entry.userId)) {
        tournamentAnomalies.push({ id: `${season.id}/${entry.userId}`, reason: `entry user ${entry.userId} no longer exists` })
      }
    }
  }

  return {
    checkedAt: new Date().toISOString(),
    matches: { total: matchTotal, anomalies: matchAnomalies },
    tournaments: { total: tournamentTotal, anomalies: tournamentAnomalies },
  }
}

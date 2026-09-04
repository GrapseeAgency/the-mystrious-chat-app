// ─────────────────────────────────────────────────────────────
// /api/games/[id]/move — play one tic-tac-toe cell (Task R23-b)
//
//   POST body { userId, cell: 0..8 }
//   → 200 { match, playerX: {id,name,color}, playerO: {...}|null }
//
// Guards (contract statuses):
//   404 match not found · 400 bad input · 409 match not active
//   403 caller is not a player · 409 not your turn · 409 cell taken
//
// Win detection: the 8 classic triples → status x_won/o_won + winnerId
// + winLine JSON; 9 moves without a win → draw. Concurrency-safe: the
// write is a guarded updateMany against the exact pre-move snapshot, so
// two racing moves can never both land.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const BOARD_SIZE = 9

/** The 8 winning triples of tic-tac-toe. */
const WIN_TRIPLES: number[][] = [
  [0, 1, 2],
  [3, 4, 5],
  [6, 7, 8],
  [0, 3, 6],
  [1, 4, 7],
  [2, 5, 8],
  [0, 4, 8],
  [2, 4, 6],
]

/** Wire shape of a match — winLine is parsed back into number[] | null. */
interface SerializedMatch {
  id: string
  conversationId: string
  game: string
  playerXId: string
  playerOId: string | null
  board: string
  turn: 'X' | 'O'
  status: string
  winnerId: string | null
  winLine: number[] | null
  moveCount: number
  createdAt: string
  updatedAt: string
}

interface GamePlayer {
  id: string
  name: string
  color: string
}

/** Shape a GameMatch row for the wire (board is a 9-char string). */
function serializeMatch(row: {
  id: string
  conversationId: string
  game: string
  playerXId: string
  playerOId: string | null
  board: string
  turn: string
  status: string
  winnerId: string | null
  winLine: string | null
  moveCount: number
  createdAt: Date
  updatedAt: Date
}): SerializedMatch {
  let winLine: number[] | null = null
  if (row.winLine) {
    try {
      const parsed: unknown = JSON.parse(row.winLine)
      if (Array.isArray(parsed)) {
        winLine = parsed.filter((n): n is number => typeof n === 'number' && Number.isFinite(n))
      }
    } catch {
      // corrupt row — ship null rather than crashing the card
    }
  }
  return {
    id: row.id,
    conversationId: row.conversationId,
    game: row.game,
    playerXId: row.playerXId,
    playerOId: row.playerOId ?? null,
    board: row.board.padEnd(BOARD_SIZE, ' ').slice(0, BOARD_SIZE),
    turn: row.turn === 'O' ? 'O' : 'X',
    status: row.status,
    winnerId: row.winnerId ?? null,
    winLine,
    moveCount: row.moveCount,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  }
}

async function playerSummaries(
  playerXId: string,
  playerOId: string | null,
): Promise<{ playerX: GamePlayer; playerO: GamePlayer | null }> {
  const ids = [playerXId, ...(playerOId ? [playerOId] : [])]
  const rows = await db.user.findMany({
    where: { id: { in: ids } },
    select: { id: true, name: true, color: true },
  })
  const byId = new Map(rows.map((u) => [u.id, u]))
  const x = byId.get(playerXId)
  const o = playerOId ? byId.get(playerOId) : null
  return {
    playerX: x
      ? { id: x.id, name: x.name, color: x.color }
      : { id: playerXId, name: 'Player', color: 'emerald' },
    playerO: o ? { id: o.id, name: o.name, color: o.color } : null,
  }
}

/**
 * POST /api/games/[id]/move { userId, cell }
 * → 200 { match, playerX, playerO }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const cellRaw = typeof body.cell === 'number' ? body.cell : Number(body.cell)
  if (!Number.isInteger(cellRaw) || cellRaw < 0 || cellRaw >= BOARD_SIZE) {
    return NextResponse.json({ error: 'cell must be an integer between 0 and 8.' }, { status: 400 })
  }
  const cell = cellRaw

  const match = await db.gameMatch.findUnique({ where: { id } })
  if (!match) {
    return NextResponse.json({ error: 'Game match not found.' }, { status: 404 })
  }
  if (match.status !== 'active') {
    return NextResponse.json({ error: 'This match is not active anymore.' }, { status: 409 })
  }

  // Map the caller onto a side — non-players are rejected outright.
  let side: 'X' | 'O'
  if (match.playerXId === userId) {
    side = 'X'
  } else if (match.playerOId === userId) {
    side = 'O'
  } else {
    return NextResponse.json({ error: 'You are not a player in this match.' }, { status: 403 })
  }

  if (match.turn !== side) {
    return NextResponse.json({ error: 'Not your turn.' }, { status: 409 })
  }

  const board = match.board.padEnd(BOARD_SIZE, ' ')
  if (board[cell] !== ' ') {
    return NextResponse.json({ error: 'Cell taken.' }, { status: 409 })
  }

  // Apply the move against the exact pre-move snapshot: the guarded
  // updateMany only lands when the board/turn/status are unchanged,
  // so two racing requests can never both claim the same cell.
  const played = board.slice(0, cell) + side + board.slice(cell + 1)
  const moveCount = match.moveCount + 1

  let nextStatus = 'active'
  let winnerId: string | null = null
  let winLine: string | null = null
  let nextTurn: string = side === 'X' ? 'O' : 'X' // flipped unless the match ends

  for (const triple of WIN_TRIPLES) {
    if (triple.every((i) => played[i] === side)) {
      nextStatus = side === 'X' ? 'x_won' : 'o_won'
      winnerId = userId
      winLine = JSON.stringify(triple)
      nextTurn = side // match over — turn stays on the winning side
      break
    }
  }
  if (nextStatus === 'active' && moveCount >= BOARD_SIZE) {
    nextStatus = 'draw'
    nextTurn = side
  }

  const updated = await db.gameMatch.updateMany({
    where: { id, status: 'active', turn: side, board: match.board },
    data: {
      board: played,
      moveCount: { increment: 1 },
      status: nextStatus,
      ...(winnerId ? { winnerId } : {}),
      ...(winLine ? { winLine } : {}),
      turn: nextTurn,
    },
  })
  if (updated.count === 0) {
    return NextResponse.json({ error: 'Board changed — try again.' }, { status: 409 })
  }

  // ── R24-d hooks: XP + tournament season feed ────────────────
  // Runs only after the guarded write landed. EVERY write here is
  // best-effort: wrapped in try/catch so a hook failure can never
  // break the move response. Win → winner +25 xp (loser unchanged);
  // draw → both players +10 xp. A running Tournament on this room
  // auto-joins BOTH players and books the result into the standings.
  if (nextStatus !== 'active') {
    try {
      const isDraw = nextStatus === 'draw'
      const winnerLocal: string | null = isDraw ? null : userId
      const loserLocal: string | null =
        winnerLocal === null ? null : winnerLocal === match.playerXId ? match.playerOId : match.playerXId

      // XP — win: +25 to the winner only · draw: +10 to both.
      const xpAwards = isDraw
        ? [...new Set([match.playerXId, match.playerOId].filter((v): v is string => Boolean(v)))]
        : [userId]
      for (const awardedId of xpAwards) {
        await db.user.update({
          where: { id: awardedId },
          data: { xp: { increment: isDraw ? 10 : 25 } },
        })
      }

      // Tournament feed — newest running season on this room.
      let tournamentId: string | null = null
      const season = await db.tournament.findFirst({
        where: { conversationId: match.conversationId, status: 'running' },
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        select: { id: true },
      })
      if (season) {
        tournamentId = season.id
        if (isDraw && loserLocal === null) {
          // draw needs both seats; auto-join then book draws for both
          const bothIds = [match.playerXId, match.playerOId].filter(
            (v): v is string => Boolean(v),
          )
          if (bothIds.length === 2) {
            for (const playerId of bothIds) {
              await db.tournamentPlayer.upsert({
                where: { tournamentId_userId: { tournamentId: season.id, userId: playerId } },
                create: { tournamentId: season.id, userId: playerId },
                update: {},
              })
            }
            await db.tournamentPlayer.updateMany({
              where: { tournamentId: season.id, userId: { in: bothIds } },
              data: { draws: { increment: 1 } },
            })
          }
        } else if (winnerLocal !== null && loserLocal !== null) {
          // auto-join both players so standings stay complete
          for (const playerId of [winnerLocal, loserLocal]) {
            await db.tournamentPlayer.upsert({
              where: { tournamentId_userId: { tournamentId: season.id, userId: playerId } },
              create: { tournamentId: season.id, userId: playerId },
              update: {},
            })
          }
          await db.tournamentPlayer.updateMany({
            where: { tournamentId: season.id, userId: winnerLocal },
            data: { points: { increment: 1 }, wins: { increment: 1 } },
          })
          await db.tournamentPlayer.updateMany({
            where: { tournamentId: season.id, userId: loserLocal },
            data: { losses: { increment: 1 } },
          })
        }
      }

      // Devops trail — one row per awarded user.
      for (const awardedId of xpAwards) {
        await db.logEvent.create({
          data: {
            userId: awardedId,
            kind: 'game',
            message: isDraw
              ? 'drew a tic-tac-toe match (+10 xp)'
              : 'won a tic-tac-toe match (+25 xp)',
            meta: JSON.stringify({
              matchId: id,
              conversationId: match.conversationId,
              tournamentId,
              xp: isDraw ? 10 : 25,
            }),
          },
        })
      }
    } catch (hookError) {
      // XP/tournament accounting must never break the move response.
      console.error('[games/move] xp/tournament hook failed:', hookError)
    }
  }

  const fresh = await db.gameMatch.findUnique({ where: { id } })
  if (!fresh) {
    return NextResponse.json({ error: 'Game match not found.' }, { status: 404 })
  }
  const players = await playerSummaries(fresh.playerXId, fresh.playerOId)

  return NextResponse.json({ match: serializeMatch(fresh), ...players })
}

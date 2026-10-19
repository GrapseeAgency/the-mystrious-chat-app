// /api/games/[id]/move - play one tic-tac-toe cell (Task R23-b)
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
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

import { playGame, GameOperationError } from '@/lib/game-contract'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const BOARD_SIZE = 9

/** Wire shape of a match - winLine is parsed back into number[] | null. */
interface SerializedMatch {
  id: string
  conversationId: string
  game: string
  playerXId: string
  playerOId: string | null
  board: string
  turn: string
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
      // corrupt row - ship null rather than crashing the card
    }
  }
  return {
    id: row.id,
    conversationId: row.conversationId,
    game: row.game,
    playerXId: row.playerXId,
    playerOId: row.playerOId ?? null,
    board: row.board,
    turn: row.turn,
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
  const cellRaw = typeof body.cell === 'number' ? body.cell : NaN
  if (!Number.isInteger(cellRaw) || cellRaw < 0 || cellRaw >= BOARD_SIZE) {
    return NextResponse.json({ error: 'cell must be an integer between 0 and 8.' }, { status: 400 })
  }
  const cell = cellRaw

  try {
    const fresh = await playGame(db, id, userId, cell)
    const players = await playerSummaries(fresh.playerXId, fresh.playerOId)
    return NextResponse.json({ match: serializeMatch(fresh), ...players })
  } catch (error) {
    if (error instanceof GameOperationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

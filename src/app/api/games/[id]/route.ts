// ─────────────────────────────────────────────────────────────
// /api/games/[id] — match detail with resolved player identities
// (Task R23-b — in-chat tic-tac-toe)
//
//   GET → { match: SerializedMatch,
//           playerX: {id, name, color},
//           playerO: {id, name, color} | null }
//
// Read-only: mirrors the messages GET policy (no membership gate on
// reads — moves/joins carry the guards). The chat card self-fetches
// this endpoint and polls while status === 'active'.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const BOARD_SIZE = 9

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

interface GamePlayer {
  id: string
  name: string
  color: string
}

/**
 * GET /api/games/[id]
 * → 200 { match, playerX: {id,name,color}, playerO: {id,name,color}|null }
 */
export async function GET(_req: Request, { params }: RouteCtx) {
  const { id } = await params

  const row = await db.gameMatch.findUnique({ where: { id } })
  if (!row) {
    return NextResponse.json({ error: 'Game match not found.' }, { status: 404 })
  }

  const ids = [row.playerXId, ...(row.playerOId ? [row.playerOId] : [])]
  const users = await db.user.findMany({
    where: { id: { in: ids } },
    select: { id: true, name: true, color: true },
  })
  const byId = new Map(users.map((u) => [u.id, u]))
  const x = byId.get(row.playerXId)
  const o = row.playerOId ? byId.get(row.playerOId) : null

  return NextResponse.json(
    {
      match: serializeMatch(row),
      playerX: x
        ? { id: x.id, name: x.name, color: x.color }
        : { id: row.playerXId, name: 'Player', color: 'emerald' },
      playerO: o ? { id: o.id, name: o.name, color: o.color } : null,
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

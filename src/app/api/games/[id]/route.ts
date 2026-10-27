import { readRoomGame, gameReadErrorResponse, gameReadHeaders } from '@/lib/game-read'
import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/games/[id] - match detail with resolved player identities
// (Task R23-b - in-chat tic-tac-toe)
//
//   GET → { match: SerializedMatch,
//           playerX: {id, name, color},
//           playerO: {id, name, color} | null }
//
// Private room data: reads require an owned credential and current room
// membership in the same transaction. The chat card self-fetches
// this endpoint and polls while status === 'active'.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}


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

interface GamePlayer {
  id: string
  name: string
  color: string
}

/**
 * GET /api/games/[id]
 * → 200 { match, playerX: {id,name,color}, playerO: {id,name,color}|null }
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const userId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'game read')
  if (denied) return denied
  try { return await readRoomGame(db, userId, { matchId: id }, async (tx, conversationId) => {
    const row = await tx.gameMatch.findUnique({ where: { id } })
    if (!row) {
      return NextResponse.json({ error: 'Game match not found.' }, { status: 404 })
    }

    const ids = [row.playerXId, ...(row.playerOId ? [row.playerOId] : [])]
    const users = await tx.user.findMany({
      where: { id: { in: ids } },
      select: { id: true, name: true, color: true },
    })
    const byId = new Map(users.map((u) => [u.id, u]))
    const x = byId.get(row.playerXId)
    const o = row.playerOId ? byId.get(row.playerOId) : null

    return NextResponse.json(
      {
        gameReadVersion: 1, userId, conversationId,
        match: serializeMatch(row),
        playerX: x
          ? { id: x.id, name: x.name, color: x.color }
          : { id: row.playerXId, name: 'Player', color: 'emerald' },
        playerO: o ? { id: o.id, name: o.name, color: o.color } : null,
      },
      { headers: gameReadHeaders },
    )
  }) } catch (error) { return gameReadErrorResponse(error) }
}

import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/games/[id]/join - claim the open O seat (Task R23-b)
//
//   POST body { userId }
//   → 200 { match, playerX: {id,name,color}, playerO: {...} }
//
// Only joinable when: status active · playerOId still null · the
// caller is not the X owner · the caller is a room participant.
// Race-safe: a guarded updateMany(playerOId: null) means exactly one
// of two simultaneous joiners wins; the loser gets 409.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

import { joinGame, GameOperationError } from '@/lib/game-contract'

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
 * POST /api/games/[id]/join { userId }
 * → 200 { match, playerX, playerO }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'game')
  if (denied) return denied

  try {
    const fresh = await joinGame(db, id, userId)
    const players = await playerSummaries(fresh.playerXId, fresh.playerOId)
    return NextResponse.json({ match: serializeMatch(fresh), ...players })
  } catch (error) {
    if (error instanceof GameOperationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

// ─────────────────────────────────────────────────────────────
// /api/games/[id]/join — claim the open O seat (Task R23-b)
//
//   POST body { userId }
//   → 200 { match, playerX: {id,name,color}, playerO: {...} }
//
// Only joinable when: status active · playerOId still null · the
// caller is not the X owner · the caller is a room participant.
// Race-safe: a guarded updateMany(playerOId: null) means exactly one
// of two simultaneous joiners wins; the loser gets 409.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

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

  const match = await db.gameMatch.findUnique({ where: { id } })
  if (!match) {
    return NextResponse.json({ error: 'Game match not found.' }, { status: 404 })
  }
  if (match.status !== 'active' || match.playerOId !== null) {
    return NextResponse.json(
      { error: 'This challenge is no longer open to join.' },
      { status: 409 },
    )
  }
  if (match.playerXId === userId) {
    return NextResponse.json({ error: 'You already own this challenge.' }, { status: 409 })
  }

  // The joiner must be a participant of the room the match lives in.
  const membership = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: match.conversationId } },
    select: { id: true },
  })
  if (!membership) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  // Guarded write — playerOId must still be null at write time.
  const updated = await db.gameMatch.updateMany({
    where: { id, status: 'active', playerOId: null },
    data: { playerOId: userId },
  })
  if (updated.count === 0) {
    return NextResponse.json(
      { error: 'Someone else already took the O seat.' },
      { status: 409 },
    )
  }

  const fresh = await db.gameMatch.findUnique({ where: { id } })
  if (!fresh) {
    return NextResponse.json({ error: 'Game match not found.' }, { status: 404 })
  }
  const players = await playerSummaries(fresh.playerXId, fresh.playerOId)

  return NextResponse.json({ match: serializeMatch(fresh), ...players })
}

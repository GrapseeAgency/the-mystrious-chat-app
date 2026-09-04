// ─────────────────────────────────────────────────────────────
// /api/games — in-chat game matches (R23-b wave, tic-tac-toe first)
//
// A match lives in the chat as a REAL Message row (kind: "game",
// payload {matchId, game}) so it scrolls with history exactly like
// any other bubble; the card UI self-fetches /api/games/[id].
//
// Contracts:
//   POST body { userId, conversationId, game?: 'tictactoe', opponentId? }
//        → 201 { match, message: ChatMessage }   (message shape identical
//          to POST /api/conversations/[id]/messages)
//        · userId must be a ConversationParticipant (403)
//        · opponentId (when present) must also be a participant (400)
//          and differ from userId (400)
//   GET  ?conversationId= → { matches: SerializedMatch[] }  (recent, desc)
//
// No mocks — every row lands in Prisma/SQLite.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  MESSAGE_FULL_INCLUDE,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const GAMES = ['tictactoe'] as const
const MATCHES_LIST_LIMIT = 25
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

/**
 * POST /api/games — create a match + its game-invite chat message.
 * Direct challenge (opponentId set) or open challenge (any room member
 * can claim the O seat via POST /api/games/[id]/join).
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  if (!userId || !conversationId) {
    return NextResponse.json({ error: 'userId and conversationId are required.' }, { status: 400 })
  }

  const game = strField(body.game) || 'tictactoe'
  if (!(GAMES as readonly string[]).includes(game)) {
    return NextResponse.json({ error: `game must be one of: ${GAMES.join(', ')}.` }, { status: 400 })
  }

  const opponentId = strField(body.opponentId)
  if (opponentId && opponentId === userId) {
    return NextResponse.json({ error: 'opponentId must differ from userId.' }, { status: 400 })
  }

  // Existence + membership up-front → clean 404 / 403 semantics.
  const conv = await db.conversation.findUnique({
    where: { id: conversationId },
    select: { id: true, ttlSeconds: true },
  })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const memberships = await db.conversationParticipant.findMany({
    where: { conversationId, userId: { in: opponentId ? [userId, opponentId] : [userId] } },
    select: { userId: true },
  })
  const memberSet = new Set(memberships.map((m) => m.userId))
  if (!memberSet.has(userId)) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }
  if (opponentId && !memberSet.has(opponentId)) {
    return NextResponse.json(
      { error: 'opponentId must be a participant of this conversation.' },
      { status: 400 },
    )
  }

  // Match + invite message + list-ordering bumps, atomically.
  const now = new Date()
  const expiresAt = conv.ttlSeconds > 0 ? new Date(now.getTime() + conv.ttlSeconds * 1000) : null
  const { match, message } = await db.$transaction(async (tx) => {
    const created = await tx.gameMatch.create({
      data: {
        conversationId,
        game,
        playerXId: userId,
        ...(opponentId ? { playerOId: opponentId } : {}),
      },
    })
    const msg = await tx.message.create({
      data: {
        conversationId,
        senderId: userId,
        content: opponentId ? '⚔️ Tic-tac-toe challenge' : '⚔️ Tic-tac-toe — open challenge',
        kind: 'game',
        payload: JSON.stringify({ matchId: created.id, game: 'tictactoe' }),
        ...(expiresAt ? { expiresAt } : {}),
      },
      include: MESSAGE_FULL_INCLUDE,
    })
    await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
    await tx.conversationParticipant.update({
      where: { userId_conversationId: { userId, conversationId } },
      data: { lastReadAt: now },
    })
    await tx.conversationParticipant.updateMany({
      where: { conversationId, userId: { not: userId }, archivedAt: { not: null } },
      data: { archivedAt: null },
    })
    return { match: created, message: msg }
  })

  const mapped = mapMessage(message, userId)

  // Realtime relay to every OTHER member (sender handles self via response).
  const recipients = (await memberIdsOf(conversationId)).filter((memberId) => memberId !== userId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapped,
    recipientIds: recipients,
    conversationId,
  })

  return NextResponse.json(
    { match: serializeMatch(match), message: mapped },
    { status: 201 },
  )
}

/**
 * GET /api/games?conversationId= — recent matches for a room (newest first).
 * Read-only mirror of the messages GET policy (no membership gate on reads).
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const conversationId = strField(url.searchParams.get('conversationId'))
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }
  const conv = await db.conversation.findUnique({
    where: { id: conversationId },
    select: { id: true },
  })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const rows = await db.gameMatch.findMany({
    where: { conversationId },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    take: MATCHES_LIST_LIMIT,
  })

  return NextResponse.json(
    { matches: rows.map(serializeMatch) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

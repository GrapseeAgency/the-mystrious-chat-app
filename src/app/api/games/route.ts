import { readRoomGame, gameReadErrorResponse, gameReadHeaders } from '@/lib/game-read'
import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/games - in-chat game matches (R23-b wave, tic-tac-toe first)
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
// No mocks - every row lands in Prisma/SQLite.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { deliverLivePush } from '@/lib/live-push-outbox'
import { createRoomObject, MessageOperationError } from '@/lib/message-operation'
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

/**
 * POST /api/games - create a match + its game-invite chat message.
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
  const denied = await requireActionOwner(req, userId, 'game')
  if (denied) return denied

  const game = strField(body.game) || 'tictactoe'
  if (!(GAMES as readonly string[]).includes(game)) {
    return NextResponse.json({ error: `game must be one of: ${GAMES.join(', ')}.` }, { status: 400 })
  }

  const opponentId = strField(body.opponentId)
  if (opponentId && opponentId === userId) {
    return NextResponse.json({ error: 'opponentId must differ from userId.' }, { status: 400 })
  }

  try {
    const receipt = await createRoomObject(db, { kind: 'game', userId, conversationId, opponentId, clientId: body.clientId })
    const [row, message] = await Promise.all([
      db.gameMatch.findUnique({ where: { id: receipt.resourceId } }),
      db.message.findFirst({ where: { id: receipt.messageId, deletedAt: null,
        OR: [{ expiresAt: null }, { expiresAt: { gt: new Date() } }] }, include: MESSAGE_FULL_INCLUDE }),
    ])
    if (!row || !message) return NextResponse.json({ error: 'The earlier creation was delivered, but is no longer available. Submit again to start a new one.', code: 'creation_gone', clientId: receipt.clientId }, { status: 410 })
    const mapped = mapMessage(message, userId)
    // Notification failure is not creation failure. A replay must not re-notify,
    // mark the room read again, unarchive it again or create another carrier.
    if (!receipt.replayed) {
      try {
        const recipients = (await memberIdsOf(conversationId)).filter(memberId => memberId !== userId)
        await notifySocket('message:new', recipients, { type: 'message:new', message: mapped, recipientIds: recipients, conversationId })
      } catch (error) { console.error('[games/create] relay failed after commit', error) }
      // Machine producers ride the same remote push path as human sends.
      void deliverLivePush(db, message.id)
    }
    return NextResponse.json({ match: serializeMatch(row), message: mapped,
      clientId: receipt.clientId, replayed: receipt.replayed }, { status: receipt.replayed ? 200 : 201 })
  } catch (error) {
    if (error instanceof MessageOperationError) return NextResponse.json({ error: error.message, clientId: error.clientId, code: error.code }, { status: error.status })
    throw error
  }
}

/**
 * GET /api/games?conversationId= - recent matches for a room (newest first).
 * Private snapshot, bound to the verified viewer and current room membership.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const conversationId = strField(url.searchParams.get('conversationId'))
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'game read')
  if (denied) return denied
  try { return await readRoomGame(db, userId, { conversationId }, async (tx, conversationId) => {
    const conv = await tx.conversation.findUnique({
      where: { id: conversationId },
      select: { id: true },
    })
    if (!conv) {
      return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
    }

    const rows = await tx.gameMatch.findMany({
      where: { conversationId },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: MATCHES_LIST_LIMIT,
    })

    return NextResponse.json(
      { gameReadVersion: 1, userId, conversationId, matches: rows.map(serializeMatch) },
      { headers: gameReadHeaders },
    )
  }) } catch (error) { return gameReadErrorResponse(error) }
}

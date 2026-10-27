import { readRoomGame, gameReadErrorResponse, gameReadHeaders } from '@/lib/game-read'
import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/tournaments - Twitch-style seasonal ladders per room
// (Task R24-d)
//
// POST { userId, conversationId, name, game? }
//   → 201 { tournament: {id,name,game,status,createdAt,endsAt},
//           message: ChatMessage }
//   · member-guarded (403) · name 1..40 (400) · game whitelist (400)
//   · creator is auto-joined as the first TournamentPlayer
//   · a REAL kind:'tournament' Message row announces the season -
//     built with the exact same serializer include + socket relay
//     pattern as POST /api/redpackets (mapMessage + notifySocket
//     'message:new' + conversation updatedAt bump), so clients can
//     append it with zero reshaping.
//
// GET ?conversationId= → { tournaments: TournamentListItem[] }
//   · newest 5 seasons of the room (createdAt desc), each with the
//     real player count - powers the group-info Tournament section.
//
// No mocks - every row lands in Prisma/SQLite.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
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

const NAME_MIN = 1
const NAME_MAX = 40
const GAMES = ['tictactoe'] as const
const LIST_LIMIT = 5

/** Wire shape of the list endpoint (group-info Tournament section). */
export interface TournamentListItem {
  id: string
  name: string
  game: string
  status: string
  playerCount: number
  joined: boolean | null
  createdAt: string
}

/**
 * POST /api/tournaments - open a season, auto-join the creator and
 * announce it in the room with a real chat message.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'game')
  if (denied) return denied
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }

  const name = strField(body.name)
  if (name.length < NAME_MIN || name.length > NAME_MAX) {
    return NextResponse.json(
      { error: `name must be between ${NAME_MIN} and ${NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  const game = strField(body.game) || 'tictactoe'
  if (!(GAMES as readonly string[]).includes(game)) {
    return NextResponse.json(
      { error: `game must be one of: ${GAMES.join(', ')}.` },
      { status: 400 },
    )
  }

  try {
    const receipt = await createRoomObject(db, { kind: 'tournament', userId, conversationId, name, clientId: body.clientId })
    const [row, message] = await Promise.all([
      db.tournament.findUnique({ where: { id: receipt.resourceId } }),
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
      } catch (error) { console.error('[tournaments/create] relay failed after commit', error) }
    }
    return NextResponse.json({ tournament: { id: row.id, name: row.name, game: row.game, status: row.status,
        createdAt: row.createdAt.toISOString(), endsAt: row.endsAt?.toISOString() ?? null }, message: mapped,
      clientId: receipt.clientId, replayed: receipt.replayed }, { status: receipt.replayed ? 200 : 201 })
  } catch (error) {
    if (error instanceof MessageOperationError) return NextResponse.json({ error: error.message, clientId: error.clientId, code: error.code }, { status: error.status })
    throw error
  }
}

/**
 * GET /api/tournaments?conversationId= - newest 5 seasons of a room
 * with real player counts (read-only mirror of the games GET policy).
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

    const rows = await tx.tournament.findMany({
      where: { conversationId },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: LIST_LIMIT,
      include: { _count: { select: { entries: true } } },
    })

    const joined = new Set(userId ? (await tx.tournamentPlayer.findMany({
      where: { userId, tournamentId: { in: rows.map(row => row.id) } }, select: { tournamentId: true },
    })).map(entry => entry.tournamentId) : [])
    const tournaments = rows.map((row) => ({
      id: row.id, conversationId,
      name: row.name,
      game: row.game,
      status: row.status,
      playerCount: row._count.entries,
      joined: userId ? joined.has(row.id) : null,
      createdAt: row.createdAt.toISOString(),
    }))

    return NextResponse.json(
      { gameReadVersion: 1, userId, conversationId, tournaments },
      { headers: gameReadHeaders },
    )
  }) } catch (error) { return gameReadErrorResponse(error) }
}

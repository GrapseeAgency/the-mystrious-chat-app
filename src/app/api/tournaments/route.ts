// ─────────────────────────────────────────────────────────────
// /api/tournaments — Twitch-style seasonal ladders per room
// (Task R24-d)
//
// POST { userId, conversationId, name, game? }
//   → 201 { tournament: {id,name,game,status,createdAt,endsAt},
//           message: ChatMessage }
//   · member-guarded (403) · name 1..40 (400) · game whitelist (400)
//   · creator is auto-joined as the first TournamentPlayer
//   · a REAL kind:'tournament' Message row announces the season —
//     built with the exact same serializer include + socket relay
//     pattern as POST /api/redpackets (mapMessage + notifySocket
//     'message:new' + conversation updatedAt bump), so clients can
//     append it with zero reshaping.
//
// GET ?conversationId= → { tournaments: TournamentListItem[] }
//   · newest 5 seasons of the room (createdAt desc), each with the
//     real player count — powers the group-info Tournament section.
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
  createdAt: string
}

/**
 * POST /api/tournaments — open a season, auto-join the creator and
 * announce it in the room with a real chat message.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
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

  // Existence + membership up-front → clean 404 / 403 semantics
  // (same guard shape as POST /api/redpackets).
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } },
      select: { id: true },
    }),
  ])
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const now = new Date()

  // One atomic transaction: season row → creator entry → carrier
  // message (kind 'tournament') → list-ordering bumps → devops log.
  const { tournament, message } = await db.$transaction(async (tx) => {
    const created = await tx.tournament.create({
      data: {
        conversationId,
        name,
        game,
        status: 'running',
        createdById: userId,
        createdAt: now,
      },
    })
    // creator auto-joins the standings
    await tx.tournamentPlayer.create({
      data: { tournamentId: created.id, userId, joinedAt: now },
    })
    const msg = await tx.message.create({
      data: {
        conversationId,
        senderId: userId,
        content: `🏆 Tournament "${name}" started`,
        kind: 'tournament',
        payload: JSON.stringify({ tournamentId: created.id, name, game }),
        createdAt: now,
      },
      include: MESSAGE_FULL_INCLUDE,
    })
    // keep list ordering / read state / archives in lockstep with sends
    await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
    await tx.conversationParticipant.update({
      where: { userId_conversationId: { userId, conversationId } },
      data: { lastReadAt: now },
    })
    await tx.conversationParticipant.updateMany({
      where: { conversationId, userId: { not: userId }, archivedAt: { not: null } },
      data: { archivedAt: null },
    })
    await tx.logEvent.create({
      data: {
        userId,
        kind: 'tournament',
        message: `started tournament "${name}" (${game})`,
        meta: JSON.stringify({ tournamentId: created.id, conversationId, game }),
      },
    })
    return { tournament: created, message: msg }
  })

  const mapped = mapMessage(message, userId)

  // Realtime relay to every OTHER member (sender appends via the
  // response — the sheet fires `pulse:external-message` for the
  // instant local append).
  const recipients = (await memberIdsOf(conversationId)).filter((memberId) => memberId !== userId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapped,
    recipientIds: recipients,
    conversationId,
  })

  return NextResponse.json(
    {
      tournament: {
        id: tournament.id,
        name: tournament.name,
        game: tournament.game,
        status: tournament.status,
        createdAt: tournament.createdAt.toISOString(),
        endsAt: tournament.endsAt ? tournament.endsAt.toISOString() : null,
      },
      message: mapped,
    },
    { status: 201 },
  )
}

/**
 * GET /api/tournaments?conversationId= — newest 5 seasons of a room
 * with real player counts (read-only mirror of the games GET policy).
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

  const rows = await db.tournament.findMany({
    where: { conversationId },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    take: LIST_LIMIT,
    include: { _count: { select: { entries: true } } },
  })

  const tournaments: TournamentListItem[] = rows.map((row) => ({
    id: row.id,
    name: row.name,
    game: row.game,
    status: row.status,
    playerCount: row._count.entries,
    createdAt: row.createdAt.toISOString(),
  }))

  return NextResponse.json(
    { tournaments },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

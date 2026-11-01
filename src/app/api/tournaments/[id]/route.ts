import { readRoomGame, gameReadErrorResponse, gameReadHeaders } from '@/lib/game-read'
import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/tournaments/[id] - season detail + lifecycle (Task R24-d)
//
// GET → { tournament: TournamentSummary }   (src/lib/types.ts)
//   · entries sorted points desc → wins desc → joinedAt asc
//   · names/colors resolved via the user relation
//
// PATCH { userId, status: 'finished' } → 200 { tournament }
//   · only the season creator or a group admin may finish (403)
//   · idempotent: finishing a finished season is a no-op 200
//   · sets endsAt = now (the real moment the season closed) and
//     writes a LogEvent row
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import type { TournamentSummary } from '@/lib/types'

import { finishTournament, GameOperationError } from '@/lib/game-contract'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * Shape a Tournament row (+ entries with resolved users) into the
 * shared TournamentSummary contract. Section 11 item 11: with paging the
 * standings walk (points desc → wins desc → joinedAt asc → id asc) continues
 * from a `<points>.<wins>.<joinedAtMs>.<id>` cursor; without paging the
 * legacy full-standings shape is kept.
 */
function serializeTournament(row: {
  id: string
  conversationId: string
  name: string
  game: string
  status: string
  createdAt: Date
  endsAt: Date | null
  entries: Array<{
    id: string
    userId: string
    points: number
    wins: number
    losses: number
    draws: number
    joinedAt: Date
    user: { name: string; color: string } | null
  }>
}, paging?: { limit: number; cursor: { points: number; wins: number; atMs: number; id: string } | null }): TournamentSummary & {
  standingsHasMore?: boolean
  standingsNextCursor?: string | null
} {
  const sorted = [...row.entries].sort((a, b) => {
    if (a.points !== b.points) return b.points - a.points
    if (a.wins !== b.wins) return b.wins - a.wins
    if (a.joinedAt.getTime() !== b.joinedAt.getTime()) return a.joinedAt.getTime() - b.joinedAt.getTime()
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
  })
  let page = sorted
  let hasMore = false
  let nextCursor: string | null = null
  if (paging) {
    const cursor = paging.cursor
    if (cursor) {
      page = sorted.filter((e) => {
        if (e.points !== cursor.points) return e.points < cursor.points
        if (e.wins !== cursor.wins) return e.wins < cursor.wins
        if (e.joinedAt.getTime() !== cursor.atMs) return e.joinedAt.getTime() > cursor.atMs
        return e.id > cursor.id
      })
    }
    hasMore = page.length > paging.limit
    page = hasMore ? page.slice(0, paging.limit) : page
    const last = page.at(-1)
    nextCursor = hasMore && last ? `${last.points}.${last.wins}.${last.joinedAt.getTime()}.${last.id}` : null
  }
  return {
    id: row.id,
    conversationId: row.conversationId,
    name: row.name,
    game: row.game,
    status: row.status === 'finished' ? 'finished' : 'running',
    createdAt: row.createdAt.toISOString(),
    endsAt: row.endsAt ? row.endsAt.toISOString() : null,
    entries: page.map((entry) => ({
      userId: entry.userId,
      name: entry.user?.name ?? 'Player',
      color: entry.user?.color ?? 'emerald',
      points: entry.points,
      wins: entry.wins,
      losses: entry.losses,
      draws: entry.draws,
    })),
    ...(paging ? { standingsHasMore: hasMore, standingsNextCursor: nextCursor } : {}),
  }
}

/**
 * Load a tournament + its standings. Users are resolved in a SEPARATE query
 * (by id list) instead of via the TournamentPlayer.user relation - keeps the
 * route runnable even against a dev server holding a pre-relation Prisma
 * client (no :3000 restart needed).
 */
async function loadTournament(id: string, client: Pick<typeof db, 'tournament' | 'user'> = db) {
  const row = await client.tournament.findUnique({
    where: { id },
    include: { entries: { select: {
      id: true, userId: true, points: true, wins: true, losses: true, draws: true, joinedAt: true } } },
  })
  if (!row) return null

  const userIds = Array.from(new Set(row.entries.map((e) => e.userId)))
  const users = userIds.length
    ? await client.user.findMany({
        where: { id: { in: userIds } },
        select: { id: true, name: true, color: true },
      })
    : []
  const userById = new Map(users.map((u) => [u.id, u]))

  return {
    ...row,
    entries: row.entries.map((e) => ({
      ...e,
      user: userById.get(e.userId) ?? null,
    })),
  }
}

/**
 * GET /api/tournaments/[id] → 200 { tournament: TournamentSummary }
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'game read')
  if (denied) return denied
  // Section 11 item 11 - standings paging params (legacy shape without them).
  const limitRaw = url.searchParams.get('entriesLimit')
  const cursorRaw = url.searchParams.get('entriesCursor')
  let paging: { limit: number; cursor: { points: number; wins: number; atMs: number; id: string } | null } | undefined
  if (limitRaw !== null || cursorRaw !== null) {
    const limit = limitRaw !== null ? Number(limitRaw) : 50
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
      return NextResponse.json({ error: 'entriesLimit must be an integer between 1 and 100.' }, { status: 400 })
    }
    let cursor: { points: number; wins: number; atMs: number; id: string } | null = null
    if (cursorRaw) {
      const parts = cursorRaw.split('.')
      if (parts.length !== 4) return NextResponse.json({ error: 'entriesCursor must be `<points>.<wins>.<epochMs>.<id>`.' }, { status: 400 })
      const points = Number(parts[0]); const wins = Number(parts[1]); const atMs = Number(parts[2]); const entryId = parts[3]
      if (!Number.isSafeInteger(points) || !Number.isSafeInteger(wins) || !Number.isSafeInteger(atMs) || !entryId || entryId.length > 128) {
        return NextResponse.json({ error: 'entriesCursor is invalid.' }, { status: 400 })
      }
      cursor = { points, wins, atMs, id: entryId }
    }
    paging = { limit, cursor }
  }
  try { return await readRoomGame(db, userId, { tournamentId: id }, async (tx, conversationId) => {
    const row = await loadTournament(id, tx)
    if (!row) {
      return NextResponse.json({ error: 'Tournament not found.' }, { status: 404 })
    }

    return NextResponse.json(
      { gameReadVersion: 1, userId, conversationId, tournament: serializeTournament(row, paging) },
      { headers: gameReadHeaders },
    )
  }) } catch (error) { return gameReadErrorResponse(error) }
}

/**
 * PATCH /api/tournaments/[id] { userId, status: 'finished' }
 * → 200 { tournament: TournamentSummary }
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'game')
  if (denied) return denied
  const status = strField(body.status)
  if (status !== 'finished') {
    return NextResponse.json(
      { error: "status must be 'finished' (tournaments cannot be re-opened)." },
      { status: 400 },
    )
  }

  try { await finishTournament(db, id, userId) }
  catch (error) {
    if (error instanceof GameOperationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }

  const fresh = await loadTournament(id)
  if (!fresh) {
    return NextResponse.json({ error: 'Tournament not found.' }, { status: 404 })
  }
  return NextResponse.json({ tournament: serializeTournament(fresh) })
}

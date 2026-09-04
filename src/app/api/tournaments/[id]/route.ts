// ─────────────────────────────────────────────────────────────
// /api/tournaments/[id] — season detail + lifecycle (Task R24-d)
//
// GET → { tournament: TournamentSummary }   (src/lib/types.ts)
//   · entries sorted points desc → wins desc → joinedAt asc
//   · names/colors resolved via the user relation
//
// PATCH { userId, status: 'finished' } → 200 { tournament }
//   · only the season creator or a group admin may finish (403)
//   · idempotent-ish: finishing a finished season is a no-op 200
//   · sets endsAt = now (the real moment the season closed) and
//     writes a LogEvent row
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import type { TournamentSummary } from '@/lib/types'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * Shape a Tournament row (+ entries with resolved users) into the
 * shared TournamentSummary contract.
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
    userId: string
    points: number
    wins: number
    losses: number
    draws: number
    joinedAt: Date
    user: { name: string; color: string } | null
  }>
}): TournamentSummary {
  return {
    id: row.id,
    conversationId: row.conversationId,
    name: row.name,
    game: row.game,
    status: row.status === 'finished' ? 'finished' : 'running',
    createdAt: row.createdAt.toISOString(),
    endsAt: row.endsAt ? row.endsAt.toISOString() : null,
    // standings order: points desc → wins desc → joinedAt asc
    entries: [...row.entries]
      .sort((a, b) => {
        if (a.points !== b.points) return b.points - a.points
        if (a.wins !== b.wins) return b.wins - a.wins
        return a.joinedAt.getTime() - b.joinedAt.getTime()
      })
      .map((entry) => ({
        userId: entry.userId,
        name: entry.user?.name ?? 'Player',
        color: entry.user?.color ?? 'emerald',
        points: entry.points,
        wins: entry.wins,
        losses: entry.losses,
        draws: entry.draws,
      })),
  }
}

/**
 * Load a tournament + its standings. Users are resolved in a SEPARATE query
 * (by id list) instead of via the TournamentPlayer.user relation — keeps the
 * route runnable even against a dev server holding a pre-relation Prisma
 * client (no :3000 restart needed).
 */
async function loadTournament(id: string) {
  const row = await db.tournament.findUnique({
    where: { id },
    include: { entries: true },
  })
  if (!row) return null

  const userIds = Array.from(new Set(row.entries.map((e) => e.userId)))
  const users = userIds.length
    ? await db.user.findMany({
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
export async function GET(_req: Request, { params }: RouteCtx) {
  const { id } = await params

  const row = await loadTournament(id)
  if (!row) {
    return NextResponse.json({ error: 'Tournament not found.' }, { status: 404 })
  }

  return NextResponse.json(
    { tournament: serializeTournament(row) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
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
  const status = strField(body.status)
  if (status !== 'finished') {
    return NextResponse.json(
      { error: "status must be 'finished' (tournaments cannot be re-opened)." },
      { status: 400 },
    )
  }

  const row = await db.tournament.findUnique({ where: { id } })
  if (!row) {
    return NextResponse.json({ error: 'Tournament not found.' }, { status: 404 })
  }

  // Guard: only the season creator or a group admin may finish it.
  const membership = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: row.conversationId } },
    select: { role: true },
  })
  const isCreator = row.createdById !== null && row.createdById === userId
  const isAdmin = membership?.role === 'admin'
  if (!isCreator && !isAdmin) {
    return NextResponse.json(
      { error: 'Only the tournament creator or a group admin can finish the season.' },
      { status: 403 },
    )
  }

  const now = new Date()
  await db.tournament.update({
    where: { id },
    data: { status: 'finished', endsAt: row.endsAt ?? now },
  })
  await db.logEvent.create({
    data: {
      userId,
      kind: 'tournament',
      message: `finished tournament "${row.name}"`,
      meta: JSON.stringify({ tournamentId: id, conversationId: row.conversationId }),
    },
  })

  const fresh = await loadTournament(id)
  if (!fresh) {
    return NextResponse.json({ error: 'Tournament not found.' }, { status: 404 })
  }
  return NextResponse.json({ tournament: serializeTournament(fresh) })
}

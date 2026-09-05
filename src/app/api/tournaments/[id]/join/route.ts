// ─────────────────────────────────────────────────────────────
// /api/tournaments/[id]/join — enter a season's standings
// (Task R24-d)
//
// POST { userId }
//   → 200 { entry: {id, tournamentId, userId, points, wins, losses,
//                   draws, joinedAt} }
//
// Guards (contract statuses):
//   404 tournament or user not found
//   400 'Tournament finished' — closed seasons reject entrants
//   403 caller is not a participant of the season's room
//
// Joining is an upsert (idempotent): a re-join returns the existing
// entry untouched — standings never double-count a member.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/tournaments/[id]/join { userId } → 200 { entry }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const tournament = await db.tournament.findUnique({ where: { id } })
  if (!tournament) {
    return NextResponse.json({ error: 'Tournament not found.' }, { status: 404 })
  }
  if (tournament.status === 'finished') {
    return NextResponse.json({ error: 'Tournament finished' }, { status: 400 })
  }

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  // Entrants must belong to the room the season runs in.
  const membership = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: tournament.conversationId } },
    select: { id: true },
  })
  if (!membership) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const entry = await db.tournamentPlayer.upsert({
    where: { tournamentId_userId: { tournamentId: id, userId } },
    create: { tournamentId: id, userId },
    update: {}, // already joined — return the standing as-is
  })

  return NextResponse.json({
    entry: {
      id: entry.id,
      tournamentId: entry.tournamentId,
      userId: entry.userId,
      points: entry.points,
      wins: entry.wins,
      losses: entry.losses,
      draws: entry.draws,
      joinedAt: entry.joinedAt.toISOString(),
    },
  })
}

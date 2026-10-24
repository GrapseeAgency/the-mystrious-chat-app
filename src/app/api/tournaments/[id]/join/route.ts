import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/tournaments/[id]/join - enter a season's standings
// (Task R24-d)
//
// POST { userId }
//   → 200 { entry: {id, tournamentId, userId, points, wins, losses,
//                   draws, joinedAt} }
//
// Guards (contract statuses):
//   404 tournament or user not found
//   400 'Tournament finished' - closed seasons reject NEW entrants
//   403 caller is not a participant of the season's room
//
// Joining is transactional and idempotent: a re-join returns the existing
// entry untouched - standings never double-count a member.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

import { joinTournament, GameOperationError } from '@/lib/game-contract'

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
  const denied = await requireActionOwner(req, userId, 'game')
  if (denied) return denied

  try {
    const entry = await joinTournament(db, id, userId)

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
  } catch (error) {
    if (error instanceof GameOperationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

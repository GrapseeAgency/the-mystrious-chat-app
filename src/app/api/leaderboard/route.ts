// ─────────────────────────────────────────────────────────────
// /api/leaderboard — Twitch-style XP + activity standings
// (Task R24-d)
//
// GET ?conversationId=&userId=   (scoped)
//   → participant-guarded (403) → rows over THAT room's members.
// GET                           (global)
//   → rows over every user, top 50.
//
// Each row is a 100% real aggregate — zero mocks:
//   xp              → User.xp (messages crew feeds +2/msg, games +25 win)
//   messageCount    → COUNT Message (non-deleted) by the user in scope
//   gameWins        → COUNT GameMatch won by the user in scope
//   tournamentPoints→ SUM TournamentPlayer.points in scope
// Sort: tournamentPoints desc → gameWins desc → xp desc
// (name asc as a deterministic final tiebreak).
// Shape: { rows: LeaderboardRow[] } — src/lib/types.ts contract.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { strField } from '@/lib/serializers'
import type { LeaderboardRow } from '@/lib/types'

export const dynamic = 'force-dynamic'

const GLOBAL_LIMIT = 50

interface UserCore {
  id: string
  name: string
  color: string
  xp: number
}

/** Apply the contract sort: points desc → wins desc → xp desc → name. */
function sortRows(rows: LeaderboardRow[]): LeaderboardRow[] {
  return [...rows].sort((a, b) => {
    if (a.tournamentPoints !== b.tournamentPoints) return b.tournamentPoints - a.tournamentPoints
    if (a.gameWins !== b.gameWins) return b.gameWins - a.gameWins
    if (a.xp !== b.xp) return b.xp - a.xp
    return a.name.localeCompare(b.name)
  })
}

/**
 * GET /api/leaderboard → 200 { rows: LeaderboardRow[] }
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const conversationId = strField(url.searchParams.get('conversationId'))
  const userId = strField(url.searchParams.get('userId'))

  // ── conversation-scoped ─────────────────────────────────────
  if (conversationId) {
    if (!userId) {
      return NextResponse.json(
        { error: 'userId is required when conversationId is present.' },
        { status: 400 },
      )
    }
    const conv = await db.conversation.findUnique({
      where: { id: conversationId },
      select: { id: true },
    })
    if (!conv) {
      return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
    }
    const membership = await db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } },
      select: { id: true },
    })
    if (!membership) {
      return NextResponse.json(
        { error: 'You are not a participant of this conversation.' },
        { status: 403 },
      )
    }

    const [participants, messageCounts, winCounts, tpRows] = await Promise.all([
      db.conversationParticipant.findMany({
        where: { conversationId },
        select: { user: { select: { id: true, name: true, color: true, xp: true } } },
      }),
      db.message.groupBy({
        by: ['senderId'],
        where: { conversationId, deletedAt: null },
        _count: { _all: true },
      }),
      db.gameMatch.groupBy({
        by: ['winnerId'],
        where: { conversationId, winnerId: { not: null } },
        _count: { _all: true },
      }),
      db.tournamentPlayer.findMany({
        where: { tournament: { conversationId } },
        select: { userId: true, points: true },
      }),
    ])

    const msgBySender = new Map(messageCounts.map((r) => [r.senderId, r._count._all]))
    const winsByUser = new Map(
      winCounts.filter((r) => r.winnerId !== null).map((r) => [r.winnerId as string, r._count._all]),
    )
    const tpByUser = new Map<string, number>()
    for (const row of tpRows) {
      tpByUser.set(row.userId, (tpByUser.get(row.userId) ?? 0) + row.points)
    }

    const rows: LeaderboardRow[] = participants.map((p) => ({
      userId: p.user.id,
      name: p.user.name,
      color: p.user.color,
      xp: p.user.xp,
      messageCount: msgBySender.get(p.user.id) ?? 0,
      gameWins: winsByUser.get(p.user.id) ?? 0,
      tournamentPoints: tpByUser.get(p.user.id) ?? 0,
    }))

    return NextResponse.json(
      { rows: sortRows(rows) },
      { headers: { 'Cache-Control': 'no-store' } },
    )
  }

  // ── global (top 50 over every user) ─────────────────────────
  const [users, messageCounts, winCounts, tpSums] = await Promise.all([
    db.user.findMany({ select: { id: true, name: true, color: true, xp: true } }),
    db.message.groupBy({
      by: ['senderId'],
      where: { deletedAt: null },
      _count: { _all: true },
    }),
    db.gameMatch.groupBy({
      by: ['winnerId'],
      where: { winnerId: { not: null } },
      _count: { _all: true },
    }),
    db.tournamentPlayer.groupBy({
      by: ['userId'],
      _sum: { points: true },
    }),
  ])

  const msgBySender = new Map(messageCounts.map((r) => [r.senderId, r._count._all]))
  const winsByUser = new Map(
    winCounts.filter((r) => r.winnerId !== null).map((r) => [r.winnerId as string, r._count._all]),
  )
  const tpByUser = new Map(
    tpSums.map((r) => [r.userId, r._sum.points ?? 0] as const),
  )

  const rows: LeaderboardRow[] = users.map((u: UserCore) => ({
    userId: u.id,
    name: u.name,
    color: u.color,
    xp: u.xp,
    messageCount: msgBySender.get(u.id) ?? 0,
    gameWins: winsByUser.get(u.id) ?? 0,
    tournamentPoints: tpByUser.get(u.id) ?? 0,
  }))

  return NextResponse.json(
    { rows: sortRows(rows).slice(0, GLOBAL_LIMIT) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

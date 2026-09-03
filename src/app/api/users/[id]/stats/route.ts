// ─────────────────────────────────────────────────────────────
// /api/users/[id]/stats — real profile statistics (profile sheet)
// Counts derive from the live DB: messages sent, reactions given,
// photos shared, chats, groups, days on Pulse.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** GET /api/users/[id]/stats → { stats: UserStats } | 404 */
export async function GET(_req: Request, { params }: RouteCtx) {
  const { id } = await params

  const user = await db.user.findUnique({
    where: { id },
    select: { id: true, createdAt: true, lastSeenAt: true },
  })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const [messages, reactions, photos, voiceNotes, chats, groups] = await Promise.all([
    db.message.count({ where: { senderId: id, deletedAt: null } }),
    db.reaction.count({ where: { userId: id } }),
    db.message.count({ where: { senderId: id, deletedAt: null, NOT: { imagePath: null } } }),
    db.message.count({ where: { senderId: id, deletedAt: null, NOT: { audioPath: null } } }),
    db.conversationParticipant.count({ where: { userId: id } }),
    db.conversationParticipant.count({ where: { userId: id, conversation: { isGroup: true } } }),
  ])

  const days = Math.max(
    0,
    Math.floor((Date.now() - user.createdAt.getTime()) / 86_400_000),
  )

  return NextResponse.json({
    stats: {
      messages,
      reactions,
      photos,
      voiceNotes,
      chats,
      groups,
      days,
      joinedAt: user.createdAt.toISOString(),
      lastSeenAt: user.lastSeenAt.toISOString(),
    },
  })
}

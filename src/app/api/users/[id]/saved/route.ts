// ─────────────────────────────────────────────────────────────
// /api/users/[id]/saved — saved/starred messages library
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapMessage, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/users/[id]/saved → { items: SavedItem[] } (newest first, cap 100)
 * Each item carries its message + resolved conversation display info
 * (DMs resolve to the partner's name server-side).
 */
export async function GET(_req: Request, { params }: RouteCtx) {
  const { id: userId } = await params

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const rows = await db.savedMessage.findMany({
    where: { userId },
    orderBy: { savedAt: 'desc' },
    take: 100,
    include: {
      message: {
        include: {
          ...MESSAGE_FULL_INCLUDE,
          conversation: {
            select: {
              id: true,
              isGroup: true,
              name: true,
              participants: { select: { userId: true, user: { select: { name: true } } } },
            },
          },
        },
      },
    },
  })

  const items = rows.map((row) => {
    const conv = row.message.conversation
    const partner =
      !conv.isGroup && conv.name === null
        ? (conv.participants.find((p) => p.userId !== userId)?.user.name ?? null)
        : null
    return {
      savedAt: row.savedAt.toISOString(),
      conversation: {
        id: conv.id,
        isGroup: conv.isGroup,
        name: conv.isGroup ? conv.name : (partner ?? conv.name ?? 'Direct message'),
      },
      message: mapMessage(row.message, userId),
    }
  })

  return NextResponse.json({ items })
}

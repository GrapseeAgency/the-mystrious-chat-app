// /api/users/[id]/saved - saved/starred messages library
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { savedMessagesWhere, visibleInboxPreview } from '@/lib/message-visibility'
import { requireActionOwner } from '@/lib/wallet-authorization'
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
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: userId } = await params

  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'saved messages')
    if (denied) return denied
  }
  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const items = await db.$transaction(async tx => {
    const rows = await tx.savedMessage.findMany({
      where: savedMessagesWhere(userId, new Date()),
      orderBy: { savedAt: 'desc' },
      take: 100,
      include: {
        message: {
          include: {
            ...MESSAGE_FULL_INCLUDE,
            parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
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

    const deliveredAt = new Date()
    return rows.filter(row => visibleInboxPreview(row.message, row.message.conversationId, deliveredAt) !== null).map((row) => {
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

  }, { isolationLevel: 'Serializable' })
  return NextResponse.json({ items })
}

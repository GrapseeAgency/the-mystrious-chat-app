// ─────────────────────────────────────────────────────────────
// /api/users/[id]/blocks — R47 blocked-accounts list.
// `[id]` is the owner of the block list; `?userId` must equal it
// (self-service only — you can never read someone else's block list).
//
// Contract:
//   GET ?userId={ownerId} → 200 { blocks: Array<{ id, name, username,
//       avatar, color, blockedAt }> } | 400 | 403 | 404
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** GET — the owner's block list, newest first. */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: ownerId } = await params

  const viewerId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (viewerId.length === 0) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }
  if (viewerId !== ownerId) {
    return NextResponse.json(
      { error: 'Block lists are self-service only.' },
      { status: 403 },
    )
  }

  const owner = await db.user.findUnique({ where: { id: ownerId }, select: { id: true } })
  if (!owner) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const rows = await db.userBlock.findMany({
    where: { blockerId: ownerId },
    orderBy: { createdAt: 'desc' },
    select: {
      id: true,
      createdAt: true,
      blocked: {
        select: { id: true, name: true, username: true, avatar: true, color: true },
      },
    },
  })

  return NextResponse.json({
    blocks: rows.map((row) => ({
      id: row.blocked.id,
      name: row.blocked.name,
      username: row.blocked.username,
      avatar: row.blocked.avatar,
      color: row.blocked.color,
      blockedAt: row.createdAt.toISOString(),
    })),
  })
}

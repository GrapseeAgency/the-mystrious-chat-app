// /api/users/[id]/blocks - R47 blocked-accounts list.
// `[id]` is the owner of the block list; `?userId` must equal it
// (self-service only - you can never read someone else's block list).
//
// Contract:
//   GET ?userId={ownerId} → 200 { blockListVersion, blocks, nextCursor, hasMore }
//   Section 8 item 6: large lists are bounded (default 100, max 500 rows per
//   page) and continue via ?cursor=<blockedAt|id> keyset; a cursor into a page
//   the owner can no longer see is a 400, never a silent reset to page one.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** GET - the owner's block list, newest first, keyset-paginated. */
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

  const denied = await requireActionOwner(req, ownerId, 'blocked accounts')
  if (denied) return denied
  const owner = await db.user.findUnique({ where: { id: ownerId }, select: { id: true } })
  if (!owner) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const url = new URL(req.url)
  const limitRaw = url.searchParams.get('limit')
  const limit = Math.min(Math.max(limitRaw ? Number.parseInt(limitRaw, 10) || 0 : 100, 1), 500)
  const cursorRaw = url.searchParams.get('cursor')?.trim() ?? ''
  let cursorWhere: { createdAt: Date; id: string } | null = null
  if (cursorRaw) {
    const [at, rowId] = cursorRaw.split('|')
    const atMs = Date.parse(at ?? '')
    if (!at || !rowId || Number.isNaN(atMs)) {
      return NextResponse.json({ error: 'cursor must be <blockedAt ISO>|<block id>.' }, { status: 400 })
    }
    // The cursor must still reference a row the owner actually owns.
    const anchor = await db.userBlock.findFirst({
      where: { blockerId: ownerId, blockedId: rowId, createdAt: new Date(atMs) },
      select: { id: true },
    })
    if (!anchor) return NextResponse.json({ error: 'Unknown cursor.' }, { status: 400 })
    cursorWhere = { createdAt: new Date(atMs), id: rowId }
  }

  const rows = await db.userBlock.findMany({
    where: {
      blockerId: ownerId,
      ...(cursorWhere ? {
        OR: [
          { createdAt: { lt: cursorWhere.createdAt } },
          { createdAt: cursorWhere.createdAt, id: { lt: cursorWhere.id } },
        ],
      } : {}),
    },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    take: limit + 1,
    select: {
      id: true,
      createdAt: true,
      blocked: {
        select: { id: true, name: true, username: true, avatar: true, color: true },
      },
    },
  })
  const hasMore = rows.length > limit
  const page = rows.slice(0, limit)
  const last = page[page.length - 1]

  return NextResponse.json({
    blockListVersion: 1,
    userId: ownerId,
    blocks: page.map((row) => ({
      id: row.blocked.id,
      name: row.blocked.name,
      username: row.blocked.username,
      avatar: row.blocked.avatar,
      color: row.blocked.color,
      blockedAt: row.createdAt.toISOString(),
    })),
    nextCursor: hasMore && last ? `${last.createdAt.toISOString()}|${last.blocked.id}` : null,
    hasMore,
  }, { headers: { 'Cache-Control': 'private, no-store' } })
}

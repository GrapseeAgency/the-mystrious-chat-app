import { requireWalletOwner } from '@/lib/wallet-authorization'
// /api/hub/market - open listings board (Hub → Market panel)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { createListingReceipted, MarketOperationError } from '@/lib/market-contract'
import { pageLimit } from '@/lib/section10-contracts'

export const dynamic = 'force-dynamic'

const BOARD_PAGE_MAX = 100
const LEGACY_BOARD_CAP = 60

/** Board order: status asc ('cancelled' < 'open' < 'sold'), then (createdAt, id) desc. */
type BoardStatus = string
type BoardCursor = { status: BoardStatus; atMs: number; id: string }

/** Decode a `<status>.<epochMs>.<id>` board cursor. The id may contain dots. */
function decodeBoardCursor(raw: string | null): { ok: true; cursor: BoardCursor | null } | { ok: false } {
  if (raw === null || raw === '') return { ok: true, cursor: null }
  if (raw.length > 256) return { ok: false }
  const first = raw.indexOf('.')
  if (first <= 0) return { ok: false }
  const status = raw.slice(0, first)
  const rest = raw.slice(first + 1)
  const second = rest.indexOf('.')
  if (second <= 0 || second === rest.length - 1) return { ok: false }
  const atMs = Number(rest.slice(0, second))
  const id = rest.slice(second + 1)
  if (!['cancelled', 'open', 'sold'].includes(status)) return { ok: false }
  if (!Number.isSafeInteger(atMs) || atMs < 0 || id.length === 0 || id.length > 128) return { ok: false }
  return { ok: true, cursor: { status, atMs, id } }
}

function encodeBoardCursor(status: BoardStatus, atMs: number, id: string): string {
  return `${status}.${atMs}.${id}`
}

/**
 * GET /api/hub/market?userId=x[&limit=1..100][&cursor=status.ms.id]
 * → { listings, nextCursor?, hasMore? }
 * Open listings first (newest), then the viewer's own history.
 * Section 11 item 3: cursor pages walk past the legacy 60-row boundary;
 * without a cursor the legacy shape (60 rows, no paging fields) is kept so
 * every existing client keeps working unchanged.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (userId) {
    const denied = await requireWalletOwner(req, userId)
    if (denied) return denied
  }

  const limitParsed = pageLimit(url.searchParams.get('limit'), BOARD_PAGE_MAX)
  if (!limitParsed.ok) {
    return NextResponse.json({ error: 'limit must be an integer between 1 and 100.' }, { status: 400 })
  }
  const cursorParsed = decodeBoardCursor(url.searchParams.get('cursor'))
  if (!cursorParsed.ok) {
    return NextResponse.json({ error: 'cursor is invalid.' }, { status: 400 })
  }
  const cursor = cursorParsed.cursor
  const paging = limitParsed.limit !== null || cursor !== null
  const limit = limitParsed.limit ?? (cursor !== null ? 25 : LEGACY_BOARD_CAP)

  const keyset = cursor ? {
    OR: [
      { status: { gt: cursor.status } },
      { status: cursor.status, createdAt: { lt: new Date(cursor.atMs) } },
      { status: cursor.status, createdAt: new Date(cursor.atMs), id: { lt: cursor.id } },
    ],
  } : {}

  const listings = await db.marketListing.findMany({
    where: { AND: [
      { OR: [{ status: 'open' }, ...(userId ? [{ sellerId: userId }] : [])] },
      keyset,
    ] },
    orderBy: [{ status: 'asc' }, { createdAt: 'desc' }, { id: 'desc' }],
    take: paging ? limit + 1 : limit,
    include: {
      seller: { select: { id: true, name: true, username: true, color: true } },
      buyer: { select: { id: true, name: true, username: true, color: true } },
    },
  })

  const page = paging ? listings.slice(0, limit) : listings
  const hasMore = paging && listings.length > limit
  const last = page.at(-1)

  return NextResponse.json({
    marketVersion: 1, userId: userId || null,
    listings: page.map((l) => ({
      id: l.id,
      title: l.title,
      description: l.description,
      price: l.price,
      asset: l.asset,
      status: l.status,
      createdAt: l.createdAt.toISOString(),
      seller: l.seller,
      buyer: userId === l.sellerId ? l.buyer : null,
      mine: userId === l.sellerId,
    })),
    ...(paging ? {
      hasMore,
      nextCursor: hasMore && last ? encodeBoardCursor(last.status, last.createdAt.getTime(), last.id) : null,
    } : {}),
  }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
}

/** POST /api/hub/market { userId, title, description?, price, clientId? }
 * → 201 { listing } / 200 { listing, replayed: true } / 409 / 410 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const title = strField(body.title)
  const description = strField(body.description).slice(0, 200)
  const price = typeof body.price === 'number' ? body.price : NaN

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireWalletOwner(req, userId)
  if (denied) return denied
  if (!title || title.length > 80) {
    return NextResponse.json({ error: 'Title must be 1–80 characters.' }, { status: 400 })
  }
  if (!Number.isInteger(price) || price <= 0 || price > 100_000) {
    return NextResponse.json({ error: 'Price must be a positive whole number (max 100,000 PC).' }, { status: 400 })
  }
  const clientId = typeof body.clientId === 'string' && body.clientId ? body.clientId : null

  try {
    const receipt = await createListingReceipted(db, { userId, title, description, price, clientId })
    return NextResponse.json(
      { marketVersion: 1, userId, listing: receipt.listing, clientId: receipt.clientId, replayed: receipt.replayed },
      { status: receipt.replayed ? 200 : 201 },
    )
  } catch (error) {
    if (error instanceof MarketOperationError) {
      return NextResponse.json({ error: error.message, code: error.code, clientId: error.clientId }, { status: error.status })
    }
    throw error
  }
}

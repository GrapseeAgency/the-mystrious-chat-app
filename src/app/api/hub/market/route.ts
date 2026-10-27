import { requireWalletOwner } from '@/lib/wallet-authorization'
// /api/hub/market - open listings board (Hub → Market panel)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/**
 * GET /api/hub/market?userId=x → { listings }
 * Open listings first (newest), then the viewer's sold history.
 * Seller info is embedded for the row UI.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (userId) {
    const denied = await requireWalletOwner(req, userId)
    if (denied) return denied
  }

  const listings = await db.marketListing.findMany({
    where: { OR: [{ status: 'open' }, ...(userId ? [{ sellerId: userId }] : [])] },
    orderBy: [{ status: 'asc' }, { createdAt: 'desc' }, { id: 'desc' }],
    take: 60,
    include: {
      seller: { select: { id: true, name: true, username: true, color: true } },
      buyer: { select: { id: true, name: true, username: true, color: true } },
    },
  })

  return NextResponse.json({
    marketVersion: 1, userId: userId || null,
    listings: listings.map((l) => ({
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
  }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
}

/** POST /api/hub/market { userId, title, description?, price } → 201 { listing } */
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

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true, name: true, username: true, color: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  return db.$transaction(async tx => {
    const listing = await tx.marketListing.create({
      data: { sellerId: userId, title, description, price },
    })

    await tx.logEvent.create({
      data: { userId, kind: 'market', message: `listed "${title}" for ${price} PC` },
    })

    return NextResponse.json(
      {
        marketVersion: 1, userId,
        listing: {
          id: listing.id,
          title: listing.title,
          description: listing.description,
          price: listing.price,
          asset: listing.asset,
          status: listing.status,
          createdAt: listing.createdAt.toISOString(),
          seller: user, buyer: null, mine: true,
        },
      },
      { status: 201 },
    )
  }, { isolationLevel: 'Serializable' })
}

// ─────────────────────────────────────────────────────────────
// /api/hub/market/[id]/buy — escrow-style purchase (atomic)
// Coins move seller←buyer inside one transaction; the listing flips
// to "sold"; both ledgers + a log event are appended.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** POST /api/hub/market/[id]/buy { userId } → { listing, wallet } */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const listing = await db.marketListing.findUnique({
    where: { id },
    include: { seller: { select: { id: true, name: true, username: true } } },
  })
  if (!listing) {
    return NextResponse.json({ error: 'Listing not found.' }, { status: 404 })
  }
  if (listing.status !== 'open') {
    return NextResponse.json({ error: 'This listing is already sold.' }, { status: 409 })
  }
  if (listing.sellerId === userId) {
    return NextResponse.json({ error: 'You cannot buy your own listing.' }, { status: 400 })
  }

  await db.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })
  await db.userWallet.upsert({
    where: { userId: listing.sellerId },
    create: { userId: listing.sellerId },
    update: {},
  })

  const wallet = await db
    .$transaction(async (tx) => {
      const buyer = await tx.userWallet.update({
        where: { userId },
        data: { coins: { decrement: listing.price } },
      })
      if (buyer.coins < 0) {
        throw new Error('INSUFFICIENT')
      }
      await tx.userWallet.update({
        where: { userId: listing.sellerId },
        data: { coins: { increment: listing.price } },
      })
      await tx.marketListing.update({
        where: { id },
        data: { status: 'sold', buyerId: userId },
      })
      await tx.walletLedger.createMany({
        data: [
          {
            userId,
            kind: 'market_buy',
            asset: 'PC',
            amount: -listing.price,
            note: `Bought "${listing.title}"`,
            counterpartyId: listing.sellerId,
          },
          {
            userId: listing.sellerId,
            kind: 'market_sell',
            asset: 'PC',
            amount: listing.price,
            note: `Sold "${listing.title}"`,
            counterpartyId: userId,
          },
        ],
      })
      await tx.logEvent.create({
        data: {
          userId,
          kind: 'market',
          message: `bought "${listing.title}" for ${listing.price} PC`,
          meta: JSON.stringify({ listingId: id, sellerId: listing.sellerId }),
        },
      })
      return tx.userWallet.findUniqueOrThrow({ where: { userId } })
    })
    .catch((err: unknown) => {
      if (err instanceof Error && err.message === 'INSUFFICIENT') {
        return NextResponse.json(
          { error: `Insufficient PC — this costs ${listing.price}.` },
          { status: 402 },
        )
      }
      throw err
    })

  return NextResponse.json({ ok: true, wallet })
}

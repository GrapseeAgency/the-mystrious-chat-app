// /api/hub/swap - currency exchange (PC ⇄ GEM)
// GET  → rates + live market stats (volume from the real ledger)
// POST → atomic exchange with ledger rows on both legs
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { swapWallet, walletErrorResponse, PC_PER_GEM_SELL, PC_PER_GEM_BUY } from '@/lib/wallet-contract'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'


/** GET /api/hub/swap → { rates, stats } */
export async function GET() {
  const [buyVolume, sellVolume, swapCount, wallets] = await Promise.all([
    db.walletLedger.aggregate({
      where: { kind: 'swap', asset: 'PC', amount: { lt: 0 } },
      _sum: { amount: true },
    }),
    db.walletLedger.aggregate({
      where: { kind: 'swap', asset: 'PC', amount: { gt: 0 } },
      _sum: { amount: true },
    }),
    db.walletLedger.count({ where: { kind: 'swap' } }),
    db.userWallet.aggregate({ _sum: { coins: true, gems: true } }),
  ])

  return NextResponse.json({
    rates: { pcPerGemBuy: PC_PER_GEM_SELL, pcPerGemSell: PC_PER_GEM_BUY },
    stats: {
      swaps: swapCount,
      pcBought: Math.abs(buyVolume._sum.amount ?? 0),
      pcSold: sellVolume._sum.amount ?? 0,
      circulatingCoins: wallets._sum.coins ?? 0,
      circulatingGems: wallets._sum.gems ?? 0,
    },
  })
}

/**
 * POST /api/hub/swap { userId, direction: 'pc2gem' | 'gem2pc', amount }
 * pc2gem: amount in PC (multiple of 100) → floor(amount / 100) GEM
 * gem2pc: amount in GEM → amount * 80 PC
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const direction = strField(body.direction)
  const amount = Number(body.amount)

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (direction !== 'pc2gem' && direction !== 'gem2pc') {
    return NextResponse.json({ error: "Direction must be 'pc2gem' or 'gem2pc'." }, { status: 400 })
  }
  if (!Number.isInteger(amount) || amount <= 0) {
    return NextResponse.json({ error: 'Amount must be a positive whole number.' }, { status: 400 })
  }

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  try { return NextResponse.json(await swapWallet(db, userId, direction, amount)) }
  catch (error) { return walletErrorResponse(error) }
}

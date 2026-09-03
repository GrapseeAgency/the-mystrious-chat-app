// ─────────────────────────────────────────────────────────────
// /api/hub/swap — currency exchange (PC ⇄ GEM)
// GET  → rates + live market stats (volume from the real ledger)
// POST → atomic exchange with ledger rows on both legs
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const PC_PER_GEM_SELL = 100 // 100 PC buys 1 GEM
const PC_PER_GEM_BUY = 80 // selling 1 GEM returns 80 PC (spread = the house edge)

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

  await db.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })

  // per-leg asset + amount deltas
  let coinDelta = 0
  let gemDelta = 0
  let note = ''
  if (direction === 'pc2gem') {
    if (amount < PC_PER_GEM_SELL) {
      return NextResponse.json({ error: `Minimum exchange is ${PC_PER_GEM_SELL} PC for 1 GEM.` }, { status: 400 })
    }
    if (amount % PC_PER_GEM_SELL !== 0) {
      return NextResponse.json({ error: `Amount must be a multiple of ${PC_PER_GEM_SELL} PC.` }, { status: 400 })
    }
    gemDelta = Math.floor(amount / PC_PER_GEM_SELL)
    coinDelta = -amount
    note = `Swapped ${amount} PC → ${gemDelta} GEM`
  } else {
    coinDelta = amount * PC_PER_GEM_BUY
    gemDelta = -amount
    note = `Swapped ${amount} GEM → ${coinDelta} PC`
  }

  const wallet = await db
    .$transaction(async (tx) => {
      const current = await tx.userWallet.findUniqueOrThrow({ where: { userId } })
      if (current.coins + coinDelta < 0) {
        throw new Error('INSUFFICIENT_PC')
      }
      if (current.gems + gemDelta < 0) {
        throw new Error('INSUFFICIENT_GEM')
      }
      await tx.walletLedger.createMany({
        data: [
          ...(coinDelta !== 0
            ? [{ userId, kind: 'swap', asset: 'PC', amount: coinDelta, note }]
            : []),
          ...(gemDelta !== 0
            ? [{ userId, kind: 'swap', asset: 'GEM', amount: gemDelta, note }]
            : []),
        ],
      })
      await tx.logEvent.create({
        data: { userId, kind: 'swap', message: note.toLowerCase() },
      })
      return tx.userWallet.update({
        where: { userId },
        data: { coins: { increment: coinDelta }, gems: { increment: gemDelta } },
      })
    })
    .catch((err: unknown) => {
      if (err instanceof Error && err.message.startsWith('INSUFFICIENT')) {
        const asset = err.message === 'INSUFFICIENT_PC' ? 'PC' : 'GEM'
        return NextResponse.json({ error: `Insufficient ${asset} for this swap.` }, { status: 402 })
      }
      throw err
    })

  return NextResponse.json({ wallet, note })
}

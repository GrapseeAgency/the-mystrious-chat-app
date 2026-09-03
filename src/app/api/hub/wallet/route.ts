// ─────────────────────────────────────────────────────────────
// /api/hub/wallet — wallet + ledger (Hub → Wallet panel)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { dayKey } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

export interface WalletDTO {
  userId: string
  coins: number
  gems: number
  streak: number
  lastCheckIn: string | null
  checkedInToday: boolean
}

export interface LedgerEntryDTO {
  id: string
  kind: string
  asset: string
  amount: number
  note: string
  counterpartyId: string | null
  createdAt: string
}

/** GET /api/hub/wallet?userId=x&ledger=20 → { wallet, ledger: LedgerEntryDTO[] } */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const limitRaw = Number.parseInt(url.searchParams.get('ledger') ?? '30', 10)
  const ledgerLimit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 100) : 30

  const wallet = await db.userWallet.upsert({
    where: { userId },
    create: { userId },
    update: {},
  })

  const ledger = await db.walletLedger.findMany({
    where: { userId },
    orderBy: { createdAt: 'desc' },
    take: ledgerLimit,
  })

  return NextResponse.json({
    wallet: {
      userId: wallet.userId,
      coins: wallet.coins,
      gems: wallet.gems,
      streak: wallet.streak,
      lastCheckIn: wallet.lastCheckIn ? wallet.lastCheckIn.toISOString() : null,
      checkedInToday: wallet.lastCheckIn ? dayKey(wallet.lastCheckIn) === dayKey(new Date()) : false,
    },
    ledger: ledger.map((row) => ({
      id: row.id,
      kind: row.kind,
      asset: row.asset,
      amount: row.amount,
      note: row.note,
      counterpartyId: row.counterpartyId ?? null,
      createdAt: row.createdAt.toISOString(),
    })),
  })
}

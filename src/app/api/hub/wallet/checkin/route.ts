import { requireWalletOwner } from '@/lib/wallet-authorization'
// /api/hub/wallet/checkin - daily +25 PC with streak bonus
// Streak logic: consecutive UTC days → +2 PC per streak day (max +20).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

import { checkinWallet, walletErrorResponse } from '@/lib/wallet-contract'

export const dynamic = 'force-dynamic'


/** POST /api/hub/wallet/checkin { userId } → { wallet, reward, streak } */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireWalletOwner(req, userId)
  if (denied) return denied

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  try {
    const result = await checkinWallet(db, userId)
    if (result.alreadyCheckedIn) return NextResponse.json(
      { error: 'Daily reward already claimed. Come back on the next eligible UTC day.', wallet: result.wallet },
      { status: 409 },
    )
    return NextResponse.json({ wallet: result.wallet, reward: result.reward, streak: result.streak })
  } catch (error) { return walletErrorResponse(error) }
}

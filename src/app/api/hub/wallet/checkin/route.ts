// ─────────────────────────────────────────────────────────────
// /api/hub/wallet/checkin — daily +25 PC with streak bonus
// Streak logic: consecutive UTC days → +2 PC per streak day (max +20).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField, dayKey } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const BASE_REWARD = 25
const STREAK_BONUS_CAP = 20

/** POST /api/hub/wallet/checkin { userId } → { wallet, reward, streak } */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const now = new Date()
  const today = dayKey(now)
  const yesterday = dayKey(new Date(now.getTime() - 86_400_000))

  const wallet = await db.userWallet.upsert({
    where: { userId },
    create: { userId },
    update: {},
  })

  if (wallet.lastCheckIn && dayKey(wallet.lastCheckIn) === today) {
    return NextResponse.json(
      { error: 'Already checked in today. Come back tomorrow.', wallet },
      { status: 409 },
    )
  }

  // consecutive-day streak: yesterday checked → +1, else reset to 1
  const streak =
    wallet.lastCheckIn && dayKey(wallet.lastCheckIn) === yesterday
      ? wallet.streak + 1
      : 1

  const bonus = Math.min((streak - 1) * 2, STREAK_BONUS_CAP)
  const reward = BASE_REWARD + bonus

  const [updated] = await db.$transaction([
    db.userWallet.update({
      where: { userId },
      data: { coins: { increment: reward }, streak, lastCheckIn: now },
    }),
    db.walletLedger.create({
      data: {
        userId,
        kind: 'checkin',
        asset: 'PC',
        amount: reward,
        note: streak > 1 ? `Daily check-in · ${streak}-day streak (+${bonus} bonus)` : 'Daily check-in',
      },
    }),
    db.logEvent.create({
      data: { userId, kind: 'checkin', message: `checked in — day ${streak} streak, +${reward} PC` },
    }),
  ])

  return NextResponse.json({ wallet: updated, reward, streak })
}

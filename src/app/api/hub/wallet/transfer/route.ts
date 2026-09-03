// ─────────────────────────────────────────────────────────────
// /api/hub/wallet/transfer — PC transfer by @handle (real ledger rows
// on both sides + log event). Atomic via interactive transaction.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** POST /api/hub/wallet/transfer { userId, toUsername, amount, note? } */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const toUsername = strField(body.toUsername).toLowerCase().replace(/^@/, '')
  const note = strField(body.note).slice(0, 140)
  const amount = Number(body.amount)

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!Number.isInteger(amount) || amount <= 0 || amount > 100_000) {
    return NextResponse.json({ error: 'Amount must be a positive whole number (max 100,000 PC).' }, { status: 400 })
  }
  if (!toUsername) {
    return NextResponse.json({ error: 'Recipient @handle is required.' }, { status: 400 })
  }

  const sender = await db.user.findUnique({
    where: { id: userId },
    include: { wallet: true },
  })
  if (!sender) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const recipient = await db.user.findUnique({
    where: { username: toUsername },
    select: { id: true, name: true, username: true },
  })
  if (!recipient) {
    return NextResponse.json({ error: `No Pulse account with handle @${toUsername}.` }, { status: 404 })
  }
  if (recipient.id === userId) {
    return NextResponse.json({ error: 'You cannot transfer to yourself.' }, { status: 400 })
  }

  // ensure both wallets exist before the move
  await db.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })
  await db.userWallet.upsert({ where: { userId: recipient.id }, create: { userId: recipient.id }, update: {} })

  const senderWallet = sender.wallet ?? (await db.userWallet.findUniqueOrThrow({ where: { userId } }))
  if (senderWallet.coins < amount) {
    return NextResponse.json(
      { error: `Insufficient PC — you have ${senderWallet.coins}, tried to send ${amount}.` },
      { status: 402 },
    )
  }

  const result = await db.$transaction(async (tx) => {
    const from = await tx.userWallet.update({
      where: { userId },
      data: { coins: { decrement: amount } },
    })
    // guard against a concurrent spend driving the balance negative
    if (from.coins < 0) {
      throw new Error('INSUFFICIENT')
    }
    await tx.userWallet.update({
      where: { userId: recipient.id },
      data: { coins: { increment: amount } },
    })
    await tx.walletLedger.createMany({
      data: [
        {
          userId,
          kind: 'transfer_out',
          asset: 'PC',
          amount: -amount,
          note: note || `To @${toUsername}`,
          counterpartyId: recipient.id,
        },
        {
          userId: recipient.id,
          kind: 'transfer_in',
          asset: 'PC',
          amount,
          note: note || `From @${sender.username ?? sender.name}`,
          counterpartyId: userId,
        },
      ],
    })
    await tx.logEvent.create({
      data: {
        userId,
        kind: 'transfer',
        message: `sent ${amount} PC to @${toUsername}`,
        meta: JSON.stringify({ to: recipient.id, amount }),
      },
    })
    return tx.userWallet.findUniqueOrThrow({ where: { userId } })
  }).catch((err: unknown) => {
    if (err instanceof Error && err.message === 'INSUFFICIENT') {
      return NextResponse.json({ error: 'Insufficient PC — balance changed, try again.' }, { status: 402 })
    }
    throw err
  })

  return NextResponse.json({ wallet: result, to: { id: recipient.id, name: recipient.name, username: recipient.username } })
}

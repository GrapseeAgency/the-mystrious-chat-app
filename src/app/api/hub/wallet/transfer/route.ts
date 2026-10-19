// /api/hub/wallet/transfer - PC transfer by @handle (real ledger rows
// on both sides + log event). Atomic via interactive transaction.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { transferWallet, walletErrorResponse } from '@/lib/wallet-contract'
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
    select: { id: true, username: true, name: true },
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

  try {
    const wallet = await transferWallet(db, userId, recipient.id, amount, note, sender.username ?? sender.name, toUsername)
    return NextResponse.json({ wallet, to: { id: recipient.id, name: recipient.name, username: recipient.username } })
  } catch (error) { return walletErrorResponse(error) }
}

import type { PrismaClient } from '../../prisma/generated-client'

export const PC_PER_GEM_SELL = 100
export const PC_PER_GEM_BUY = 80
const MAX_BALANCE = 2_147_483_647 // Prisma Int / native integer contract
export class WalletContractError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export function walletErrorResponse(error: unknown): Response {
  if (error instanceof WalletContractError) return Response.json({ error: error.message }, { status: error.status })
  throw error
}
function amountInRange(amount: number) {
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > MAX_BALANCE) {
    throw new WalletContractError(400, 'Amount is outside the supported whole-number range.')
  }
}
export async function transferWallet(db: PrismaClient, senderId: string, recipientId: string, amount: number, note: string, senderLabel: string, recipientLabel: string) {
  amountInRange(amount)
  if (amount > 100_000 || senderId === recipientId) throw new WalletContractError(400, 'Invalid transfer.')
  return db.$transaction(async tx => {
    await tx.userWallet.upsert({ where: { userId: senderId }, create: { userId: senderId }, update: {} })
    await tx.userWallet.upsert({ where: { userId: recipientId }, create: { userId: recipientId }, update: {} })
    const debited = await tx.userWallet.updateMany({ where: { userId: senderId, coins: { gte: amount } }, data: { coins: { decrement: amount } } })
    if (!debited.count) throw new WalletContractError(402, 'Insufficient PC - balance changed, try again.')
    const credited = await tx.userWallet.updateMany({ where: { userId: recipientId, coins: { lte: MAX_BALANCE - amount } }, data: { coins: { increment: amount } } })
    if (!credited.count) throw new WalletContractError(422, 'Recipient balance limit would be exceeded.')
    await tx.walletLedger.createMany({ data: [
      { userId: senderId, kind: 'transfer_out', asset: 'PC', amount: -amount, note: note || `To @${recipientLabel}`, counterpartyId: recipientId },
      { userId: recipientId, kind: 'transfer_in', asset: 'PC', amount, note: note || `From @${senderLabel}`, counterpartyId: senderId },
    ] })
    await tx.logEvent.create({ data: { userId: senderId, kind: 'transfer', message: `sent ${amount} PC to @${recipientLabel}`, meta: JSON.stringify({ to: recipientId, amount }) } })
    return tx.userWallet.findUniqueOrThrow({ where: { userId: senderId } })
  })
}
export async function swapWallet(db: PrismaClient, userId: string, direction: string, amount: number) {
  amountInRange(amount)
  if (direction !== 'pc2gem' && direction !== 'gem2pc') throw new WalletContractError(400, 'Invalid swap direction.')
  if (direction === 'pc2gem' && (amount < PC_PER_GEM_SELL || amount % PC_PER_GEM_SELL)) throw new WalletContractError(400, `Amount must be a positive multiple of ${PC_PER_GEM_SELL} PC.`)
  const coinDelta = direction === 'pc2gem' ? -amount : amount * PC_PER_GEM_BUY
  const gemDelta = direction === 'pc2gem' ? amount / PC_PER_GEM_SELL : -amount
  if (Math.abs(coinDelta) > MAX_BALANCE || Math.abs(gemDelta) > MAX_BALANCE) throw new WalletContractError(400, 'Exchange exceeds the balance limit.')
  const note = direction === 'pc2gem' ? `Swapped ${amount} PC → ${gemDelta} GEM` : `Swapped ${amount} GEM → ${coinDelta} PC`
  const wallet = await db.$transaction(async tx => {
    const current = await tx.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })
    if (current.coins + coinDelta < 0 || current.gems + gemDelta < 0) throw new WalletContractError(402, 'Insufficient balance for this swap.')
    if (current.coins + coinDelta > MAX_BALANCE || current.gems + gemDelta > MAX_BALANCE) throw new WalletContractError(422, 'Exchange would exceed the balance limit.')
    const updated = await tx.userWallet.updateMany({ where: {
      userId, coins: { gte: Math.max(0, -coinDelta), lte: MAX_BALANCE - Math.max(0, coinDelta) },
      gems: { gte: Math.max(0, -gemDelta), lte: MAX_BALANCE - Math.max(0, gemDelta) },
    }, data: { coins: { increment: coinDelta }, gems: { increment: gemDelta } } })
    if (!updated.count) throw new WalletContractError(402, 'Balance changed; refresh before exchanging.')
    await tx.walletLedger.createMany({ data: [
      { userId, kind: 'swap', asset: 'PC', amount: coinDelta, note },
      { userId, kind: 'swap', asset: 'GEM', amount: gemDelta, note },
    ] })
    await tx.logEvent.create({ data: { userId, kind: 'swap', message: note.toLowerCase() } })
    return tx.userWallet.findUniqueOrThrow({ where: { userId } })
  })
  return { wallet, note }
}

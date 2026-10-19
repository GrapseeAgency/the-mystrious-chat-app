import { createHash } from 'node:crypto'
import type { PrismaClient, Prisma } from '../../prisma/generated-client'

export const PC_PER_GEM_SELL = 100
export const PC_PER_GEM_BUY = 80
const MAX_BALANCE = 2_147_483_647 // Prisma Int / native integer contract
export class WalletContractError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export function walletErrorResponse(error: unknown): Response {
  if (error instanceof WalletContractError || error instanceof WalletOperationConflict) return Response.json({ error: error.message }, { status: error.status })
  throw error
}
function amountInRange(amount: number) {
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > MAX_BALANCE) {
    throw new WalletContractError(400, 'Amount is outside the supported whole-number range.')
  }
}
export async function transferWallet(db: PrismaClient, senderId: string, recipientId: string, amount: number, note: string, senderLabel: string, recipientLabel: string, clientId?: string) {
  return walletOperation(db, senderId, clientId, ['transfer', recipientId, amount, note], async tx => {
    amountInRange(amount)
    if (amount > 100_000 || senderId === recipientId) throw new WalletContractError(400, 'Invalid transfer.')
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
export async function swapWallet(db: PrismaClient, userId: string, direction: string, amount: number, clientId?: string) {
  return walletOperation(db, userId, clientId, ['swap', direction, amount], async tx => {
    amountInRange(amount)
    if (direction !== 'pc2gem' && direction !== 'gem2pc') throw new WalletContractError(400, 'Invalid swap direction.')
    if (direction === 'pc2gem' && (amount < PC_PER_GEM_SELL || amount % PC_PER_GEM_SELL)) throw new WalletContractError(400, `Amount must be a positive multiple of ${PC_PER_GEM_SELL} PC.`)
    const coinDelta = direction === 'pc2gem' ? -amount : amount * PC_PER_GEM_BUY
    const gemDelta = direction === 'pc2gem' ? amount / PC_PER_GEM_SELL : -amount
    if (Math.abs(coinDelta) > MAX_BALANCE || Math.abs(gemDelta) > MAX_BALANCE) throw new WalletContractError(400, 'Exchange exceeds the balance limit.')
    const note = direction === 'pc2gem' ? `Swapped ${amount} PC → ${gemDelta} GEM` : `Swapped ${amount} GEM → ${coinDelta} PC`
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
    return { wallet: await tx.userWallet.findUniqueOrThrow({ where: { userId } }), note }
  })
}

export class WalletOperationConflict extends Error {
  readonly status = 409
}
/** Receipt and all balance/ledger writes commit together. A retry replays the
 * original verdict, never a second debit. No receipt expiry releases a key. */
export async function walletOperation<T>(db: PrismaClient, userId: string, clientId: string | undefined,
  intent: unknown[], mutate: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> {
  if (clientId !== undefined && !/^[A-Za-z0-9_-]{16,128}$/.test(clientId)) throw new WalletOperationConflict('Invalid wallet operation ID.')
  const hash = createHash('sha256').update(JSON.stringify(intent)).digest('hex')
  return db.$transaction(async tx => {
    if (clientId !== undefined) {
      const rows = await tx.$queryRaw<Array<{ requestHash: string; result: string }>>`
        SELECT requestHash, result FROM WalletOperation WHERE userId = ${userId} AND clientId = ${clientId}`
      if (rows[0]) {
        if (rows[0].requestHash !== hash) throw new WalletOperationConflict('Operation ID was already used for different wallet instructions.')
        return JSON.parse(rows[0].result, (key, value) => key === 'lastCheckIn' && typeof value === 'string' ? new Date(value) : value) as T
      }
    }
    const result = await mutate(tx)
    if (clientId !== undefined) await tx.$executeRaw`
      INSERT INTO WalletOperation (userId, clientId, requestHash, result)
      VALUES (${userId}, ${clientId}, ${hash}, ${JSON.stringify(result)})`
    return result
  })
}

/** A listing is a single-use purchase identity. Claim + money + ledger are one
 * transaction; the winning buyer can retry after losing the HTTP response. */
export async function buyMarketListing(db: PrismaClient, buyerId: string, listingId: string) {
  return db.$transaction(async tx => {
    const listing = await tx.marketListing.findUnique({ where: { id: listingId } })
    if (!listing) throw new WalletContractError(404, 'Listing not found.')
    if (listing.sellerId === buyerId) throw new WalletContractError(400, 'You cannot buy your own listing.')
    if (listing.status === 'sold' && listing.buyerId === buyerId) {
      return tx.userWallet.findUniqueOrThrow({ where: { userId: buyerId } })
    }
    if (listing.status !== 'open') throw new WalletContractError(409, 'This listing is already sold.')
    amountInRange(listing.price)
    if (listing.asset !== 'PC') throw new WalletContractError(400, 'Unsupported listing asset.')
    const claimed = await tx.marketListing.updateMany({ where: { id: listingId, status: 'open', buyerId: null }, data: { status: 'sold', buyerId } })
    if (!claimed.count) throw new WalletContractError(409, 'This listing is no longer available.')
    await tx.userWallet.upsert({ where: { userId: buyerId }, create: { userId: buyerId }, update: {} })
    await tx.userWallet.upsert({ where: { userId: listing.sellerId }, create: { userId: listing.sellerId }, update: {} })
    const debited = await tx.userWallet.updateMany({ where: { userId: buyerId, coins: { gte: listing.price } }, data: { coins: { decrement: listing.price } } })
    if (!debited.count) throw new WalletContractError(402, `Insufficient PC - this costs ${listing.price}.`)
    const credited = await tx.userWallet.updateMany({ where: { userId: listing.sellerId, coins: { gte: 0, lte: MAX_BALANCE - listing.price } }, data: { coins: { increment: listing.price } } })
    if (!credited.count) throw new WalletContractError(422, 'Seller balance limit would be exceeded.')
    await tx.walletLedger.createMany({ data: [
      { userId: buyerId, kind: 'market_buy', asset: 'PC', amount: -listing.price, note: `Bought "${listing.title}"`, counterpartyId: listing.sellerId },
      { userId: listing.sellerId, kind: 'market_sell', asset: 'PC', amount: listing.price, note: `Sold "${listing.title}"`, counterpartyId: buyerId },
    ] })
    await tx.logEvent.create({ data: { userId: buyerId, kind: 'market', message: `bought "${listing.title}" for ${listing.price} PC`, meta: JSON.stringify({ listingId, sellerId: listing.sellerId }) } })
    return tx.userWallet.findUniqueOrThrow({ where: { userId: buyerId } })
  })
}

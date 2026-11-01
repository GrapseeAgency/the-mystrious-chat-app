import type { PrismaClient, Prisma } from '../../prisma/generated-client'
import { settleSellerListings } from './market-contract.ts'

export type EconomySettlementReport = {
  userId: string
  cancelledListings: number
  abandonedMatches: number
  ledgerRows: number
  ledgerNetPc: number
  ledgerNetGem: number
  walletRows: number
}

/**
 * Section 11 item 4 (REVIEW → explicit contract) — account-deletion
 * settlement for economic flows. Run INSIDE the deletion transaction, before
 * the User row goes away:
 *
 *  - open listings owned by the leaver become "cancelled" (not "sold"):
 *    buyers can never purchase from a ghost, and the board shrinks honestly;
 *  - still-active game matches the leaver plays in become "abandoned" —
 *    GameMatch.playerXId/playerOId are plain strings WITHOUT an FK cascade,
 *    so without this step the rows would orphan with a live board;
 *  - the money trail is SUMMARIZED (row count + net PC/GEM), not erased here:
 *    UserWallet/WalletLedger cascade with the user per schema, and the
 *    counterparties' own ledger legs reference THEIR user id, so they stay
 *    intact and readable — a deleted seller/buyer never rewrites someone
 *    else's history.
 *
 * The report is the deletion receipt: callers log it so historical wallet and
 * listing behavior is always explainable after the fact.
 */
export async function settleDeletedUserEconomy(db: PrismaClient, userId: string): Promise<EconomySettlementReport> {
  return db.$transaction(async tx => {
    const listings = await settleSellerListings(tx, userId)
    const matches = await tx.gameMatch.updateMany({
      where: { status: 'active', OR: [{ playerXId: userId }, { playerOId: userId }] },
      data: { status: 'abandoned' },
    })
    const [ledgerRows, net] = await Promise.all([
      tx.walletLedger.count({ where: { userId } }),
      tx.walletLedger.groupBy({
        by: ['asset'],
        where: { userId },
        _sum: { amount: true },
      }),
    ])
    const netPc = net.find(row => row.asset === 'PC')?._sum.amount ?? 0
    const netGem = net.find(row => row.asset === 'GEM')?._sum.amount ?? 0
    const walletRows = await tx.userWallet.count({ where: { userId } })
    await tx.logEvent.create({ data: {
      userId, kind: 'account', message: 'economic settlement before account deletion',
      meta: JSON.stringify({ ...listings, abandonedMatches: matches.count, ledgerRows, ledgerNetPc: netPc, ledgerNetGem: netGem }),
    } })
    return {
      userId,
      cancelledListings: listings.cancelledListings,
      abandonedMatches: matches.count,
      ledgerRows,
      ledgerNetPc: netPc,
      ledgerNetGem: netGem,
      walletRows,
    }
  }, { isolationLevel: 'Serializable' })
}

/**
 * Section 11 item 4 — post-deletion guard. Every identified money/listing
 * write path calls this first: a deleted (or never-existing) actor gets a
 * 404 instead of a half-created economic artifact.
 */
export async function requireLiveEconomyActor(tx: Prisma.TransactionClient, userId: string) {
  const row = await tx.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!row) {
    const error = new Error('User not found.') as Error & { status?: number }
    error.status = 404
    throw error
  }
  return row
}

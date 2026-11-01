import type { PrismaClient, Prisma } from '../../prisma/generated-client'
import { opHash } from './section10-contracts.ts'

export class MarketOperationError extends Error {
  constructor(public readonly status: number, message: string, public readonly code?: string, public readonly clientId?: string) { super(message) }
}

export type CreateListingInput = {
  userId: string
  title: string
  description: string
  price: number
  clientId?: string | null
}

export type CreateListingReceipt = {
  listing: {
    id: string
    title: string
    description: string
    price: number
    asset: string
    status: string
    createdAt: string
    seller: { id: string; name: string; username: string | null; color: string } | null
    buyer: unknown
    mine: boolean
  }
  replayed: boolean
  clientId: string | null
}

const LISTING_SELECT = { id: true, title: true, description: true, price: true, asset: true,
  status: true, createdAt: true, sellerId: true } as const

function shapeListing(row: { id: string; title: string; description: string; price: number; asset: string;
  status: string; createdAt: Date }, seller: { id: string; name: string; username: string | null; color: string } | null) {
  return {
    id: row.id, title: row.title, description: row.description, price: row.price,
    asset: row.asset, status: row.status, createdAt: row.createdAt.toISOString(),
    seller, buyer: null, mine: true,
  }
}

/**
 * Section 11 items 1/2 — listing creation with a durable replay receipt.
 * The MarketCreationOperation row is written in the SAME Serializable
 * transaction as the listing it vouches for, so a lost ACK replays the SAME
 * listing (200 replayed), a reused identity with different instructions is a
 * 409, and a replay whose row was later removed is an honest 410
 * creation_gone instead of a silent duplicate.
 */
export async function createListingReceipted(db: PrismaClient, input: CreateListingInput): Promise<CreateListingReceipt> {
  const { userId, title, description, price } = input
  const clientId = input.clientId ?? null
  const seller = await db.user.findUnique({ where: { id: userId }, select: { id: true, name: true, username: true, color: true } })
  if (!seller) throw new MarketOperationError(404, 'User not found.')

  if (clientId) {
    const requestHash = opHash([userId, title, description, price])
    const existing = await db.marketCreationOperation.findUnique({ where: { userId_clientId: { userId, clientId } } })
    if (existing) {
      if (existing.requestHash !== requestHash) {
        throw new MarketOperationError(409, 'This listing identity was already used with different instructions.', 'identity_clash', clientId)
      }
      const row = await db.marketListing.findUnique({ where: { id: existing.listingId } })
      if (!row) {
        throw new MarketOperationError(410, 'The earlier listing was delivered, but is no longer available. Submit again to create a new one.', 'creation_gone', clientId)
      }
      return { listing: shapeListing(row, seller), replayed: true, clientId }
    }
  }

  try {
    return await db.$transaction(async tx => {
      const listing = await tx.marketListing.create({ data: { sellerId: userId, title, description, price } })
      await tx.logEvent.create({ data: { userId, kind: 'market', message: `listed "${title}" for ${price} PC` } })
      if (clientId) {
        await tx.marketCreationOperation.create({ data: {
          userId, clientId, requestHash: opHash([userId, title, description, price]), listingId: listing.id,
        } })
      }
      return { listing: shapeListing(listing, seller), replayed: false, clientId }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    const code = (error as { code?: string })?.code
    if (code && ['P1008', 'P2028', 'P2034', 'P2002'].includes(code)) {
      throw new MarketOperationError(409, 'Market state changed or is busy. Refresh and retry the same action.')
    }
    throw error
  }
}

/**
 * Section 11 item 4 — explicit seller-deletion lifecycle. Cancelled listings
 * keep their history (audit trail) but leave the open board, and the caller
 * receives the counts so deletion receipts can state what happened to the
 * economy instead of assuming FK cascades did the right thing.
 */
export async function settleSellerListings(tx: Prisma.TransactionClient, userId: string) {
  const cancelled = await tx.marketListing.updateMany({
    where: { sellerId: userId, status: 'open' },
    data: { status: 'cancelled' },
  })
  return { cancelledListings: cancelled.count }
}

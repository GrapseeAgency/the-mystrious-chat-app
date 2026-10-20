import type { PrismaClient, Prisma } from '../../prisma/generated-client'

export class RedPacketClaimError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
/** Never filter invalid entries: that changes the next recipient's slice index. */
export function packetSlices(raw: string, count: number, total: number): number[] {
  let values: unknown
  try { values = JSON.parse(raw) } catch { values = null }
  if (!Number.isSafeInteger(count) || count <= 0 || !Number.isSafeInteger(total) || total <= 0 || total > 2_147_483_647 ||
      !Array.isArray(values) || values.length !== count ||
      !values.every(value => Number.isSafeInteger(value) && value > 0 && value <= total) ||
      values.reduce((sum, value) => sum + value, 0) !== total) {
    throw new RedPacketClaimError(500, 'Red packet slices are corrupted - grab aborted.')
  }
  return values
}

export async function claimRedPacket(db: PrismaClient, userId: string, id: string, clock: () => Date = () => new Date()) {
  return db.$transaction(async tx => {
    const packet = await tx.redPacket.findUnique({
      where: { id },
      include: {
        message: { select: { conversationId: true } },
      },
    })
    if (!packet) {
      throw new RedPacketClaimError(404, 'Red packet not found.')
    }
    const participant = await tx.conversationParticipant.findUnique({
      where: {
        userId_conversationId: { userId, conversationId: packet.message.conversationId },
      },
      select: { id: true },
    })
    if (!participant) {
      throw new RedPacketClaimError(403, 'You are not a participant of this conversation.')
    }
    if (userId === packet.senderId) {
      throw new RedPacketClaimError(400, 'You cannot grab your own red packet.')
    }
    const existing = await tx.redPacketGrab.findUnique({
      where: { packetId_userId: { packetId: packet.id, userId } },
      select: { amount: true },
    })
    if (existing) {
      if (!Number.isSafeInteger(existing.amount) || existing.amount <= 0 || existing.amount > 2_147_483_647) throw new RedPacketClaimError(500, 'Stored payout receipt is invalid.')
      return { amount: existing.amount, grabbed: packet.grabbed, count: packet.count, alreadyGrabbed: true }
    }
    if (clock() >= packet.expiresAt) throw new RedPacketClaimError(400, 'This red packet has expired.')
    if (packet.grabbed >= packet.count) throw new RedPacketClaimError(400, 'This red packet is already fully grabbed.')
    const slices = packetSlices(packet.slices, packet.count, packet.total)
    if (!Number.isInteger(packet.grabbed) || packet.grabbed < 0) throw new RedPacketClaimError(500, 'Invalid packet cursor.')
    await verifyPayoutPrefix(tx, packet.id, slices, packet.grabbed)
    const amount = slices[packet.grabbed]

    // CAS bump: only succeeds when `grabbed` is still the value we read,
    // so two racing grabs can never draw the same slice.
    const bumped = await tx.redPacket.updateMany({
      where: { id: packet.id, grabbed: packet.grabbed },
      data: { grabbed: { increment: 1 } },
    })
    if (bumped.count !== 1) {
      throw new RedPacketClaimError(409, 'Someone grabbed at the same moment - try again.')
    }

    await tx.redPacketGrab.create({
      data: { packetId: packet.id, userId, amount },
    })

    // RedPacket carries only senderId - resolve the display name for the ledger.
    const sender = await tx.user.findUnique({
      where: { id: packet.senderId },
      select: { name: true },
    })

    await tx.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })
    const credited = await tx.userWallet.updateMany({
      where: { userId, coins: { gte: 0, lte: 2_147_483_647 - amount } },
      data: { coins: { increment: amount } },
    })
    if (credited.count !== 1) throw new RedPacketClaimError(422, 'Wallet balance limit would be exceeded.')
    await tx.walletLedger.create({
      data: {
        userId,
        kind: 'redpacket_claim',
        asset: 'PC',
        amount,
        note: `Red packet from ${sender?.name ?? 'a crewmate'}`,
        counterpartyId: packet.senderId,
      },
    })
    await tx.logEvent.create({
      data: {
        userId,
        kind: 'redpacket',
        message: `grabbed ${amount} PC from a red packet`,
        meta: JSON.stringify({
          packetId: packet.id,
          conversationId: packet.message.conversationId,
          amount,
        }),
      },
    })

    return { amount, grabbed: packet.grabbed + 1, count: packet.count, alreadyGrabbed: false }
  })
}


async function verifyPayoutPrefix(tx: Prisma.TransactionClient, packetId: string, slices: number[], grabbed: number) {
  if (!Number.isInteger(grabbed) || grabbed < 0 || grabbed > slices.length) throw new RedPacketClaimError(500, 'Invalid packet cursor.')
  const paid = await tx.redPacketGrab.aggregate({ where: { packetId }, _sum: { amount: true }, _count: true })
  if (paid._count !== grabbed || (paid._sum.amount ?? 0) !== slices.slice(0, grabbed).reduce((a, b) => a + b, 0)) {
    throw new RedPacketClaimError(500, 'Packet payouts do not match its cursor; settlement requires reconciliation.')
  }
}

/** A failed refund must leave the cursor OPEN for later reconciliation, never
 * silently consume the escrow because corrupt JSON was converted to []. */
export async function refundRedPacket(db: PrismaClient, id: string, clock: () => Date = () => new Date()) {
  return db.$transaction(async tx => {
    const packet = await tx.redPacket.findUnique({ where: { id } })
    if (!packet || clock() < packet.expiresAt || packet.grabbed >= packet.count) return 0
    const slices = packetSlices(packet.slices, packet.count, packet.total)
    await verifyPayoutPrefix(tx, id, slices, packet.grabbed)
    const refund = slices.slice(packet.grabbed).reduce((sum, value) => sum + value, 0)
    const closed = await tx.redPacket.updateMany({ where: { id, grabbed: packet.grabbed }, data: { grabbed: packet.count } })
    if (closed.count !== 1) throw new RedPacketClaimError(409, 'Packet changed during settlement; retry.')
    await tx.userWallet.upsert({ where: { userId: packet.senderId }, create: { userId: packet.senderId }, update: {} })
    const credited = await tx.userWallet.updateMany({ where: { userId: packet.senderId, coins: { gte: 0, lte: 2_147_483_647 - refund } }, data: { coins: { increment: refund } } })
    if (credited.count !== 1) throw new RedPacketClaimError(422, 'Sender balance limit prevents refund; settlement remains pending.')
    await tx.walletLedger.create({ data: { userId: packet.senderId, kind: 'redpacket_refund', asset: 'PC', amount: refund, note: 'expired red packet refund' } })
    await tx.logEvent.create({ data: { userId: packet.senderId, kind: 'redpacket', message: `expired red packet refunded ${refund} PC`, meta: JSON.stringify({ packetId: id, refund }) } })
    return refund
  })
}

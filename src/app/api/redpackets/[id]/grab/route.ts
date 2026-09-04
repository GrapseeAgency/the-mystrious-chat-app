// ─────────────────────────────────────────────────────────────
// /api/redpackets/[id]/grab — open a red packet (Task R23-a)
//
// POST { userId }
//   → 200 { amount, grabbed, count }
//
// Every rule is enforced inside ONE interactive transaction:
// packet exists · not expired · grabber is a room participant ·
// grabber ≠ sender · grabbed < count · no existing grab row for
// (packetId, userId). The slice handed out is slices[grabbed] and
// the grabbed cursor is bumped with a compare-and-set updateMany
// so two simultaneous grabs can never draw the same slice. The
// winner's wallet is credited (+ WalletLedger 'redpacket_claim'
// with the sender as counterparty + LogEvent) in the same tx.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Typed error carrying an HTTP status out of the transaction. */
class GrabError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Parse the stored slices JSON into a finite-number array ([] on corruption). */
function parseSlices(raw: string): number[] {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      return parsed.filter((n): n is number => typeof n === 'number' && Number.isFinite(n))
    }
  } catch {
    // corrupt row — fails the draw below instead of paying bogus money
  }
  return []
}

/** POST /api/redpackets/[id]/grab { userId } — draw the next slice. */
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  try {
    const result = await db.$transaction(async (tx) => {
      const packet = await tx.redPacket.findUnique({
        where: { id },
        include: {
          message: { select: { conversationId: true } },
        },
      })
      if (!packet) {
        throw new GrabError(404, 'Red packet not found.')
      }
      if (new Date() > packet.expiresAt) {
        throw new GrabError(400, 'This red packet has expired.')
      }
      const participant = await tx.conversationParticipant.findUnique({
        where: {
          userId_conversationId: { userId, conversationId: packet.message.conversationId },
        },
        select: { id: true },
      })
      if (!participant) {
        throw new GrabError(403, 'You are not a participant of this conversation.')
      }
      if (userId === packet.senderId) {
        throw new GrabError(400, 'You cannot grab your own red packet.')
      }
      if (packet.grabbed >= packet.count) {
        throw new GrabError(400, 'This red packet is already fully grabbed.')
      }
      const existing = await tx.redPacketGrab.findUnique({
        where: { packetId_userId: { packetId: packet.id, userId } },
        select: { id: true },
      })
      if (existing) {
        throw new GrabError(400, 'You already grabbed this red packet.')
      }

      // CAS bump: only succeeds when `grabbed` is still the value we read,
      // so two racing grabs can never draw the same slice.
      const bumped = await tx.redPacket.updateMany({
        where: { id: packet.id, grabbed: packet.grabbed },
        data: { grabbed: { increment: 1 } },
      })
      if (bumped.count !== 1) {
        throw new GrabError(409, 'Someone grabbed at the same moment — try again.')
      }

      const slices = parseSlices(packet.slices)
      const amount = slices[packet.grabbed]
      if (!Number.isInteger(amount) || amount <= 0) {
        throw new GrabError(500, 'Red packet slices are corrupted — grab aborted.')
      }

      await tx.redPacketGrab.create({
        data: { packetId: packet.id, userId, amount },
      })

      // RedPacket carries only senderId — resolve the display name for the ledger.
      const sender = await tx.user.findUnique({
        where: { id: packet.senderId },
        select: { name: true },
      })

      await tx.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })
      await tx.userWallet.update({
        where: { userId },
        data: { coins: { increment: amount } },
      })
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

      return { amount, grabbed: packet.grabbed + 1, count: packet.count }
    })

    return NextResponse.json(result)
  } catch (err) {
    if (err instanceof GrabError) {
      return NextResponse.json({ error: err.message }, { status: err.status })
    }
    throw err
  }
}

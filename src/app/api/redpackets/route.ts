// ─────────────────────────────────────────────────────────────
// /api/redpackets — WeChat-style red packets (Task R23-a)
//
// POST { userId, conversationId, total, count, note? }
//   → 201 { message: ChatMessage, packet: {...} }
//
// Everything happens in ONE interactive transaction: wallet debit
// (guarded against concurrent spends), a real "cut the rope"
// random split (every slice ≥ 1 PC), the carrier Message row
// (kind 'redpacket'), the RedPacket row, a WalletLedger row and a
// LogEvent. The sender's message is serialized EXACTLY like the
// messages API returns it (same mapMessage + MESSAGE_FULL_INCLUDE),
// so the client can append it with zero reshaping.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  MESSAGE_FULL_INCLUDE,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

// validation bounds (mirrored by the composer sheet)
const TOTAL_MIN = 1
const TOTAL_MAX = 10_000
const COUNT_MIN = 1
const COUNT_MAX = 50
const NOTE_MAX = 60
const EXPIRY_MS = 24 * 60 * 60 * 1000 // packets live for 24h like WeChat

/** Typed error carrying an HTTP status out of the transaction. */
class RedPacketError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/**
 * "Cut the rope" split: pick count-1 DISTINCT integer cuts in
 * [1, total-1], sort them, and the diffs (plus the two ends) are the
 * slices. Distinct cuts guarantee every slice is a whole PC ≥ 1 and
 * the slices sum to exactly `total`.
 */
function cutRopeSlices(total: number, count: number): number[] {
  if (count === 1) return [total]
  const cuts = new Set<number>()
  while (cuts.size < count - 1) {
    cuts.add(TOTAL_MIN + Math.floor(Math.random() * (total - 1)))
  }
  const sorted = [...cuts].sort((a, b) => a - b)
  const slices: number[] = []
  let prev = 0
  for (const cut of sorted) {
    slices.push(cut - prev)
    prev = cut
  }
  slices.push(total - prev)
  return slices
}

/** POST /api/redpackets — create a packet, debit the sender, post the message. */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }

  const total = typeof body.total === 'number' ? body.total : Number(body.total)
  const count = typeof body.count === 'number' ? body.count : Number(body.count)
  if (!Number.isInteger(total) || total < TOTAL_MIN || total > TOTAL_MAX) {
    return NextResponse.json(
      { error: `total must be an integer between ${TOTAL_MIN} and ${TOTAL_MAX} PC.` },
      { status: 400 },
    )
  }
  if (!Number.isInteger(count) || count < COUNT_MIN || count > COUNT_MAX) {
    return NextResponse.json(
      { error: `count must be an integer between ${COUNT_MIN} and ${COUNT_MAX}.` },
      { status: 400 },
    )
  }
  if (count > total) {
    return NextResponse.json(
      { error: 'count must be ≤ total so every grab wins at least 1 PC.' },
      { status: 400 },
    )
  }
  const note = strField(body.note).slice(0, NOTE_MAX)
  const content = note || '🧧 Red packet'
  const payload = (packetId: string) => JSON.stringify({ packetId, total, count, note })

  // Existence + membership up-front → clean 404 / 403 semantics.
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } },
      select: { id: true },
    }),
  ])
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const now = new Date()
  const expiresAt = new Date(now.getTime() + EXPIRY_MS)
  const slices = cutRopeSlices(total, count)

  const created = await db
    .$transaction(async (tx) => {
      // wallet upsert + funds check + debit (guard against concurrent spend)
      await tx.userWallet.upsert({ where: { userId }, create: { userId }, update: {} })
      const wallet = await tx.userWallet.findUniqueOrThrow({ where: { userId } })
      if (wallet.coins < total) {
        throw new RedPacketError(402, `Insufficient PC — you have ${wallet.coins}, tried to send ${total}.`)
      }
      const after = await tx.userWallet.update({
        where: { userId },
        data: { coins: { decrement: total } },
      })
      if (after.coins < 0) {
        throw new RedPacketError(402, 'Insufficient PC — balance changed, try again.')
      }

      // carrier message (payload patched with the real packetId below)
      const message = await tx.message.create({
        data: {
          conversationId,
          senderId: userId,
          content,
          kind: 'redpacket',
          createdAt: now,
        },
        include: MESSAGE_FULL_INCLUDE,
      })
      const packet = await tx.redPacket.create({
        data: {
          messageId: message.id,
          senderId: userId,
          total,
          count,
          slices: JSON.stringify(slices),
          note,
          expiresAt,
        },
      })
      const finalPayload = payload(packet.id)
      await tx.message.update({
        where: { id: message.id },
        data: { payload: finalPayload },
      })

      // keep list ordering / read state / archives in lockstep with sends
      await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
      await tx.conversationParticipant.update({
        where: { userId_conversationId: { userId, conversationId } },
        data: { lastReadAt: now },
      })
      await tx.conversationParticipant.updateMany({
        where: { conversationId, userId: { not: userId }, archivedAt: { not: null } },
        data: { archivedAt: null },
      })

      // immutable money trail + devops log
      await tx.walletLedger.create({
        data: {
          userId,
          kind: 'redpacket_send',
          asset: 'PC',
          amount: -total,
          note: note || `Red packet · ${count} grabs`,
          counterpartyId: null,
        },
      })
      await tx.logEvent.create({
        data: {
          userId,
          kind: 'redpacket',
          message: `sent a red packet of ${total} PC`,
          meta: JSON.stringify({ packetId: packet.id, conversationId, total, count }),
        },
      })

      return {
        message: { ...message, payload: finalPayload },
        packetId: packet.id,
      }
    })
    .catch((err: unknown) => {
      if (err instanceof RedPacketError) {
        return NextResponse.json({ error: err.message }, { status: err.status })
      }
      throw err
    })

  // Transaction rolled back → the catch above already built the error response.
  if (created instanceof NextResponse) return created

  const mapped = mapMessage(created.message, userId)

  // Realtime relay to every OTHER member (sender appends via the response —
  // the sheet additionally fires `pulse:external-message` for instant append).
  const recipients = (await memberIdsOf(conversationId)).filter((memberId) => memberId !== userId)
  await notifySocket('message:new', recipients, {
    type: 'message:new',
    message: mapped,
    recipientIds: recipients,
    conversationId,
  })

  return NextResponse.json(
    {
      message: mapped,
      packet: {
        id: created.packetId,
        total,
        count,
        grabbed: 0,
        note,
        expiresAt: expiresAt.toISOString(),
      },
    },
    { status: 201 },
  )
}

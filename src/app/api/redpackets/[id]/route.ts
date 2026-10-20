// /api/redpackets/[id] - packet detail + LAZY REFUND (Task R23-a)
//
// GET ?userId=<viewer>   (viewer optional - read is open to the room URL)
//   → { packet: { id, senderId, total, count, grabbed, note,
//                 expiresAt, status: 'open'|'exhausted'|'expired' },
//         senderName, grabs: [{userId, name, amount, createdAt}],
//         myGrab: number|null, isMine: boolean }
//
// Grabs are ordered by amount DESC (WeChat "best luck" display),
// createdAt ASC as the tiebreaker. When the packet is past its
// expiry and slices remain unclaimed, THIS read performs the lazy
// refund in one transaction: unclaimed slices go back to the
// sender's wallet (+ WalletLedger 'redpacket_refund' + LogEvent)
// and the packet is closed (grabbed = count) so the refund is
// idempotent. Status is computed AFTER the refund.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { refundRedPacket, RedPacketClaimError } from '@/lib/redpacket-contract'
import { strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Packet load used for both passes (pre/post refund). RedPacket has no direct
 * User relation - names are resolved separately below. */
function loadPacket(id: string) {
  return db.redPacket.findUnique({
    where: { id },
    include: {
      grabs: {
        orderBy: [{ amount: 'desc' as const }, { createdAt: 'asc' as const }],
      },
    },
  })
}

/** Display names for the sender + grabbers in ONE query (no fake relations). */
async function namesFor(userIds: string[]): Promise<Map<string, string>> {
  const rows = await db.user.findMany({
    where: { id: { in: userIds } },
    select: { id: true, name: true },
  })
  return new Map(rows.map((r) => [r.id, r.name]))
}

/**
 * GET /api/redpackets/[id]?userId=<viewer>
 * Read the packet, lazily refunding expired leftovers to the sender first.
 */
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))

  let packet = await loadPacket(id)
  if (!packet) {
    return NextResponse.json({ error: 'Red packet not found.' }, { status: 404 })
  }

  // Corrupt/over-limit refunds fail without consuming the settlement cursor.
  if (new Date() >= packet.expiresAt && packet.grabbed < packet.count) {
    try { await refundRedPacket(db, id) }
    catch (error) {
      if (error instanceof RedPacketClaimError) return NextResponse.json({ error: error.message }, { status: error.status })
      throw error
    }
    packet = (await loadPacket(id)) ?? packet
  }

  const now = new Date()
  const status: 'open' | 'exhausted' | 'expired' =
    now >= packet.expiresAt ? 'expired' : packet.grabbed >= packet.count ? 'exhausted' : 'open'

  const names = await namesFor([packet.senderId, ...packet.grabs.map((g) => g.userId)])

  const myGrabRow = userId ? (packet.grabs.find((g) => g.userId === userId) ?? null) : null

  return NextResponse.json(
    {
      packet: {
        id: packet.id,
        senderId: packet.senderId,
        total: packet.total,
        count: packet.count,
        grabbed: packet.grabbed,
        note: packet.note,
        expiresAt: packet.expiresAt.toISOString(),
        status,
      },
      senderName: names.get(packet.senderId) ?? 'Unknown',
      grabs: packet.grabs.map((g) => ({
        userId: g.userId,
        name: names.get(g.userId) ?? 'Unknown',
        amount: g.amount,
        createdAt: g.createdAt.toISOString(),
      })),
      myGrab: myGrabRow ? myGrabRow.amount : null,
      isMine: userId ? packet.senderId === userId : false,
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

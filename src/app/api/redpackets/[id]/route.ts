// ─────────────────────────────────────────────────────────────
// /api/redpackets/[id] — packet detail + LAZY REFUND (Task R23-a)
//
// GET ?userId=<viewer>   (viewer optional — read is open to the room URL)
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
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Parse the stored slices JSON into a finite-number array ([] on corruption). */
function parseSlices(raw: string): number[] {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      return parsed.filter((n): n is number => typeof n === 'number' && Number.isFinite(n))
    }
  } catch {
    // corrupt row — treat as empty; the refund math below then no-ops
  }
  return []
}

/** Packet load used for both passes (pre/post refund). RedPacket has no direct
 * User relation — names are resolved separately below. */
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

  // LAZY REFUND — first reader after expiry settles the leftovers.
  if (new Date() > packet.expiresAt && packet.grabbed < packet.count) {
    await db.$transaction(async (tx) => {
      // Re-read inside the tx so concurrent readers settle it exactly once.
      const fresh = await tx.redPacket.findUnique({
        where: { id },
        select: {
          senderId: true,
          grabbed: true,
          count: true,
          slices: true,
          expiresAt: true,
        },
      })
      if (!fresh) return
      if (new Date() <= fresh.expiresAt || fresh.grabbed >= fresh.count) return

      const slices = parseSlices(fresh.slices)
      const unclaimed = slices.slice(fresh.grabbed).reduce((sum, n) => sum + n, 0)

      await tx.redPacket.update({ where: { id }, data: { grabbed: fresh.count } })
      if (unclaimed > 0) {
        await tx.userWallet.upsert({
          where: { userId: fresh.senderId },
          create: { userId: fresh.senderId },
          update: {},
        })
        await tx.userWallet.update({
          where: { userId: fresh.senderId },
          data: { coins: { increment: unclaimed } },
        })
        await tx.walletLedger.create({
          data: {
            userId: fresh.senderId,
            kind: 'redpacket_refund',
            asset: 'PC',
            amount: unclaimed,
            note: 'expired red packet refund',
            counterpartyId: null,
          },
        })
        await tx.logEvent.create({
          data: {
            userId: fresh.senderId,
            kind: 'redpacket',
            message: `expired red packet refunded ${unclaimed} PC`,
            meta: JSON.stringify({ packetId: id, refund: unclaimed }),
          },
        })
      }
    })
    packet = (await loadPacket(id)) ?? packet
  }

  const now = new Date()
  const status: 'open' | 'exhausted' | 'expired' =
    now > packet.expiresAt ? 'expired' : packet.grabbed >= packet.count ? 'exhausted' : 'open'

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

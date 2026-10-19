// Existing per-user grab rows are durable claim receipts, including after expiry.
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { claimRedPacket, RedPacketClaimError } from '@/lib/redpacket-contract'
export const dynamic = 'force-dynamic'
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const userId = strField((await safeJson(req)).userId)
  if (!userId) return Response.json({ error: 'userId is required.' }, { status: 400 })
  try { return Response.json(await claimRedPacket(db, userId, id)) }
  catch (error) {
    if (error instanceof RedPacketClaimError) return Response.json({ error: error.message }, { status: error.status })
    throw error
  }
}

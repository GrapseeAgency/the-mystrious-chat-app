// Target is in the path; the existing owner credential must match the actor.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField, notifySocket } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { peerBlock, PeerBlockError } from '@/lib/peer-block'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

async function act(req: Request, { params }: RouteCtx, desired: boolean | null) {
  const { id: targetId } = await params
  const actorId = desired === true ? strField((await safeJson(req)).userId) : new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (!actorId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, actorId, 'peer block')
  if (denied) return denied
  try {
    const receipt = await peerBlock(db, actorId, targetId, desired)
    if (desired !== null) {
      // Refresh the actor's own DM policy caches, without broadcasting their
      // private block list or a block event to the other account.
      const rooms = await db.conversation.findMany({ where: { isGroup: false, AND: [
        { participants: { some: { userId: actorId } } }, { participants: { some: { userId: targetId } } },
      ] }, select: { id: true } })
      for (const room of rooms) await notifySocket('conversation:updated', [actorId], {
        type: 'conversation:updated', conversationId: room.id, recipientIds: [actorId],
      })
    }
    return NextResponse.json(receipt, { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (error instanceof PeerBlockError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}
export async function GET(req: Request, ctx: RouteCtx) { return act(req, ctx, null) }
export async function POST(req: Request, ctx: RouteCtx) { return act(req, ctx, true) }
export async function DELETE(req: Request, ctx: RouteCtx) { return act(req, ctx, false) }

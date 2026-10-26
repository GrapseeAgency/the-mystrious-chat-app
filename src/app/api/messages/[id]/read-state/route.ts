import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { exactMessageReadState } from '@/lib/message-visibility'

export const dynamic = 'force-dynamic'

/** Read-only, single-message reconciliation for an open native Info surface. */
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params, query = new URL(req.url).searchParams
  const userId = query.get('userId')?.trim(), conversationId = query.get('conversationId')?.trim()
  if (!userId || !conversationId || conversationId.length > 128 || !id || id.length > 128)
    return NextResponse.json({ error: 'Specify a message, conversation and viewer.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'message read state')
  if (denied) return denied
  const proof = await db.$transaction(async tx => {
    const member = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } }, select: { id: true },
    })
    // An authenticated owner's unavailable result reveals no message existence.
    if (!member) return null
    return exactMessageReadState(tx, conversationId, userId, id, new Date())
  }, { isolationLevel: 'Serializable' })
  return NextResponse.json({ messageReadStateVersion: 1, userId, conversationId, messageId: id,
    messageAvailable: proof !== null, readByMembershipIds: proof ?? [] },
    { headers: { 'Cache-Control': 'private, no-store' } })
}

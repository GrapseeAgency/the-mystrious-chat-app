// Pin toggle (legacy) and explicit removal share a transaction with the snapshot.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { mutateMessage, MessageMutationError } from '@/lib/message-mutation'
import { mapMessage, memberIdsOf, notifySocket, MESSAGE_FULL_INCLUDE, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

async function perform(id: string, userId: string, removing: boolean) {
  try {
    const result = await mutateMessage(db, id, userId, { kind: 'pin', ...(removing ? { present: false } : {}) },
      (tx, messageId) => tx.message.findUniqueOrThrow({ where: { id: messageId }, include: MESSAGE_FULL_INCLUDE }))
    if (result.changed) {
      const recipients = await memberIdsOf(result.conversationId)
      await notifySocket('message:pinned', recipients, { type: 'message:pinned', message: mapMessage(result.message),
        recipientIds: recipients, conversationId: result.conversationId })
    }
    return NextResponse.json({ message: mapMessage(result.message, userId), ...(removing ? { unpinned: true } : {}) })
  } catch (error) {
    if (error instanceof MessageMutationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const userId = strField((await safeJson(req)).userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  // Keep the legacy no-bearer transport while binding credential-bearing actions.
  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'pin')
    if (denied) return denied
  }
  return perform(id, userId, false)
}

/** Explicit removal stays removed after a lost-response retry. */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const userId = strField((await safeJson(req)).userId)
  const denied = await requireActionOwner(req, userId, 'unpin')
  if (denied) return denied
  return perform(id, userId, true)
}

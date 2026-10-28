import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/messages/[id] - edit (sender only) + soft-delete (sender only)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mutateMessage, MessageMutationError } from '@/lib/message-mutation'
import {
  mapMessage,
  memberIdsOf,
  notifySocket,
  MESSAGE_FULL_INCLUDE,
  MESSAGE_MAX,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Sender-only edits/deletes; membership and lifetime are checked at the write. */
async function perform(id: string, owner: string, action: { kind: 'edit'; content: string } | { kind: 'delete' }) {
  try {
    const result = await mutateMessage(db, id, owner, action, (tx, messageId) =>
      tx.message.findUniqueOrThrow({ where: { id: messageId }, include: MESSAGE_FULL_INCLUDE }))
    const mapped = mapMessage(result.message, owner)
    if (result.changed) {
      const event = action.kind === 'edit' ? 'message:edited' : 'message:deleted'
      const recipients = await memberIdsOf(result.conversationId)
      // The actor's other devices also need the committed mutation.
      await notifySocket(event, recipients, { type: event, message: mapMessage(result.message),
        recipientIds: recipients, conversationId: result.conversationId })
    }
    return NextResponse.json({ message: mapped })
  } catch (error) {
    if (error instanceof MessageMutationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'message edit')
  if (denied) return denied
  const content = strField(body.content)
  if (!content.length || content.length > MESSAGE_MAX) {
    return NextResponse.json({ error: `content must contain 1..${MESSAGE_MAX} characters.` }, { status: 400 })
  }
  return perform(id, userId, { kind: 'edit', content })
}

export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, requesterId, 'message deletion')
  if (denied) return denied
  return perform(id, requesterId, { kind: 'delete' })
}

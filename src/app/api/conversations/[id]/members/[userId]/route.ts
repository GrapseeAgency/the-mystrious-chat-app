import { invalidateNativeReadState } from '@/lib/push/transport'
// Targeted role/remove aliases use the same guards as the collection endpoint.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { GroupMembershipError, removeGroupMember, setGroupRole } from '@/lib/group-membership'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  notifySocket,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ id: string; userId: string }>
}
function failure(error: unknown) {
  if (error instanceof GroupMembershipError)
    return NextResponse.json({ error: error.message }, { status: error.status })
  throw error
}
async function notify(id: string, recipients: string[]) {
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id, userId } = await params
  const body = await safeJson(req),
    requesterId = strField(body.requesterId),
    action = strField(body.action)
  if (!requesterId) return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  if (action !== 'promote' && action !== 'demote')
    return NextResponse.json(
      { error: 'action must be either "promote" or "demote".' },
      { status: 400 },
    )
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'group membership')
    if (denied) return denied
  }
  try {
    const result = await setGroupRole(
      db,
      id,
      requesterId,
      userId,
      action === 'promote' ? 'admin' : 'member',
    )
    if (result.changed) await notify(id, result.recipients)
    const updated = await db.conversation.findFirst({
      where: { id, participants: { some: { userId: requesterId } } },
      include: CONVERSATION_FULL_INCLUDE,
    })
    if (!updated)
      return NextResponse.json(
        { error: 'Conversation is no longer available to this participant.' },
        { status: 403 },
      )
    // Viewer-private fields belong to the requester, NEVER the role-change target.
    return NextResponse.json({ conversation: await buildConversationDetail(updated, requesterId) })
  } catch (error) {
    return failure(error)
  }
}

export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id, userId } = await params
  const body = await safeJson(req),
    requesterId = strField(body.requesterId)
  if (!requesterId) return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'group membership')
    if (denied) return denied
  }
  try {
    const result = await removeGroupMember(db, id, requesterId, userId)
    if (result.changed) {
      invalidateNativeReadState(userId, id)
      await notify(id, [...result.recipients, userId])
    }
    return NextResponse.json({ ok: true, remainingMembers: result.recipients.length })
  } catch (error) {
    return failure(error)
  }
}

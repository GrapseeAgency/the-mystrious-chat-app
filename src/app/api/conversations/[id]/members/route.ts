// Group membership checks and writes share one serializable snapshot.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  addGroupMembers,
  GroupMembershipError,
  leaveGroup,
  setGroupRole,
} from '@/lib/group-membership'
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
  params: Promise<{ id: string }>
}

function failure(error: unknown) {
  if (error instanceof GroupMembershipError)
    return NextResponse.json(
      { error: error.message, ...(error.missing ? { missing: error.missing } : {}) },
      { status: error.status },
    )
  throw error
}
async function notify(id: string, recipients: string[]) {
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })
}
async function detail(id: string, requester: string) {
  const row = await db.conversation.findFirst({
    where: { id, participants: { some: { userId: requester } } },
    include: CONVERSATION_FULL_INCLUDE,
  })
  return row ? await buildConversationDetail(row, requester) : null
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req),
    requesterId = strField(body.requesterId)
  if (!requesterId) return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'group membership')
    if (denied) return denied
  }
  const ids = Array.isArray(body.userIds)
    ? body.userIds.filter((value): value is string => typeof value === 'string')
    : []
  try {
    const result = await addGroupMembers(db, id, requesterId, ids)
    if (result.added.length) await notify(id, result.recipients)
    const conversation = await detail(id, requesterId)
    if (!conversation)
      return NextResponse.json(
        { error: 'Conversation is no longer available to this participant.' },
        { status: 403 },
      )
    return NextResponse.json({ conversation, added: result.added })
  } catch (error) {
    return failure(error)
  }
}

export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req),
    requesterId = strField(body.requesterId)
  if (!requesterId) return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'group membership')
    if (denied) return denied
  }
  try {
    const result = await leaveGroup(db, id, requesterId)
    await notify(id, [...result.recipients, requesterId])
    return NextResponse.json({
      ok: true,
      remainingMembers: result.recipients.length,
      promotedUserId: result.promotedUserId,
    })
  } catch (error) {
    return failure(error)
  }
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req),
    requesterId = strField(body.requesterId),
    userId = strField(body.userId),
    role = strField(body.role)
  if (!requesterId || !userId)
    return NextResponse.json({ error: 'requesterId and userId are required.' }, { status: 400 })
  if (role !== 'admin' && role !== 'member')
    return NextResponse.json({ error: "role must be 'admin' or 'member'." }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'group membership')
    if (denied) return denied
  }
  try {
    const result = await setGroupRole(db, id, requesterId, userId, role)
    if (result.changed) await notify(id, result.recipients)
    const conversation = await detail(id, requesterId)
    if (!conversation)
      return NextResponse.json(
        { error: 'Conversation is no longer available to this participant.' },
        { status: 403 },
      )
    return NextResponse.json({ conversation })
  } catch (error) {
    return failure(error)
  }
}

// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/members — add members · leave group
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  memberIdsOf,
  notifySocket,
  safeJson,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/conversations/[id]/members  body { requesterId, userIds: string[] }
 * Group-only. Any member may add; duplicates silently ignored.
 * New participants start with lastReadAt=now (no unread backlog).
 * → 200 { conversation: ConversationDetail, added: string[] }; relays conversation:updated.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = typeof body.requesterId === 'string' ? body.requesterId.trim() : ''
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }
  const rawIds = Array.isArray(body.userIds) ? body.userIds : []
  const userIds = [...new Set(rawIds.filter((v): v is string => typeof v === 'string' && v.trim().length > 0))]
  if (userIds.length === 0) {
    return NextResponse.json({ error: 'userIds must contain at least one user id.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!conv.isGroup) {
    return NextResponse.json({ error: 'Members cannot be added to a direct conversation.' }, { status: 400 })
  }
  const requester = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: requesterId, conversationId: id } },
    select: { id: true },
  })
  if (!requester) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }

  // Every candidate must be a real user.
  const users = await db.user.findMany({ where: { id: { in: userIds } }, select: { id: true } })
  const known = new Set(users.map((u) => u.id))
  const missing = userIds.filter((uid) => !known.has(uid))
  if (missing.length > 0) {
    return NextResponse.json({ error: 'Unknown user id in userIds.', missing }, { status: 400 })
  }

  // Skip existing participants.
  const existing = await db.conversationParticipant.findMany({
    where: { conversationId: id, userId: { in: userIds } },
    select: { userId: true },
  })
  const existingSet = new Set(existing.map((r) => r.userId))
  const toAdd = userIds.filter((uid) => !existingSet.has(uid))

  if (toAdd.length > 0) {
    const now = new Date()
    await db.$transaction([
      db.conversationParticipant.createMany({
        data: toAdd.map((uid) => ({ userId: uid, conversationId: id, lastReadAt: now })),
      }),
      db.conversation.update({ where: { id }, data: { updatedAt: now } }),
    ])

    const recipients = await memberIdsOf(id)
    await notifySocket('conversation:updated', recipients, {
      type: 'conversation:updated',
      conversationId: id,
      recipientIds: recipients,
    })
  }

  const updated = await db.conversation.findUnique({
    where: { id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!updated) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  return NextResponse.json({ conversation: buildConversationDetail(updated), added: toAdd })
}

/**
 * DELETE /api/conversations/[id]/members  body { requesterId }
 * Leave a group (self-removal only). History is kept; other members continue.
 * → 200 { ok: true, remainingMembers }; relays conversation:updated to remaining.
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = typeof body.requesterId === 'string' ? body.requesterId.trim() : ''
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!conv.isGroup) {
    return NextResponse.json({ error: 'Direct conversations cannot be left.' }, { status: 400 })
  }
  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: requesterId, conversationId: id } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }

  await db.conversationParticipant.delete({ where: { id: participant.id } })

  const remaining = await memberIdsOf(id)
  await notifySocket('conversation:updated', remaining, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: remaining,
  })

  return NextResponse.json({ ok: true, remainingMembers: remaining.length })
}

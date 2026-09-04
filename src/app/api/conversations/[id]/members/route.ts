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
 * Group-only. ADMINS ONLY add members; duplicates silently ignored.
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
    select: { role: true },
  })
  if (!requester) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }
  if (requester.role !== 'admin') {
    return NextResponse.json({ error: 'Only group admins can add members.' }, { status: 403 })
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
  return NextResponse.json({ conversation: buildConversationDetail(updated, requesterId), added: toAdd })
}

/**
 * DELETE /api/conversations/[id]/members  body { requesterId }
 * Leave a group (self-removal only). History is kept; other members continue.
 * Succession rule: if the leaver was the group's LAST admin and members remain,
 * the longest-standing remaining member is auto-promoted to admin (groups never
 * end up leaderless).
 * → 200 { ok: true, remainingMembers, promotedUserId? }; relays conversation:updated.
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
    select: { id: true, role: true },
  })
  if (!participant) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }

  let promotedUserId: string | null = null

  if (participant.role === 'admin') {
    // Will anyone else hold an admin flag after this row disappears?
    const otherAdmins = await db.conversationParticipant.count({
      where: { conversationId: id, role: 'admin', NOT: { id: participant.id } },
    })
    if (otherAdmins === 0) {
      const successor = await db.conversationParticipant.findFirst({
        where: { conversationId: id, NOT: { id: participant.id } },
        orderBy: [{ joinedAt: 'asc' }, { id: 'asc' }],
        select: { userId: true },
      })
      if (successor) {
        promotedUserId = successor.userId
      }
    }
  }

  await db.$transaction(async (tx) => {
    await tx.conversationParticipant.delete({ where: { id: participant.id } })
    if (promotedUserId !== null) {
      await tx.conversationParticipant.update({
        where: { userId_conversationId: { userId: promotedUserId, conversationId: id } },
        data: { role: 'admin' },
      })
    }
  })

  const remaining = await memberIdsOf(id)
  await notifySocket('conversation:updated', remaining, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: remaining,
  })

  return NextResponse.json({ ok: true, remainingMembers: remaining.length, promotedUserId })
}

/**
 * PATCH /api/conversations/[id]/members  body { requesterId, userId, role: 'admin'|'member' }
 * Group-only role management. ADMINS ONLY.
 * - promote member → admin: always allowed
 * - demote admin → member: allowed unless the target is the LAST admin
 *   (self-demotion included — the group must always keep at least one admin)
 * → 200 { conversation: ConversationDetail }; relays conversation:updated.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = typeof body.requesterId === 'string' ? body.requesterId.trim() : ''
  const targetUserId = typeof body.userId === 'string' ? body.userId.trim() : ''
  const role = typeof body.role === 'string' ? body.role.trim() : ''
  if (!requesterId || !targetUserId) {
    return NextResponse.json({ error: 'requesterId and userId are required.' }, { status: 400 })
  }
  if (role !== 'admin' && role !== 'member') {
    return NextResponse.json({ error: "role must be 'admin' or 'member'." }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!conv.isGroup) {
    return NextResponse.json({ error: 'Direct conversations have no roles.' }, { status: 400 })
  }

  const [requester, target] = await Promise.all([
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: requesterId, conversationId: id } },
      select: { id: true, role: true },
    }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: targetUserId, conversationId: id } },
      select: { id: true, role: true },
    }),
  ])
  if (!requester) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }
  if (requester.role !== 'admin') {
    return NextResponse.json({ error: 'Only group admins can change roles.' }, { status: 403 })
  }
  if (!target) {
    return NextResponse.json({ error: 'That user is not a member of this group.' }, { status: 404 })
  }
  if (target.role === role) {
    return NextResponse.json({ error: `That member is already ${role === 'admin' ? 'an admin' : 'a member'}.` }, { status: 409 })
  }
  if (role === 'member' && target.role === 'admin') {
    const otherAdmins = await db.conversationParticipant.count({
      where: { conversationId: id, role: 'admin', NOT: { id: target.id } },
    })
    if (otherAdmins === 0) {
      return NextResponse.json(
        { error: 'Cannot demote the last admin — promote someone else first.' },
        { status: 400 },
      )
    }
  }

  await db.conversationParticipant.update({
    where: { id: target.id },
    data: { role },
  })

  const updated = await db.conversation.findUnique({
    where: { id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!updated) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  const detail = buildConversationDetail(updated, requesterId)
  const memberIds = await memberIdsOf(id)
  await notifySocket('conversation:updated', memberIds, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: memberIds,
  })
  return NextResponse.json({ conversation: detail })
}

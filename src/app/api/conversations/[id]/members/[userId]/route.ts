// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/members/[userId] — promote · demote · remove
// Group role governance (admin-only mutations on other members).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  notifySocket,
  safeJson,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string; userId: string }>
}

/** Shared preconditions for both verbs. */
async function loadContext(id: string, userId: string, body: Record<string, unknown>) {
  const requesterId = typeof body.requesterId === 'string' ? body.requesterId.trim() : ''
  if (!requesterId) {
    return { error: NextResponse.json({ error: 'requesterId is required.' }, { status: 400 }) }
  }

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return { error: NextResponse.json({ error: 'Conversation not found.' }, { status: 404 }) }
  }
  if (!conv.isGroup) {
    return { error: NextResponse.json({ error: 'Direct conversations have no roles.' }, { status: 400 }) }
  }

  const requester = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: requesterId, conversationId: id } },
    select: { role: true },
  })
  if (!requester) {
    return { error: NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 }) }
  }
  if (requester.role !== 'admin') {
    return { error: NextResponse.json({ error: 'Only group admins can manage members.' }, { status: 403 }) }
  }

  const target = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true, role: true },
  })
  if (!target) {
    return { error: NextResponse.json({ error: 'That user is not a member of this group.' }, { status: 404 }) }
  }

  return { conv, target }
}

/**
 * PATCH /api/conversations/[id]/members/[userId]  body { requesterId, action: "promote" | "demote" }
 * Admin-only. Guards: promote fails if already admin; demote fails when the target
 * would be the group's last remaining admin.
 * → 200 { conversation: ConversationDetail }; relays conversation:updated.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id, userId } = await params

  const body = await safeJson(req)
  const ctx = await loadContext(id, userId, body)
  if ('error' in ctx) return ctx.error

  const action = typeof body.action === 'string' ? body.action.trim() : ''
  if (action !== 'promote' && action !== 'demote') {
    return NextResponse.json(
      { error: 'action must be either "promote" or "demote".' },
      { status: 400 },
    )
  }

  const target = ctx.target!

  if (action === 'promote') {
    if (target.role === 'admin') {
      return NextResponse.json({ error: 'That member is already an admin.' }, { status: 400 })
    }
    await db.conversationParticipant.update({ where: { id: target.id }, data: { role: 'admin' } })
  } else {
    if (target.role !== 'admin') {
      return NextResponse.json({ error: 'That member is not an admin.' }, { status: 400 })
    }
    const otherAdmins = await db.conversationParticipant.count({
      where: { conversationId: id, role: 'admin', NOT: { id: target.id } },
    })
    if (otherAdmins === 0) {
      return NextResponse.json(
        { error: 'Cannot demote the only admin of this group.' },
        { status: 400 },
      )
    }
    await db.conversationParticipant.update({ where: { id: target.id }, data: { role: 'member' } })
  }

  // Bump so list ordering / previews refresh uniformly.
  await db.conversation.update({ where: { id }, data: { updatedAt: new Date() } })

  const updated = await db.conversation.findUnique({
    where: { id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!updated) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const recipients = (
    await db.conversationParticipant.findMany({ where: { conversationId: id }, select: { userId: true } })
  ).map((p) => p.userId)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })

  return NextResponse.json({ conversation: buildConversationDetail(updated, userId) })
}

/**
 * DELETE /api/conversations/[id]/members/[userId]  body { requesterId }
 * Remove (kick) a NON-admin member. Kicking yourself is a leave operation → 400;
 * kicking another admin is forbidden → 403.
 * → 200 { ok: true, remainingMembers }; relays conversation:updated.
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id, userId } = await params

  const body = await safeJson(req)
  const ctx = await loadContext(id, userId, body)
  if ('error' in ctx) return ctx.error

  const requesterId = String(body.requesterId)
  if (userId === requesterId) {
    return NextResponse.json(
      { error: 'Use "Leave group" to remove yourself from this chat.' },
      { status: 400 },
    )
  }

  const target = ctx.target!
  if (target.role === 'admin') {
    return NextResponse.json({ error: 'Admins cannot be removed from the group.' }, { status: 403 })
  }

  await db.conversationParticipant.delete({ where: { id: target.id } })

  const remaining = (
    await db.conversationParticipant.findMany({ where: { conversationId: id }, select: { userId: true } })
  ).map((p) => p.userId)

  await notifySocket('conversation:updated', [...remaining, userId], {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: [...remaining, userId],
  })

  return NextResponse.json({ ok: true, remainingMembers: remaining.length })
}

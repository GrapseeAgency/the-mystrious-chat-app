// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/invite — group invite link management
// Admin-only: create (lazy), return, or regenerate the code.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { CONVERSATION_FULL_INCLUDE, generateInviteCode, notifySocket, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * POST /api/conversations/[id]/invite  body { requesterId, regenerate? }
 * → 200 { inviteCode } (200 iff group + requester is admin)
 * - Groups only (400 otherwise).
 * - Lazy create: no code yet → one is minted.
 * - regenerate:true → old code is replaced (previous links die).
 * Relays conversation:updated so members' detail caches refresh.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }
  const regenerate = body.regenerate === true

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!conv.isGroup) {
    return NextResponse.json({ error: 'Direct conversations do not have invite links.' }, { status: 400 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: requesterId, conversationId: id } },
    select: { role: true },
  })
  if (!participant) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }
  if (participant.role !== 'admin') {
    return NextResponse.json({ error: 'Only group admins can manage the invite link.' }, { status: 403 })
  }

  const nextCode = regenerate || !conv.inviteCode ? generateInviteCode() : conv.inviteCode
  const updated = await db.conversation.update({
    where: { id },
    data: { inviteCode: nextCode },
    include: CONVERSATION_FULL_INCLUDE,
  })

  const recipients = await db.conversationParticipant.findMany({
    where: { conversationId: id },
    select: { userId: true },
  })
  await notifySocket(
    'conversation:updated',
    recipients.map((r) => r.userId),
    { type: 'conversation:updated', conversationId: id, recipientIds: recipients.map((r) => r.userId) },
  )

  return NextResponse.json({ inviteCode: updated.inviteCode })
}

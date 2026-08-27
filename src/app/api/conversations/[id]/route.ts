// ─────────────────────────────────────────────────────────────
// /api/conversations/[id] — detail · group rename
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  GROUP_NAME_MAX,
  memberIdsOf,
  notifySocket,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * GET /api/conversations/[id]?userId=X
 * → { conversation: ConversationDetail } | 404
 * userId (the viewer) is required by contract; members carry lastReadAt.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({
    where: { id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  return NextResponse.json({ conversation: buildConversationDetail(conv, userId) })
}

/**
 * PATCH /api/conversations/[id]  body { requesterId, name?, broadcast? }
 * Group-only meta changes. ADMINS ONLY. `name` renames; `broadcast`
 * toggles announcement mode (Discord stage / Telegram channel: only
 * admins may post while on). → { conversation: ConversationDetail }
 * · relays conversation:updated.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const hasName = body.name !== undefined
  const hasBroadcast = typeof body.broadcast === 'boolean'
  let name = ''
  if (hasName) {
    name = strField(body.name)
    if (name.length === 0 || name.length > GROUP_NAME_MAX) {
      return NextResponse.json(
        { error: `Group name must be 1-${GROUP_NAME_MAX} characters.` },
        { status: 400 },
      )
    }
  }
  if (!hasName && !hasBroadcast) {
    return NextResponse.json(
      { error: 'Nothing to update — provide name and/or broadcast.' },
      { status: 400 },
    )
  }

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!conv.isGroup) {
    return NextResponse.json(
      { error: hasName ? 'Direct conversations cannot be renamed.' : 'Announcement mode is groups-only.' },
      { status: 400 },
    )
  }
  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: requesterId, conversationId: id } },
    select: { role: true },
  })
  if (!participant) {
    return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
  }
  if (participant.role !== 'admin') {
    return NextResponse.json(
      { error: hasName ? 'Only group admins can rename this group.' : 'Only group admins can change announcement mode.' },
      { status: 403 },
    )
  }

  const updated = await db.conversation.update({
    where: { id },
    data: {
      ...(hasName ? { name } : {}),
      ...(hasBroadcast ? { broadcastMode: body.broadcast as boolean } : {}),
    },
    include: CONVERSATION_FULL_INCLUDE,
  })

  const recipients = await memberIdsOf(id)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })

  return NextResponse.json({ conversation: buildConversationDetail(updated, requesterId) })
}

// ─────────────────────────────────────────────────────────────
// /api/conversations/[id] — detail · group rename · photo (R33-b)
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

/** R33-b — accepted stored photo paths: "/api/uploads/<uuid>.<image-ext>". */
const PHOTO_PATH_RE = /^\/api\/uploads\/([A-Za-z0-9-]+\.(?:jpg|jpeg|png|webp))$/

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

  return NextResponse.json({ conversation: await buildConversationDetail(conv, userId) })
}

/**
 * PATCH /api/conversations/[id]  body { requesterId, name?, broadcast?, photo?, screenPrivacy? }
 * Group-only meta changes: `name` renames; `broadcast` toggles announcement
 * mode (Discord stage / Telegram channel: only admins may post while on);
 * `photo` (R33-b) sets the channel/group photo to a validated
 * "/api/uploads/<file>" path — '' clears it. ADMINS ONLY.
 * R38 — `screenPrivacy` (Signal screen security: frost the message area while
 * the Pulse window is unfocused) is gated like the disappearing TTL
 * (/api/conversations/[id]/disappearing): ANY participant of ANY conversation
 * type (groups and DMs) may toggle it. Honest deviation note: on Signal this
 * is a per-viewer local comfort setting; here it is per-conversation state
 * following the TTL precedent, so it is deliberately NOT admin-gated.
 * → { conversation: ConversationDetail } · relays conversation:updated.
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
  // R33-b — photo is additive: '' clears (stored null), a validated upload
  // path sets it, anything else is a 400.
  const hasPhoto = body.photo !== undefined
  let photo: string | null = null
  if (hasPhoto) {
    const raw = typeof body.photo === 'string' ? body.photo.trim() : ''
    if (raw === '') {
      photo = null
    } else if (PHOTO_PATH_RE.test(raw)) {
      photo = raw
    } else {
      return NextResponse.json(
        { error: 'photo must be an uploaded image path ("/api/uploads/<file>").' },
        { status: 400 },
      )
    }
  }
  // R38 — screenPrivacy mirrors broadcast's boolean validation; the TTL-style
  // participant-level gating (no admin check) happens below.
  const hasScreenPrivacy = typeof body.screenPrivacy === 'boolean'
  if (!hasName && !hasBroadcast && !hasPhoto && !hasScreenPrivacy) {
    return NextResponse.json(
      { error: 'Nothing to update — provide name, broadcast, photo and/or screenPrivacy.' },
      { status: 400 },
    )
  }

  const conv = await db.conversation.findUnique({ where: { id } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  // Group-only rules apply to name/broadcast/photo only — a DM may toggle
  // screenPrivacy (Signal screen security is a DM-relevant setting too).
  if (!conv.isGroup && (hasName || hasBroadcast || hasPhoto)) {
    return NextResponse.json(
      {
        error: hasName
          ? 'Direct conversations cannot be renamed.'
          : hasPhoto
            ? 'Photos are for groups and channels.'
            : 'Announcement mode is groups-only.',
      },
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
  const wantsAdminAction = hasName || hasBroadcast || hasPhoto
  if (wantsAdminAction && participant.role !== 'admin') {
    return NextResponse.json(
      {
        error: hasName
          ? 'Only group admins can rename this group.'
          : hasPhoto
            ? 'Only group admins can change the photo.'
            : 'Only group admins can change announcement mode.',
      },
      { status: 403 },
    )
  }

  const updated = await db.conversation.update({
    where: { id },
    data: {
      ...(hasName ? { name } : {}),
      ...(hasBroadcast ? { broadcastMode: body.broadcast as boolean } : {}),
      ...(hasPhoto ? { photo } : {}),
      ...(hasScreenPrivacy ? { screenPrivacy: body.screenPrivacy as boolean } : {}),
    },
    include: CONVERSATION_FULL_INCLUDE,
  })

  const recipients = await memberIdsOf(id)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })

  return NextResponse.json({ conversation: await buildConversationDetail(updated, requesterId) })
}

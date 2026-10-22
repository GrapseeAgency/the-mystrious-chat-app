// /api/conversations/[id] - detail · group rename · photo (R33-b)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import {
  conversationMetadataPatch,
  ConversationMetadataError,
  patchConversationMetadata,
} from '@/lib/conversation-metadata'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  GROUP_NAME_MAX,
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

  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'conversation detail')
    if (denied) return denied
  }
  // Include queries share the same authorized snapshot, not a separate preflight
  // that can race removal and then disclose a newer message to a former member.
  const conv = await db.$transaction(
    (tx) =>
      tx.conversation.findFirst({
        where: { id, participants: { some: { userId } } },
        include: CONVERSATION_FULL_INCLUDE,
      }),
    { isolationLevel: 'Serializable' },
  )
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
 * "/api/uploads/<file>" path - '' clears it. ADMINS ONLY.
 * R38 - `screenPrivacy` (Signal screen security: frost the message area while
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

  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'conversation settings')
    if (denied) return denied
  }
  try {
    const patch = conversationMetadataPatch(body, GROUP_NAME_MAX)
    const recipients = await patchConversationMetadata(db, id, requesterId, patch)
    await notifySocket('conversation:updated', recipients, {
      type: 'conversation:updated',
      conversationId: id,
      recipientIds: recipients,
    })
    const updated = await db.$transaction(
      (tx) =>
        tx.conversation.findFirst({
          where: { id, participants: { some: { userId: requesterId } } },
          include: CONVERSATION_FULL_INCLUDE,
        }),
      { isolationLevel: 'Serializable' },
    )
    if (!updated)
      return NextResponse.json(
        { error: 'Conversation is no longer available to this participant.' },
        { status: 403 },
      )
    return NextResponse.json({ conversation: await buildConversationDetail(updated, requesterId) })
  } catch (error) {
    if (error instanceof ConversationMetadataError)
      return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

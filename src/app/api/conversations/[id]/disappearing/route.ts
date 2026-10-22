// Participant-level TTL for newly sent messages; current membership and write are atomic.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationDetail,
  CONVERSATION_FULL_INCLUDE,
  notifySocket,
  safeJson,
  strField,
} from '@/lib/serializers'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import {
  ConversationMetadataError,
  patchConversationMetadata,
  requirePreset,
  TTL_PRESETS,
} from '@/lib/conversation-metadata'

export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'disappearing-message settings')
    if (denied) return denied
  }
  try {
    const ttlSeconds = body.ttlSeconds
    requirePreset(ttlSeconds, TTL_PRESETS, 'ttlSeconds')
    const recipients = await patchConversationMetadata(db, id, userId, { ttlSeconds })
    await notifySocket('conversation:updated', recipients, {
      type: 'conversation:updated',
      conversationId: id,
      recipientIds: recipients,
    })
    const updated = await db.$transaction(
      (tx) =>
        tx.conversation.findFirst({
          where: { id, participants: { some: { userId } } },
          include: CONVERSATION_FULL_INCLUDE,
        }),
      { isolationLevel: 'Serializable' },
    )
    if (!updated)
      return NextResponse.json(
        { error: 'Conversation is no longer available to this participant.' },
        { status: 403 },
      )
    return NextResponse.json({ conversation: await buildConversationDetail(updated, userId) })
  } catch (error) {
    if (error instanceof ConversationMetadataError)
      return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

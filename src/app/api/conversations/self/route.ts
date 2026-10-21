// /api/conversations/self - Note to Self (Task R24-a)
//
// Contract:
//   POST { userId }
//        → find-first-or-create the Signal-style private notebook
//          chat: Conversation.isSelf === true with EXACTLY one
//          ConversationParticipant (the user). Idempotent - the
//          second call resolves the SAME conversation. → 200 when
//          found, 201 when created, { conversation: <summary> } in
//          the exact GET /api/conversations row shape (built with
//          buildConversationSummary from '@/lib/serializers').
//          isSelf rows flow through GET /api/conversations
//          unfiltered (verified - the list route never filters
//          isSelf), so the chat appears in the normal chats list.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationSummary,
  CONVERSATION_FULL_INCLUDE,
  safeJson,
  strField,
} from '@/lib/serializers'
import { DirectConversationError, openSelfConversation } from '@/lib/direct-conversation'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

/**
 * POST /api/conversations/self { userId }
 * → { conversation: ConversationSummary } (200 deduped / 201 created).
 */
export async function POST(req: Request) {
  const body = await safeJson(req)

  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'Note to Self')
    if (denied) return denied
  }
  try {
    const opened = await openSelfConversation(db, userId)
    const full = await db.conversation.findFirst({
      where: { id: opened.id, isSelf: true, isGroup: false,
        participants: { some: { userId }, every: { userId } } },
      include: CONVERSATION_FULL_INCLUDE,
    })
    if (!full) return NextResponse.json({ error: 'Note to Self is no longer available.' }, { status: 404 })
    return NextResponse.json({ conversation: await buildConversationSummary(full, userId) },
      { status: opened.created ? 201 : 200 })
  } catch (error) {
    if (error instanceof DirectConversationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

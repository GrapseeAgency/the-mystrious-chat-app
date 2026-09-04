// ─────────────────────────────────────────────────────────────
// /api/conversations/self — Note to Self (Task R24-a)
//
// Contract:
//   POST { userId }
//        → find-first-or-create the Signal-style private notebook
//          chat: Conversation.isSelf === true with EXACTLY one
//          ConversationParticipant (the user). Idempotent — the
//          second call resolves the SAME conversation. → 200 when
//          found, 201 when created, { conversation: <summary> } in
//          the exact GET /api/conversations row shape (built with
//          buildConversationSummary from '@/lib/serializers').
//          isSelf rows flow through GET /api/conversations
//          unfiltered (verified — the list route never filters
//          isSelf), so the chat appears in the normal chats list.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationSummary,
  CONVERSATION_FULL_INCLUDE,
  safeJson,
  strField,
} from '@/lib/serializers'
import type { ConversationSummary } from '@/lib/types'

export const dynamic = 'force-dynamic'

const SELF_CHAT_NAME = 'Note to self'

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

  const user = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  // Idempotency: resolve an existing isSelf chat owned by exactly
  // this user (single-participant, self-flagged rows only).
  const candidates = await db.conversation.findMany({
    where: {
      isSelf: true,
      participants: { some: { userId } },
    },
    include: CONVERSATION_FULL_INCLUDE,
    orderBy: { createdAt: 'asc' },
  })
  const existing = candidates.find(
    (conv) => conv.participants.length === 1 && conv.participants[0].userId === userId,
  )
  if (existing) {
    return NextResponse.json({
      conversation: await buildConversationSummary(existing, userId),
    })
  }

  const created = await db.conversation.create({
    data: {
      isGroup: false,
      isSelf: true,
      name: SELF_CHAT_NAME,
      participants: { create: { userId } },
    },
    select: { id: true },
  })

  const full = await db.conversation.findUnique({
    where: { id: created.id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!full) {
    return NextResponse.json(
      { error: 'Failed to load the Note to Self chat.' },
      { status: 500 },
    )
  }

  const summary: ConversationSummary = await buildConversationSummary(full, userId)
  return NextResponse.json({ conversation: summary }, { status: 201 })
}

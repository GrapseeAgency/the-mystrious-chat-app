// ─────────────────────────────────────────────────────────────
// /api/conversations — list my chats, create DM / group
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  buildConversationSummary,
  CONVERSATION_FULL_INCLUDE,
  GROUP_NAME_MAX,
  safeJson,
  strField,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/**
 * GET /api/conversations?userId=X
 * → { conversations: ConversationSummary[] } sorted by updatedAt desc.
 * Members include every participant with their lastReadAt watermark;
 * unreadCount is computed for the viewer X.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json(
      { error: 'userId query parameter is required.' },
      { status: 400 },
    )
  }

  const participations = await db.conversationParticipant.findMany({
    where: { userId },
    select: { conversationId: true },
  })
  const conversationIds = participations.map((p) => p.conversationId)

  // Single round-trip: conversations + members(+users) + newest message each.
  const conversations = await db.conversation.findMany({
    where: { id: { in: conversationIds } },
    include: CONVERSATION_FULL_INCLUDE,
    orderBy: { updatedAt: 'desc' },
  })

  const summaries = await Promise.all(
    conversations.map((conv) => buildConversationSummary(conv, userId)),
  )

  // Pinned (by this viewer) float to the top, then freshest first.
  summaries.sort((a, b) => {
    const pinDelta = (b.pinnedAt ? Date.parse(b.pinnedAt) : 0) - (a.pinnedAt ? Date.parse(a.pinnedAt) : 0)
    if (pinDelta !== 0) return pinDelta
    return Date.parse(b.updatedAt) - Date.parse(a.updatedAt)
  })

  return NextResponse.json({ conversations: summaries })
}

/**
 * POST /api/conversations { creatorId, memberIds, isGroup?, name? }
 * DM: exactly creatorId + 1 member; dedupes to an existing 1-on-1 chat.
 * Group: ≥ 3 distinct members incl. creator, always creates a new one.
 * → 201 { conversation } or 200 { conversation } when an existing DM matched.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)

  const creatorId = strField(body.creatorId)
  if (!creatorId) {
    return NextResponse.json({ error: 'creatorId is required.' }, { status: 400 })
  }

  if (!Array.isArray(body.memberIds)) {
    return NextResponse.json({ error: 'memberIds must be an array of user ids.' }, { status: 400 })
  }
  const memberIds = body.memberIds.map(strField).filter((id) => id !== '')

  const isGroup = body.isGroup === true

  // Distinct participant set incl. the creator.
  const distinctIds = Array.from(new Set([creatorId, ...memberIds]))

  // Every id must reference a real user.
  const foundUsers = await db.user.findMany({
    where: { id: { in: distinctIds } },
    select: { id: true },
  })
  if (foundUsers.length !== distinctIds.length) {
    return NextResponse.json(
      { error: 'One or more user ids do not exist.' },
      { status: 400 },
    )
  }

  let groupName: string | null = null

  if (!isGroup) {
    if (distinctIds.length !== 2) {
      return NextResponse.json(
        { error: 'A direct conversation requires exactly one other member.' },
        { status: 400 },
      )
    }

    // DM dedupe — find a non-group conversation whose participant set
    // equals exactly these two users.
    const candidates = await db.conversation.findMany({
      where: {
        isGroup: false,
        participants: { some: { userId: { in: distinctIds } } },
      },
      select: { id: true, participants: { select: { userId: true } } },
    })
    const existing = candidates.find((cand) => {
      const ids = cand.participants.map((p) => p.userId)
      return ids.length === 2 && ids.includes(distinctIds[0]) && ids.includes(distinctIds[1])
    })

    if (existing) {
      const full = await db.conversation.findUnique({
        where: { id: existing.id },
        include: CONVERSATION_FULL_INCLUDE,
      })
      if (!full) {
        return NextResponse.json(
          { error: 'Failed to load existing conversation.' },
          { status: 500 },
        )
      }
      return NextResponse.json({
        conversation: await buildConversationSummary(full, creatorId),
      }) // 200 — deduped
    }
  } else {
    if (distinctIds.length < 3) {
      return NextResponse.json(
        { error: 'A group needs at least 3 distinct members including you.' },
        { status: 400 },
      )
    }
    const rawName = strField(body.name)
    if (rawName.length > GROUP_NAME_MAX) {
      return NextResponse.json(
        { error: `Group name must be ${GROUP_NAME_MAX} characters or fewer.` },
        { status: 400 },
      )
    }
    groupName = rawName || `Group of ${distinctIds.length}`
  }

  const created = await db.conversation.create({
    data: {
      isGroup,
      name: groupName,
      participants: { create: distinctIds.map((userId) => ({ userId })) },
    },
    select: { id: true },
  })

  const full = await db.conversation.findUnique({
    where: { id: created.id },
    include: CONVERSATION_FULL_INCLUDE,
  })
  if (!full) {
    return NextResponse.json({ error: 'Failed to load new conversation.' }, { status: 500 })
  }

  return NextResponse.json(
    { conversation: await buildConversationSummary(full, creatorId) },
    { status: 201 },
  )
}

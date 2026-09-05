// ─────────────────────────────────────────────────────────────
// /api/channels — broadcast channel directory + creation (R30-c)
// WhatsApp-Channels / Telegram-channel style: isGroup + broadcastMode
// rooms where only admins post. GET = directory for a viewer,
// POST = create a channel (creator becomes the admin) + first post.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { memberIdsOf, notifySocket, safeJson, strField } from '@/lib/serializers'
import type { ChannelSummary } from '@/lib/types'

export const dynamic = 'force-dynamic'

const NAME_MIN = 2
const NAME_MAX = 40
const DESCRIPTION_MAX = 200
const PREVIEW_MAX = 60
const FIRST_MESSAGE = 'Channel created — say it loud.'

/** Clip the last message into a one-line directory snippet. */
function snippet(content: string): string | null {
  const trimmed = content.trim()
  if (!trimmed) return null
  return trimmed.length > PREVIEW_MAX ? `${trimmed.slice(0, PREVIEW_MAX - 1)}…` : trimmed
}

/**
 * GET /api/channels?userId=X[&mine=1]
 * → { channels: ChannelSummary[] }
 * Every broadcast channel (isGroup && broadcastMode && !isSelf, and never a
 * hub App-matrix community) with viewer-aware isSubscribed / unread flags and
 * a last-message preview. Sorted memberCount desc, then newest first.
 * `mine=1` restricts to channels the viewer is subscribed to.
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
  const mineOnly = url.searchParams.get('mine') === '1'

  const convs = await db.conversation.findMany({
    where: { isGroup: true, broadcastMode: true, isSelf: false, appKey: null },
    select: {
      id: true,
      name: true,
      description: true,
      createdAt: true,
      participants: { select: { userId: true, lastReadAt: true } },
      messages: {
        orderBy: { createdAt: 'desc' as const },
        take: 1,
        select: { content: true, senderId: true, deletedAt: true, createdAt: true },
      },
    },
  })

  const channels: ChannelSummary[] = convs.map((conv) => {
    const mine = conv.participants.find((p) => p.userId === userId)
    const last = conv.messages[0] ?? null
    const unread =
      mine !== undefined &&
      last !== null &&
      !last.deletedAt &&
      last.senderId !== userId &&
      last.createdAt > mine.lastReadAt
    return {
      id: conv.id,
      name: conv.name ?? 'Channel',
      description: conv.description,
      createdAt: conv.createdAt.toISOString(),
      memberCount: conv.participants.length,
      isSubscribed: mine !== undefined,
      unread,
      preview: last && !last.deletedAt ? snippet(last.content) : null,
    }
  })

  channels.sort((a, b) => {
    if (b.memberCount !== a.memberCount) return b.memberCount - a.memberCount
    return Date.parse(b.createdAt) - Date.parse(a.createdAt)
  })

  return NextResponse.json({
    channels: mineOnly ? channels.filter((c) => c.isSubscribed) : channels,
  })
}

/**
 * POST /api/channels  body { userId, name, description? }
 * Creates a broadcast channel: isGroup + broadcastMode, the creator lands as
 * role:"admin" participant, and a REAL first message is posted from them.
 * → 201 { channel: ChannelSummary & { conversationId } }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)

  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const name = strField(body.name)
  if (name.length < NAME_MIN || name.length > NAME_MAX) {
    return NextResponse.json(
      { error: `Channel name must be ${NAME_MIN}-${NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  const description = strField(body.description)
  if (description.length > DESCRIPTION_MAX) {
    return NextResponse.json(
      { error: `Description must be ${DESCRIPTION_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  const creator = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!creator) {
    return NextResponse.json({ error: 'Unknown user.' }, { status: 404 })
  }

  const now = new Date()
  const created = await db.conversation.create({
    data: {
      isGroup: true,
      broadcastMode: true,
      name,
      description,
      participants: {
        create: { userId, role: 'admin', lastReadAt: now },
      },
      messages: {
        create: { senderId: userId, content: FIRST_MESSAGE, kind: 'text' },
      },
    },
    select: { id: true },
  })
  // Bump list ordering for the creator (mirrors the messages route).
  await db.conversation.update({ where: { id: created.id }, data: { updatedAt: now } })

  const recipients = await memberIdsOf(created.id)
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: created.id,
    recipientIds: recipients,
  })

  const channel: ChannelSummary & { conversationId: string } = {
    id: created.id,
    conversationId: created.id,
    name,
    description,
    createdAt: now.toISOString(),
    memberCount: 1,
    isSubscribed: true,
    unread: false,
    preview: snippet(FIRST_MESSAGE),
  }
  return NextResponse.json({ channel }, { status: 201 })
}

// /api/channels - broadcast channel directory + creation (R30-c)
// WhatsApp-Channels / Telegram-channel style: isGroup + broadcastMode
// rooms where only admins post. GET = directory for a viewer,
// POST = create a channel (creator becomes the admin) + first post.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { unreadSnapshot } from '@/lib/message-visibility'
import { createConversationOnce, ConversationCreationError } from '@/lib/conversation-creation'
import { notifySocket, safeJson, strField, CONVERSATION_FULL_INCLUDE, buildConversationSummary } from '@/lib/serializers'
import type { ChannelSummary } from '@/lib/types'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { channelIdentity, normalizeChannelPhoto } from '@/lib/channel-contract'

export const dynamic = 'force-dynamic'

const NAME_MIN = 2
const NAME_MAX = 40
const DESCRIPTION_MAX = 200
const PREVIEW_MAX = 60
const FIRST_MESSAGE = 'Channel created - say it loud.'

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
  const denied = await requireActionOwner(req, userId, 'channel read state')
  if (denied) return denied
  const mineOnly = url.searchParams.get('mine') === '1'

  const convs = await db.conversation.findMany({
    where: { isGroup: true, broadcastMode: true, isSelf: false, appKey: null },
    select: {
      id: true,
      name: true,
      description: true,
      photo: true,
      createdAt: true,
      participants: { select: { userId: true, lastReadAt: true } },
      messages: {
        orderBy: { createdAt: 'desc' as const },
        take: 1,
        select: { content: true, senderId: true, deletedAt: true, createdAt: true },
      },
    },
  })

  const channels: ChannelSummary[] = await Promise.all(convs.map(async (conv) => {
    const mine = conv.participants.find((p) => p.userId === userId)
    const last = conv.messages[0] ?? null
    const unread = mine !== undefined &&
      (await unreadSnapshot(db, conv.id, userId, mine.lastReadAt, new Date())).unreadCount > 0
    return {
      ...channelIdentity(conv.id),
      name: conv.name ?? 'Channel',
      description: conv.description,
      createdAt: conv.createdAt.toISOString(),
      memberCount: conv.participants.length,
      isSubscribed: mine !== undefined,
      unread,
      preview: last && !last.deletedAt ? snippet(last.content) : null,
      photo: conv.photo ?? null,
    }
  }))

  channels.sort((a, b) => {
    if (b.memberCount !== a.memberCount) return b.memberCount - a.memberCount
    return Date.parse(b.createdAt) - Date.parse(a.createdAt)
  })

  return NextResponse.json({
    channels: mineOnly ? channels.filter((c) => c.isSubscribed) : channels,
  })
}

/**
 * POST /api/channels  body { userId, name, description?, photo? }
 * Creates a broadcast channel: isGroup + broadcastMode, the creator lands as
 * role:"admin" participant, and a REAL first message is posted from them.
 * `photo` (optional) is a validated "/api/uploads/<file>" path from the
 * shared upload chain. → 201 { channel: ChannelSummary & { conversationId } }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)

  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'channel creation')
    if (denied) return denied
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

  // R33-b - optional channel photo at creation (same upload chain as profiles)
  const photo = normalizeChannelPhoto(body.photo)
  if (photo === undefined) {
    return NextResponse.json(
      { error: 'photo must be an uploaded image path ("/api/uploads/<file>").' },
      { status: 400 },
    )
  }

  const creator = await db.user.findUnique({ where: { id: userId }, select: { id: true } })
  if (!creator) {
    return NextResponse.json({ error: 'Unknown user.' }, { status: 404 })
  }

  try {
    const now = new Date()
    const created = await createConversationOnce(db, userId, body.clientId, 'channel',
      [name, description, photo], tx => tx.conversation.create({
      data: {
        isGroup: true,
        broadcastMode: true,
        createdAt: now,
        updatedAt: now,
        name,
        description,
        ...(photo ? { photo } : {}),
        participants: {
          create: { userId, role: 'admin', lastReadAt: now },
        },
        messages: {
          create: { senderId: userId, content: FIRST_MESSAGE, kind: 'text', createdAt: now },
        },
      },
      select: { id: true },
    }))
    // Creation and its receipt committed together. A failed serialization/read can
    // now be retried with the same identity, without another room or first post.
    const recipients = [userId]
    await notifySocket('conversation:updated', recipients, {
      type: 'conversation:updated',
      conversationId: created.id,
      recipientIds: recipients,
    })

    const current = await db.conversation.findFirst({
      where: { id: created.id, participants: { some: { userId } } },
      include: CONVERSATION_FULL_INCLUDE,
    })
    if (!current) return NextResponse.json({ error: 'The created channel is no longer available.' }, { status: 404 })
    const conversation = await buildConversationSummary(current, userId)
    const channel: ChannelSummary & { conversationId: string } = {
      ...channelIdentity(current.id), name: current.name ?? 'Channel', description: current.description,
      createdAt: current.createdAt.toISOString(), memberCount: current.participants.length,
      isSubscribed: true, unread: conversation.unreadCount > 0,
      preview: conversation.lastMessage && !conversation.lastMessage.deletedAt ? snippet(conversation.lastMessage.content) : null, photo: current.photo,
    }
    return NextResponse.json({ channel, conversation, clientId: created.clientId, replayed: created.replayed }, { status: created.replayed ? 200 : 201 })
  } catch (error) {
    if (error instanceof ConversationCreationError) return NextResponse.json(
      { error: error.message, code: error.code, clientId: error.clientId }, { status: error.status })
    throw error
  }
}

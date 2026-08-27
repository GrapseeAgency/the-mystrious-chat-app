// ─────────────────────────────────────────────────────────────
// Pulse Chat — shared server-side helpers (used by REST routes)
// Prisma row → DTO serializers matching src/lib/types.ts,
// input normalization, safe JSON parsing, socket relay.
// Server-only — never import from client components.
// ─────────────────────────────────────────────────────────────
import path from 'node:path'
import type { Prisma } from '@prisma/client'
import { db } from '@/lib/db'
import type {
  AppUser,
  ChatMessage,
  ConversationDetail,
  ConversationSummary,
  MessageAuthor,
  MessageReactionGroup,
} from '@/lib/types'

// ── Validation constants ────────────────────────────────────
export const USER_NAME_MAX = 32
export const ABOUT_MAX = 140
export const MESSAGE_MAX = 2000
export const GROUP_NAME_MAX = 48
export const MESSAGES_DEFAULT_LIMIT = 200
export const MESSAGES_MAX_LIMIT = 500

/** Emoji allowed as reactions (keeps bubbles tidy). */
export const REACTION_EMOJIS = ['👍', '❤️', '😂', '😮', '😢', '🎉'] as const

export const AVATAR_COLORS = [
  'emerald',
  'rose',
  'amber',
  'violet',
  'teal',
  'orange',
  'pink',
  'cyan',
] as const

// ── Input helpers ───────────────────────────────────────────

/** Trimmed string field or '' when absent / wrong type. */
export function strField(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * Robust JSON body parser — returns {} on any failure.
 * Callers validate individual fields and return 400 on bad input.
 */
export async function safeJson(req: Request): Promise<Record<string, unknown>> {
  try {
    const parsed: unknown = await req.json()
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    return {}
  } catch {
    return {}
  }
}

/** Whitelisted avatar color; falls back to 'emerald'. */
export function normalizeColor(value: unknown): string {
  const candidate = strField(value)
  return (AVATAR_COLORS as readonly string[]).includes(candidate) ? candidate : 'emerald'
}

/** Parse an ISO date string; null when invalid. */
export function parseIsoDate(value: unknown): Date | null {
  if (typeof value !== 'string') return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

// ── Serializers ─────────────────────────────────────────────

interface UserRow {
  id: string
  name: string
  about: string
  color: string
  createdAt: Date
  lastSeenAt: Date
}

function mapAuthor(user: UserRow): MessageAuthor {
  return { id: user.id, name: user.name, color: user.color }
}

export function mapUser(user: UserRow): AppUser {
  return {
    id: user.id,
    name: user.name,
    about: user.about,
    color: user.color,
    createdAt: user.createdAt.toISOString(),
    lastSeenAt: user.lastSeenAt.toISOString(),
  }
}

interface ReactionRow {
  emoji: string
  userId: string
}

export function groupReactions(reactions: ReactionRow[]): MessageReactionGroup[] {
  const order: string[] = []
  const byEmoji = new Map<string, string[]>()
  for (const r of reactions) {
    let ids = byEmoji.get(r.emoji)
    if (!ids) {
      ids = []
      byEmoji.set(r.emoji, ids)
      order.push(r.emoji)
    }
    ids.push(r.userId)
  }
  return order.map((emoji) => {
    const userIds = byEmoji.get(emoji) ?? []
    return { emoji, userIds, count: userIds.length }
  })
}

/** Deep include reused by every message fetch (history, last-message, single). */
export const MESSAGE_FULL_INCLUDE = {
  sender: true,
  reactions: { select: { emoji: true, userId: true } },
  replyTo: { select: { id: true, content: true, deletedAt: true, sender: { select: { name: true } } } },
} satisfies Prisma.MessageInclude

export type MessageRowWithRelations = Prisma.MessageGetPayload<{ include: typeof MESSAGE_FULL_INCLUDE }>

export function mapMessage(message: MessageRowWithRelations): ChatMessage {
  const parent = message.replyTo
  const replyTo = parent
    ? {
        id: parent.id,
        content: parent.content,
        senderName: parent.sender.name,
        deleted: parent.deletedAt !== null,
      }
    : null
  return {
    id: message.id,
    conversationId: message.conversationId,
    senderId: message.senderId,
    content: message.content,
    deletedAt: message.deletedAt ? message.deletedAt.toISOString() : null,
    createdAt: message.createdAt.toISOString(),
    sender: mapAuthor(message.sender),
    reactions: groupReactions(message.reactions),
    replyTo,
    imagePath: message.imagePath ?? null,
    audioPath: message.audioPath ?? null,
    durationMs: message.durationMs ?? null,
  }
}

/** Participant row (+user) → AppUser with read watermark. */
export function mapMember(participant: { lastReadAt: Date; user: UserRow }): AppUser & { lastReadAt: string } {
  return { ...mapUser(participant.user), lastReadAt: participant.lastReadAt.toISOString() }
}

const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name)

/** Reusable deep include for "conversation + members + newest message". */
export const CONVERSATION_FULL_INCLUDE = {
  participants: { include: { user: true } },
  messages: {
    orderBy: { createdAt: 'desc' as const },
    take: 1,
    include: MESSAGE_FULL_INCLUDE,
  },
} satisfies Prisma.ConversationInclude

export type ConversationRowWithRelations = Prisma.ConversationGetPayload<{
  include: typeof CONVERSATION_FULL_INCLUDE
}>

type MemberWithWatermark = AppUser & { lastReadAt: string }

/**
 * Build a ConversationSummary for ONE viewer.
 * `viewerId` must be a participant of `conv` (unread watermark lookup).
 */
export async function buildConversationSummary(
  conv: ConversationRowWithRelations,
  viewerId: string,
): Promise<ConversationSummary> {
  const myReadAt =
    conv.participants.find((p) => p.userId === viewerId)?.lastReadAt ?? new Date(0)
  const unreadCount = await db.message.count({
    where: {
      conversationId: conv.id,
      senderId: { not: viewerId },
      deletedAt: null,
      createdAt: { gt: myReadAt },
    },
  })
  const lastRow = conv.messages[0] ?? null
  const mine = conv.participants.find((p) => p.userId === viewerId)
  return {
    id: conv.id,
    isGroup: conv.isGroup,
    name: conv.name,
    createdAt: conv.createdAt.toISOString(),
    updatedAt: conv.updatedAt.toISOString(),
    members: conv.participants.map(mapMember).sort(byName),
    lastMessage: lastRow ? mapMessage(lastRow) : null,
    unreadCount,
    pinnedAt: mine?.pinnedAt ? mine.pinnedAt.toISOString() : null,
    mutedUntil: mine?.mutedUntil ? mine.mutedUntil.toISOString() : null,
  }
}

/** Meta + members-with-watermark for a chat room header. */
export function buildConversationDetail(
  conv: ConversationRowWithRelations,
  viewerId?: string,
): ConversationDetail {
  const mine = viewerId ? conv.participants.find((p) => p.userId === viewerId) : undefined
  return {
    id: conv.id,
    isGroup: conv.isGroup,
    name: conv.name,
    createdAt: conv.createdAt.toISOString(),
    updatedAt: conv.updatedAt.toISOString(),
    members: conv.participants.map(mapMember).sort(byName),
    myMutedUntil: mine?.mutedUntil ? mine.mutedUntil.toISOString() : null,
  }
}

/** All member userIds of a conversation. */
export async function memberIdsOf(conversationId: string): Promise<string[]> {
  const rows = await db.conversationParticipant.findMany({
    where: { conversationId },
    select: { userId: true },
  })
  return rows.map((r) => r.userId)
}

/**
 * Uploads directory for image messages (files served via /api/uploads/[name]).
 * Lives outside /public so the API controls access + caching.
 */
export const UPLOADS_DIR = path.join(process.cwd(), 'uploads')

/** Whitelist of allowed upload extensions → mime for serving. */
export const UPLOAD_MIME: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  webm: 'audio/webm',
  mp3: 'audio/mpeg',
  ogg: 'audio/ogg',
  wav: 'audio/wav',
  m4a: 'audio/mp4',
  aac: 'audio/aac',
}

/** Extensions accepted for voice notes. */
export const AUDIO_EXT_REGEX = /^[A-Za-z0-9-]+\.(webm|mp3|ogg|wav|m4a|aac)$/

// ── Socket relay (best-effort — never fails the API call) ──

const SOCKET_URL = 'http://localhost:3003'

export type PulseSocketEvent =
  | 'message:new'
  | 'message:deleted'
  | 'message:read'
  | 'message:react'
  | 'conversation:updated'

/**
 * Relay a realtime event to the socket.io mini service so it can fan out
 * to each recipient's room (`user:{id}`). Silent no-op on failure.
 */
export async function notifySocket(
  event: PulseSocketEvent,
  recipientIds: string[],
  payload: unknown,
): Promise<void> {
  if (recipientIds.length === 0) return
  try {
    await fetch(`${SOCKET_URL}/notify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event, recipients: recipientIds, payload }),
      signal: AbortSignal.timeout(2500),
    })
  } catch {
    // Socket mini-service may be down; realtime delivery is best-effort.
  }
}

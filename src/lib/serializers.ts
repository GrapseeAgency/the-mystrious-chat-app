// ─────────────────────────────────────────────────────────────
// Pulse Chat — shared server-side helpers (used by REST routes)
// Prisma row → DTO serializers matching src/lib/types.ts,
// input normalization, safe JSON parsing, socket relay.
// Server-only — never import from client components.
// ─────────────────────────────────────────────────────────────
import path from 'node:path'
import type { Prisma } from '../../prisma/generated-client'
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
export const USERNAME_MIN = 3
export const USERNAME_MAX = 20
export const ABOUT_MAX = 140
export const MESSAGE_MAX = 2000
export const GROUP_NAME_MAX = 48
export const MESSAGES_DEFAULT_LIMIT = 200
export const MESSAGES_MAX_LIMIT = 500

/**
 * Validate + normalize a @handle: 3–20 chars, lowercase letters, digits,
 * underscores. Returns null when the candidate is unusable.
 */
export function normalizeUsername(value: unknown): string | null {
  const raw = strField(value).toLowerCase()
  if (raw.length < USERNAME_MIN || raw.length > USERNAME_MAX) return null
  if (!/^[a-z0-9_]+$/.test(raw)) return null
  return raw
}

/** UTC-day key ("2026-02-03") for daily gating (check-in streaks). */
export function dayKey(d: Date): string {
  return d.toISOString().slice(0, 10)
}

/**
 * R31-a — a chat streak is LIVE while its lastDay is today or yesterday
 * (UTC). Anything older is a dead streak: serializers report `myStreak: null`
 * so the UI never shows a flame for a broken chain.
 */
export function isLiveStreakDay(lastDay: string): boolean {
  if (lastDay === dayKey(new Date())) return true
  return lastDay === dayKey(new Date(Date.now() - 86_400_000))
}

/** Suggest the nearest free variant of a taken handle (append 2..99). */
export async function suggestUsername(base: string): Promise<string> {
  for (let n = 2; n < 100; n += 1) {
    const candidate = `${base}${n}`
    const clash = await db.user.findUnique({ where: { username: candidate }, select: { id: true } })
    if (!clash) return candidate
  }
  return `${base}${Date.now().toString(36)}`
}

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
  username?: string | null
  about: string
  color: string
  avatar: string | null
  statusEmoji: string | null
  statusText: string | null
  createdAt: Date
  lastSeenAt: Date
}

function mapAuthor(user: UserRow): MessageAuthor {
  return { id: user.id, name: user.name, username: user.username ?? null, color: user.color, avatar: user.avatar ?? null }
}

export function mapUser(user: UserRow): AppUser {
  return {
    id: user.id,
    name: user.name,
    username: user.username ?? null,
    about: user.about,
    color: user.color,
    avatar: user.avatar ?? null,
    statusEmoji: user.statusEmoji ?? null,
    statusText: user.statusText ?? null,
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
  poll: {
    include: {
      options: {
        orderBy: { position: 'asc' as const },
        include: { votes: { select: { userId: true, optionId: true } } },
      },
    },
  },
  translations: { select: { lang: true, text: true } },
  linkPreview: true,
} satisfies Prisma.MessageInclude

export type MessageRowWithRelations = Prisma.MessageGetPayload<{ include: typeof MESSAGE_FULL_INCLUDE }>

export function mapMessage(message: MessageRowWithRelations, viewerId?: string): ChatMessage {
  const parent = message.replyTo
  const replyTo = parent
    ? {
        id: parent.id,
        content: parent.content,
        senderName: parent.sender.name,
        deleted: parent.deletedAt !== null,
      }
    : null

  // Poll tally (viewer-aware my vote)
  let poll: ChatMessage['poll'] = null
  if (message.poll) {
    let totalVotes = 0
    let myOptionId: string | null = null
    const options = message.poll.options.map((option) => {
      totalVotes += option.votes.length
      if (viewerId && option.votes.some((v) => v.userId === viewerId)) myOptionId = option.id
      return {
        id: option.id,
        text: option.text,
        position: option.position,
        voteCount: option.votes.length,
        votedBy: option.votes.map((v) => v.userId),
      }
    })
    poll = {
      id: message.poll.id,
      question: message.poll.question,
      closed: message.poll.closedAt !== null,
      options,
      totalVotes,
      myOptionId,
    }
  }

  return {
    id: message.id,
    conversationId: message.conversationId,
    senderId: message.senderId,
    content: message.content,
    kind: message.kind ?? 'text',
    payload: message.payload ?? null,
    deletedAt: message.deletedAt ? message.deletedAt.toISOString() : null,
    createdAt: message.createdAt.toISOString(),
    sender: mapAuthor(message.sender),
    reactions: groupReactions(message.reactions),
    replyTo,
    imagePath: message.imagePath ?? null,
    audioPath: message.audioPath ?? null,
    durationMs: message.durationMs ?? null,
    editedAt: message.editedAt ? message.editedAt.toISOString() : null,
    pinnedAt: message.pinnedAt ? message.pinnedAt.toISOString() : null,
    pinnedBy: message.pinnedBy ?? null,
    parentId: message.parentId ?? null,
    topicId: message.topicId ?? null,
    anon: message.anon ?? false,
    anonAlias: message.anonAlias ?? null,
    viewOnce: message.viewOnce,
    viewedAt: message.viewedAt ? message.viewedAt.toISOString() : null,
    viewedBy: message.viewedBy ?? null,
    expiresAt: message.expiresAt ? message.expiresAt.toISOString() : null,
    linkUrl: message.linkUrl ?? null,
    linkPreview:
      message.linkPreview !== null
        ? {
            url: message.linkPreview.url,
            title: message.linkPreview.title,
            description: message.linkPreview.description,
            imageUrl: message.linkPreview.imageUrl,
            siteName: message.linkPreview.siteName,
          }
        : null,
    poll,
    translations: message.translations.map((t) => ({ lang: t.lang, text: t.text })),
  }
}

/** Group role of a participant row — normalizes unexpected values to "member". */
export function mapRole(role: string): 'admin' | 'member' {
  return role === 'admin' ? 'admin' : 'member'
}

/** Participant row (+user) → AppUser with read watermark + group role. */
export function mapMember(participant: { lastReadAt: Date; user: UserRow; role: string }): AppUser & { lastReadAt: string; role: 'admin' | 'member' } {
  return { ...mapUser(participant.user), lastReadAt: participant.lastReadAt.toISOString(), role: mapRole(participant.role) }
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
  const [unreadCount, streakRow] = await Promise.all([
    db.message.count({
      where: {
        conversationId: conv.id,
        senderId: { not: viewerId },
        deletedAt: null,
        createdAt: { gt: myReadAt },
      },
    }),
    db.conversationStreak.findUnique({
      where: { conversationId_userId: { conversationId: conv.id, userId: viewerId } },
      select: { lastDay: true, count: true },
    }),
  ])
  const lastRow = conv.messages[0] ?? null
  const mine = conv.participants.find((p) => p.userId === viewerId)
  return {
    id: conv.id,
    isGroup: conv.isGroup,
    name: conv.name,
    createdAt: conv.createdAt.toISOString(),
    updatedAt: conv.updatedAt.toISOString(),
    members: conv.participants.map(mapMember).sort(byName),
    lastMessage: lastRow ? mapMessage(lastRow, viewerId) : null,
    unreadCount,
    myStreak:
      streakRow && isLiveStreakDay(streakRow.lastDay) ? { count: streakRow.count } : null,
    pinnedAt: mine?.pinnedAt ? mine.pinnedAt.toISOString() : null,
    mutedUntil: mine?.mutedUntil ? mine.mutedUntil.toISOString() : null,
    archivedAt: mine?.archivedAt ? mine.archivedAt.toISOString() : null,
    ttlSeconds: conv.ttlSeconds,
    broadcastMode: conv.broadcastMode,
    isSelf: conv.isSelf,
  }
}

/**
 * Meta + members-with-watermark for a chat room header. Also carries the
 * viewer's LIVE chat streak for this conversation (null = none / broken).
 */
export async function buildConversationDetail(
  conv: ConversationRowWithRelations,
  viewerId?: string,
): Promise<ConversationDetail> {
  const mine = viewerId ? conv.participants.find((p) => p.userId === viewerId) : undefined
  const streakRow = viewerId
    ? await db.conversationStreak.findUnique({
        where: { conversationId_userId: { conversationId: conv.id, userId: viewerId } },
        select: { lastDay: true, count: true, best: true },
      })
    : null
  return {
    id: conv.id,
    isGroup: conv.isGroup,
    name: conv.name,
    createdAt: conv.createdAt.toISOString(),
    updatedAt: conv.updatedAt.toISOString(),
    members: conv.participants.map(mapMember).sort(byName),
    myMutedUntil: mine?.mutedUntil ? mine.mutedUntil.toISOString() : null,
    myStreak:
      streakRow && isLiveStreakDay(streakRow.lastDay)
        ? { count: streakRow.count, best: streakRow.best }
        : null,
    inviteCode: conv.isGroup ? (conv.inviteCode ?? null) : null,
    ttlSeconds: conv.ttlSeconds,
    broadcastMode: conv.broadcastMode,
    isSelf: conv.isSelf,
    description: conv.description,
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

// ── Invite codes ────────────────────────────────────────

/** Unambiguous alphabet for invite codes (no 0/O/1/I confusion). */
const INVITE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
export const INVITE_CODE_LENGTH = 8

/** Random invite code, e.g. "K7MQX2AB" (crypto-free is fine at this scale). */
export function generateInviteCode(): string {
  let code = ''
  for (let i = 0; i < INVITE_CODE_LENGTH; i += 1) {
    code += INVITE_ALPHABET[Math.floor(Math.random() * INVITE_ALPHABET.length)]
  }
  return code
}

/** Normalize an invite code from user input (uppercase, strip separators). */
export function normalizeInviteCode(value: unknown): string {
  return strField(value).toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16)
}

// ── Socket relay (best-effort — never fails the API call) ──

const SOCKET_URL = 'http://localhost:3003'

export type PulseSocketEvent =
  | 'message:new'
  | 'message:deleted'
  | 'message:read'
  | 'message:react'
  | 'message:edited'
  | 'message:pinned'
  | 'message:viewed'
  | 'poll:voted'
  | 'link:preview'
  | 'translation:added'
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

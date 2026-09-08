/**
 * Pulse shared protocol — the REAL wire contract the live Next.js API emits
 * (captured from the :81 gateway). Web, Android (WireDtos.kt) and iOS
 * (WireDtos.swift) parse these exact shapes. Source of truth; change here →
 * change the mirrors in the same commit.
 */

export const SOCKET_EVENTS = [
  'join', 'joined', 'presence:snapshot', 'typing',
  'message:new', 'message:deleted', 'message:read',
  'voice:transcript', 'voice:join', 'voice:leave', 'voice:ptt',
  'call:offer', 'call:answer', 'call:ice', 'call:reject', 'call:cancel', 'call:hangup',
] as const

export type SocketEvent = (typeof SOCKET_EVENTS)[number]

/** Error envelope every REST route returns on failure. */
export interface ApiError {
  error: string
}

/** HTTP status → client failure kind. Android PulseResult.Kind / iOS Failure.Kind mirror this. */
export function failureKindFor(status: number): string {
  if (status === 401) return 'AUTH'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NOT_FOUND'
  if (status === 422) return 'VALIDATION'
  if (status === 429) return 'RATE_LIMITED'
  if (status >= 500) return 'SERVER'
  return 'UNKNOWN'
}

/** Sender embedded in message payloads. */
export interface WireSender {
  id: string
  name: string
  username?: string | null
  color?: string | null
  avatar?: string | null
}

export interface WireReaction {
  id?: string | null
  userId?: string | null
  emoji?: string | null
  createdAt?: string | null
}

/** GET /api/conversations/{id}/messages row — lowercase wire kinds. */
export interface WireChatMessage {
  id: string
  conversationId: string
  senderId: string
  content: string
  kind: string // text | image | voice | video | file | poll | system | sticker | location | red_packet…
  payload?: unknown
  createdAt: string
  editedAt?: string | null
  deletedAt?: string | null
  sender?: WireSender | null
  reactions?: WireReaction[]
  replyTo?: WireChatMessage | null
  parentId?: string | null
  imagePath?: string | null
  audioPath?: string | null
  durationMs?: number | null
  filePath?: string | null
  fileName?: string | null
  fileSize?: number | null
  linkUrl?: string | null
  linkPreview?: unknown
  pinnedAt?: string | null
  pinnedBy?: string | null
  poll?: unknown
  topicId?: string | null
  transcript?: string | null
  transcribedAt?: string | null
  viaAutomation?: boolean | null
  viewOnce?: boolean | null
  viewedAt?: string | null
  viewedBy?: unknown
  anon?: boolean | null
  anonAlias?: string | null
  expiresAt?: string | null
}

export interface WireMessagesPage {
  messages: WireChatMessage[]
  hasMore: boolean
  total: number
}

/** GET /api/conversations?userId= row. */
export interface WireConversationSummary {
  id: string
  isGroup: boolean
  name?: string | null
  photo?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  members: Array<{
    id: string
    name: string
    username?: string | null
    color?: string | null
    avatar?: string | null
    statusEmoji?: string | null
    statusText?: string | null
    lastReadAt?: string | null
    role?: string | null
  }>
  lastMessage?: WireChatMessage | null
  unreadCount?: number
  pinnedAt?: string | null
  mutedUntil?: string | null
  archivedAt?: string | null
  ttlSeconds?: number | null
  broadcastMode?: boolean | null
  isSelf?: boolean | null
  myDraft?: string | null
  myManualUnread?: boolean | null
  myStreak?: number | { count?: number } | null
  deadStreak?: { count?: number } | null
  lostStreak?: { count?: number } | null
}

export interface WireConversationsPage {
  conversations: WireConversationSummary[]
}

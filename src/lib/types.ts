// ─────────────────────────────────────────────────────────────
// Pulse Chat — shared API contract types (single source of truth)
// Backend routes MUST serialize to exactly these shapes.
// ─────────────────────────────────────────────────────────────

export interface AppUser {
  id: string
  name: string
  about: string
  color: string // emerald|rose|amber|violet|teal|orange|pink|cyan
  createdAt: string // ISO
  lastSeenAt: string // ISO — updated when socket connects/disconnects is NOT possible (separate svc), so treat as "profile last active"; presence comes from socket events
}

/** Minimal sender embedded in messages */
export interface MessageAuthor {
  id: string
  name: string
  color: string
}

export type MessageStatus = 'sent' | 'deleted'

/** Reactions grouped by emoji for one message. */
export interface MessageReactionGroup {
  emoji: string
  userIds: string[]
  count: number
}

/** Quoted-parent snippet embedded when a message is a reply. */
export interface ReplySnippet {
  id: string
  content: string
  senderName: string
  deleted: boolean
}

export interface ChatMessage {
  id: string
  conversationId: string
  senderId: string
  content: string
  deletedAt: string | null // ISO or null
  createdAt: string // ISO
  sender: MessageAuthor
  reactions: MessageReactionGroup[]
  replyTo: ReplySnippet | null
  imagePath: string | null // served via GET /api/uploads/[imagePath]
  audioPath: string | null // voice-note file served via GET /api/uploads/[audioPath]
  durationMs: number | null // voice-note length in milliseconds
}

/** Row of the conversations list */
export interface ConversationSummary {
  id: string
  isGroup: boolean
  name: string | null // group title; null for DMs (UI derives other member's name)
  createdAt: string
  updatedAt: string
  members: AppUser[] // every participant EXCEPT is never filtered; UI filters out self where needed
  lastMessage: ChatMessage | null
  unreadCount: number // count of non-deleted messages from others newer than my lastReadAt
  pinnedAt: string | null // viewer's pin watermark (null = not pinned)
  mutedUntil: string | null // viewer's notification-mute watermark (null = unmuted)
}

/** Full detail for a chat room */
export interface ConversationDetail {
  id: string
  isGroup: boolean
  name: string | null
  createdAt: string
  updatedAt: string
  members: Array<AppUser & { lastReadAt: string }> // includes per-member read watermark → double ticks
  myMutedUntil: string | null // viewer's notification-mute watermark (null = unmuted)
}

/** Global search hit — a message plus the conversation it lives in. */
export interface SearchResultMessage extends ChatMessage {
  /** resolved display title (group name or DM partner name) */
  conversationName: string
  isGroup: boolean
}

// ── Socket event payloads (port 3003 mini service) ────────────

export interface PresenceSnapshot {
  onlineUserIds: string[]
}

export interface TypingEvent {
  conversationId: string
  userId: string
  userName: string
  isTyping: boolean
}

export interface ReadEvent {
  conversationId: string
  userId: string
  lastReadAt: string
}

export interface SocketMessageEvent {
  type: 'message:new' | 'message:deleted' | 'message:react'
  message: ChatMessage
  /** ids of all members except sender */
  recipientIds: string[]
  conversationId: string
}

/** Group meta changed (renamed / members added / someone left). */
export interface ConversationUpdatedEvent {
  type: 'conversation:updated'
  conversationId: string
  /** ids of ALL current members (after the change) */
  recipientIds: string[]
}

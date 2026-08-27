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

export interface ChatMessage {
  id: string
  conversationId: string
  senderId: string
  content: string
  deletedAt: string | null // ISO or null
  createdAt: string // ISO
  sender: MessageAuthor
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
}

/** Full detail for a chat room */
export interface ConversationDetail {
  id: string
  isGroup: boolean
  name: string | null
  createdAt: string
  updatedAt: string
  members: Array<AppUser & { lastReadAt: string }> // includes per-member read watermark → double ticks
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
  type: 'message:new' | 'message:deleted'
  message: ChatMessage
  /** ids of all members except sender */
  recipientIds: string[]
  conversationId: string
}

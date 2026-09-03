// ─────────────────────────────────────────────────────────────
// Pulse Chat — shared API contract types (single source of truth)
// Backend routes MUST serialize to exactly these shapes.
// ─────────────────────────────────────────────────────────────

export interface AppUser {
  id: string
  name: string
  username: string | null // unique @handle — shown instead of raw IDs everywhere
  about: string
  color: string // emerald|rose|amber|violet|teal|orange|pink|cyan
  statusEmoji: string | null // Discord-style custom status glyph
  statusText: string | null // Discord-style custom status line
  createdAt: string // ISO
  lastSeenAt: string // ISO — updated when socket connects/disconnects is NOT possible (separate svc), so treat as "profile last active"; presence comes from socket events
}

/** Minimal sender embedded in messages */
export interface MessageAuthor {
  id: string
  name: string
  username: string | null
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

/** Cached Open-Graph metadata attached to a message containing a link. */
export interface LinkPreviewData {
  url: string
  title: string | null
  description: string | null
  imageUrl: string | null
  siteName: string | null
}

/** One live-poll option with its current tally (includes who voted). */
export interface PollOptionTally {
  id: string
  text: string
  position: number
  voteCount: number
  /** userIds of voters — clients derive their own pick, reaction-style */
  votedBy: string[]
}

/** Full poll card state embedded on the owning message. */
export interface PollData {
  id: string
  question: string
  closed: boolean
  options: PollOptionTally[]
  totalVotes: number
  /** option the viewer picked (null = not voted / no viewer) */
  myOptionId: string | null
}

export interface MessageTranslationEntry {
  lang: string
  text: string
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
  editedAt: string | null // ISO or null — set when the sender edited the text
  pinnedAt: string | null // ISO or null — pinned within the conversation
  pinnedBy: string | null // userId of whoever pinned (null when unpinned)
  parentId: string | null // Slack/Zulip thread root this reply belongs to
  viewOnce: boolean // view-once media gate (sender's own copies always render)
  viewedAt: string | null // when a non-sender consumed a view-once attachment
  viewedBy: string | null
  expiresAt: string | null // disappearing-message deadline (hard-purged after)
  linkUrl: string | null // canonical unfurl target
  linkPreview: LinkPreviewData | null
  poll: PollData | null
  translations: MessageTranslationEntry[] // persisted LLM translations by lang
  /** client-only marker: message is held in the offline outbox (never sent by server) */
  _queued?: boolean
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
  archivedAt: string | null // viewer's archive watermark (null = active chat)
  ttlSeconds: number // disappearing-message TTL for THIS chat (0 = off)
  broadcastMode: boolean // admin-only posting (groups/stage channels)
}

export type GroupRole = 'admin' | 'member'

/** Full detail for a chat room */
export interface ConversationDetail {
  id: string
  isGroup: boolean
  name: string | null
  createdAt: string
  updatedAt: string
  // per-member read watermark (→ double ticks) + group role (groups only)
  members: Array<AppUser & { lastReadAt: string; role: GroupRole }>
  myMutedUntil: string | null // viewer's notification-mute watermark (null = unmuted)
  inviteCode: string | null // shareable join code (groups only; null = no active link)
  ttlSeconds: number // disappearing-message TTL (0 = off)
  broadcastMode: boolean // admin-only posting
}

/** Public preview of an invite code before joining (GET /api/invite/[code]). */
export interface InvitePreview {
  code: string
  conversationId: string
  isGroup: boolean
  name: string | null
  memberCount: number
  /** true when ?userId= is already a participant (join is then idempotent) */
  alreadyMember: boolean
}

/** Global search hit — a message plus the conversation it lives in. */
export interface SearchResultMessage extends ChatMessage {
  /** resolved display title (group name or DM partner name) */
  conversationName: string
  isGroup: boolean
}

/** Telegram-style saved/starred bookmark row. */
export interface SavedItem {
  savedAt: string // ISO
  conversation: { id: string; isGroup: boolean; name: string | null }
  message: ChatMessage
}

/** Pending delayed-send row (scheduler). */
export interface ScheduledItem {
  id: string
  conversationId: string
  content: string
  scheduledAt: string // ISO
  sentAt: string | null
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
  type:
    | 'message:new'
    | 'message:deleted'
    | 'message:react'
    | 'message:edited'
    | 'message:pinned'
    | 'poll:voted' // full fresh message arrives (updated tally)
    | 'link:preview' // full fresh message arrives (unfurl attached)
    | 'translation:added' // full fresh message arrives (+1 translation)
    | 'message:viewed' // view-once media consumed → re-render gate
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

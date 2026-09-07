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
  avatar: string | null // profile photo path (/uploads/<uuid>.<ext>) — null = palette avatar fallback
  statusEmoji: string | null // Discord-style custom status glyph
  statusText: string | null // Discord-style custom status line
  createdAt: string // ISO
  /** ISO — "profile last active". R46: null when the user hides last seen
   *  (server scrubs the stamp; realtime presence is a separate signal). */
  lastSeenAt: string | null
}

/** Minimal sender embedded in messages */
export interface MessageAuthor {
  id: string
  name: string
  username: string | null
  color: string
  avatar: string | null // profile photo path (/api/uploads/<file>) — null = palette fallback
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
  kind: string // text|image|audio|sticker|location|file
  payload: string | null // JSON blob for kind extras (sticker emoji, location coords, effect name)
  deletedAt: string | null // ISO or null
  createdAt: string // ISO
  sender: MessageAuthor
  reactions: MessageReactionGroup[]
  replyTo: ReplySnippet | null
  imagePath: string | null // served via GET /api/uploads/[imagePath]
  audioPath: string | null // voice-note file served via GET /api/uploads/[audioPath]
  durationMs: number | null // voice-note length in milliseconds
  /** R40 additive — document attachment (kind 'file'): stored filename served
   *  via GET /api/uploads/[filePath], original filename for display, byte size. */
  filePath: string | null
  fileName: string | null
  fileSize: number | null
  /** R43 additive — cached voice-note transcription (real ASR service; null =
   *  never transcribed). Rendered under the voice bubble once present. */
  transcript: string | null
  transcribedAt: string | null
  editedAt: string | null // ISO or null — set when the sender edited the text
  pinnedAt: string | null // ISO or null — pinned within the conversation
  pinnedBy: string | null // userId of whoever pinned (null when unpinned)
  parentId: string | null // Slack/Zulip thread root this reply belongs to
  topicId: string | null // Zulip-style topic filing (null = General)
  anon: boolean // Session/SimpleX-style anonymous send
  anonAlias: string | null // deterministic incognito alias (shown instead of sender name)
  viewOnce: boolean // view-once media gate (sender's own copies always render)
  viewedAt: string | null // when a non-sender consumed a view-once attachment
  viewedBy: string | null
  expiresAt: string | null // disappearing-message deadline (hard-purged after)
  linkUrl: string | null // canonical unfurl target
  linkPreview: LinkPreviewData | null
  poll: PollData | null
  translations: MessageTranslationEntry[] // persisted LLM translations by lang
  /** R39 additive: true when the message was landed by a keyword automation
   *  (machine-sent — bubbles surface an honest "Automation" chip). Always
   *  present on server-serialized messages; omitted (undefined = false) on
   *  client-side optimistic/queued constructions. */
  viaAutomation?: boolean
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
  myStreak: { count: number } | null // R31-a viewer's LIVE chat streak (null = none / broken)
  /** R33-b additive: streak whose lastDay is YESTERDAY UTC with count >= 2 —
   *  still live (myStreak stays populated) but dies at tonight's UTC midnight
   *  unless the viewer messages today. null/absent = nothing at risk. */
  deadStreak?: { count: number; lastDay: string } | null
  /** R37 additive: honest end-state — a 2+ day chain whose lastDay is OLDER
   *  than yesterday (UTC); the day passed and the streak died. null/absent =
   *  none. Never populated beside a live myStreak or deadStreak. */
  lostStreak?: { count: number; best: number; lastDay: string } | null
  /** R33-b additive: channel/group photo path ("/api/uploads/<file>") — null = palette avatar fallback */
  photo: string | null
  pinnedAt: string | null // viewer's pin watermark (null = not pinned)
  mutedUntil: string | null // viewer's notification-mute watermark (null = unmuted)
  archivedAt: string | null // viewer's archive watermark (null = active chat)
  ttlSeconds: number // disappearing-message TTL for THIS chat (0 = off)
  broadcastMode: boolean // admin-only posting (groups/stage channels)
  isSelf: boolean // Signal-style Note to Self conversation
  /** R44 additive: viewer's mark-as-unread flag — the row shows the unread
   *  dot even when unreadCount is 0; cleared when the room is opened. */
  myManualUnread: boolean
  /** R45 — server-synced composer draft (cross-device; null = none). */
  myDraft: string | null
}

export type GroupRole = 'admin' | 'member'

/** One row of the broadcast-channel directory (GET /api/channels, R30-c). */
export interface ChannelSummary {
  id: string // conversation id
  name: string
  description: string
  createdAt: string
  memberCount: number // participants (= subscribers)
  isSubscribed: boolean // viewer has a participant row
  unread: boolean // viewer has an unread admin message (lastReadAt watermark)
  preview: string | null // last message content snippet (60 chars, may be null)
  /** R33-b additive: channel photo path ("/api/uploads/<file>") — null = broadcast-icon glass fallback */
  photo: string | null
}

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
  myStreak: { count: number; best: number } | null // R31-a viewer's LIVE chat streak (null = none / broken)
  /** R33-b additive — same semantics as ConversationSummary.deadStreak:
   *  live-but-dies-tonight streak (lastDay = yesterday UTC, count >= 2). */
  deadStreak?: { count: number; lastDay: string } | null
  /** R37 additive — same semantics as ConversationSummary.lostStreak:
   *  dead chain (lastDay older than yesterday UTC, count >= 2). */
  lostStreak?: { count: number; best: number; lastDay: string } | null
  /** R33-b additive: channel/group photo path ("/api/uploads/<file>") — null = palette avatar fallback */
  photo: string | null
  inviteCode: string | null // shareable join code (groups only; null = no active link)
  ttlSeconds: number // disappearing-message TTL (0 = off)
  broadcastMode: boolean // admin-only posting
  /** R38 additive: Signal screen security — frost the message area whenever
   *  the Pulse window loses focus (blur / hidden tab). false = plain room. */
  screenPrivacy: boolean
  /** R42 additive: the VIEWER's personal screen-security flag (per-participant
   *  veil). The room frosts when EITHER this or the room-wide flag is on. */
  myScreenPrivacy: boolean
  /** R44 additive: Telegram-style slow mode (0 = off; members wait Ns between
   *  sends, admins exempt — server enforces via 429 + retryAfter). */
  slowModeSeconds: number
  /** R44 additive: viewer's mark-as-unread flag (row badge until opened). */
  myManualUnread: boolean
  /** R45 — the viewer's server-synced composer draft (restored in the composer
   * when this device has no local draft for the conversation). */
  myDraft: string | null
  isSelf: boolean // Signal-style Note to Self conversation
  description: string // channel/group purpose line (R30-c)
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

/** Beeper/Zulip-style per-message reminder row (GET/POST /api/reminders). */
export interface ReminderItem {
  id: string
  conversationId: string
  /** anchored message — null = conversation-level reminder */
  messageId: string | null
  /** free-text context shown in the nudge (may be empty when anchored) */
  note: string
  remindAt: string // ISO
  /** set once the due-loop has delivered the nudge */
  firedAt: string | null
  createdAt: string // ISO
  /** minimal conversation info for sheet rows (name = group name or DM peer) */
  conversation: { id: string; name: string; isGroup: boolean }
  /** first ~120 chars of the anchored message; null when deleted/missing/unanchored */
  snippet: string | null
}

// ── R24 — topics, folders, tournaments, leaderboard ──────────

/** Zulip-style topic chip row (GET /api/conversations/[id]/topics). */
export interface TopicSummary {
  id: string
  name: string
  emoji: string
  lastMessageAt: string // ISO — sort key
  messageCount: number // real filed-message count
}

/** Signal/Beeper chat folder (GET /api/folders). */
export interface FolderSummary {
  id: string
  name: string
  emoji: string
  position: number
  conversationIds: string[] // membership in rail order
}

/** One row of the group/global leaderboard. */
export interface LeaderboardRow {
  userId: string
  name: string
  color: string
  xp: number
  messageCount: number
  gameWins: number
  tournamentPoints: number
}

/** Tournament season card data (GET /api/tournaments/[id]). */
export interface TournamentSummary {
  id: string
  conversationId: string
  name: string
  game: string
  status: 'running' | 'finished'
  createdAt: string
  endsAt: string | null
  entries: Array<{
    userId: string
    name: string
    color: string
    points: number
    wins: number
    losses: number
    draws: number
  }>
}

/** R39 — one keyword auto-reply rule (GET/POST /api/conversations/[id]/automations). */
export interface AutomationSummary {
  id: string
  conversationId: string
  trigger: string // 2-40 chars, matched as a standalone phrase
  reply: string // 1-500 chars
  enabled: boolean
  hits: number // lifetime fire count
  lastFiredAt: string | null // ISO or null
  createdAt: string // ISO
  /** admin who authored the rule — also the author of fired replies */
  createdBy: { id: string; name: string; color: string; avatar: string | null } | null
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

// ── Hub economy (wallet / tasks / market / logs / swap) ──────

export interface WalletState {
  userId: string
  coins: number
  gems: number
  streak: number
  lastCheckIn: string | null
  checkedInToday: boolean
}

export interface LedgerEntry {
  id: string
  kind: string
  asset: string
  amount: number // signed from the owner's perspective
  note: string
  counterpartyId: string | null
  createdAt: string
}

export interface HubTaskItem {
  id: string
  title: string
  status: 'todo' | 'doing' | 'done'
  createdAt: string
  updatedAt: string
}

export interface MarketListingItem {
  id: string
  title: string
  description: string
  price: number
  asset: string
  status: 'open' | 'sold'
  createdAt: string
  seller?: { id: string; name: string; username: string | null; color: string }
  buyer?: { id: string; name: string; username: string | null; color: string } | null
  mine: boolean
}

export interface LogEntry {
  id: string
  kind: string
  message: string
  meta: string | null
  createdAt: string
  user: { id: string; name: string; username: string | null; color: string } | null
}

export interface SwapInfo {
  rates: { pcPerGemBuy: number; pcPerGemSell: number }
  stats: {
    swaps: number
    pcBought: number
    pcSold: number
    circulatingCoins: number
    circulatingGems: number
  }
}

/** Real profile statistics behind the user profile sheet. */
export interface UserStats {
  messages: number
  reactions: number
  photos: number
  voiceNotes: number
  chats: number
  groups: number
  days: number
  joinedAt: string
  /** R46 — null when the profile owner hides last seen. */
  lastSeenAt: string | null
}

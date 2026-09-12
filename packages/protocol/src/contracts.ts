/**
 * Pulse shared protocol — the REAL wire contract the live Next.js API emits
 * and mini-services/pulse-socket relays. Web, Android (WireDtos.kt /
 * SocketContracts.kt) and iOS (WireDtos.swift / PulseSocketClient.swift)
 * parse these exact shapes. Source of truth; change here → change the
 * mirrors in the same commit.
 *
 * v2 (Wave 0): the socket event registry is now COMPLETE — every C→S and
 * S→C event the relay service actually handles, with typed payloads.
 * REST DTOs are unchanged.
 */

// ─────────────────────────────────────────────────────────────
// Socket.IO registry — the full surface of mini-services/pulse-socket
// ─────────────────────────────────────────────────────────────

/** Client → server events (socket.emit from any Pulse client). */
export const CLIENT_EVENTS = [
  'join', 'typing',
  'voice:join', 'voice:leave', 'voice:ptt', 'voice:chunk', 'voice:transcript',
  'stage:join', 'stage:hand', 'stage:approve', 'stage:mute', 'stage:end', 'stage:leave',
  'space:join', 'space:move', 'space:leave',
  'call:offer', 'call:answer', 'call:ice', 'call:reject', 'call:cancel', 'call:hangup',
] as const

/** Server → client events (socket.on from any Pulse client). */
export const SERVER_EVENTS = [
  'joined', 'presence:snapshot', 'typing',
  'message:new', 'message:deleted', 'message:read',
  'message:react', 'message:edited', 'message:pinned', 'message:viewed',
  'poll:voted', 'link:preview', 'translation:added', 'conversation:updated',
  'voice:roster', 'voice:ptt', 'voice:chunk', 'voice:transcript',
  'stage:state', 'stage:ended',
  'space:state',
  'call:offer', 'call:answer', 'call:ice', 'call:reject', 'call:cancel', 'call:hangup',
] as const

/** Flat union (legacy export — kept for older tooling). */
export const SOCKET_EVENTS = [...CLIENT_EVENTS, ...SERVER_EVENTS] as const

export type SocketEvent = (typeof SOCKET_EVENTS)[number]
export type ClientSocketEvent = (typeof CLIENT_EVENTS)[number]
export type ServerSocketEvent = (typeof SERVER_EVENTS)[number]

/** HTTP relay into the socket service: POST /notify (whitelist-checked). */
export const NOTIFY_EVENTS = [
  'message:new', 'message:deleted', 'message:read',
  'message:react', 'message:edited', 'message:pinned', 'message:viewed',
  'poll:voted', 'link:preview', 'translation:added', 'conversation:updated',
] as const

// ─────────────────────────────────────────────────────────────
// Socket payload types
// ─────────────────────────────────────────────────────────────

/** C→S join { userId } — registers presence + enters room user:{userId}. */
export interface JoinPayload {
  userId: string
}

/** S→C ack for join. */
export interface JoinedAck {
  onlineUserIds: string[]
}

/** S→C presence:snapshot — re-broadcast whenever anyone joins/leaves. */
export interface PresenceSnapshot {
  onlineUserIds: string[]
}

/** typing — C→S carries `recipients`; S→C relay drops them (server resolves rooms). */
export interface TypingEvent {
  conversationId: string
  userId: string
  userName: string
  isTyping: boolean
}

/** S→C message:read — a viewer advanced their watermark. */
export interface ReadEvent {
  conversationId: string
  userId: string
  lastReadAt: string
}

/**
 * Envelope for every event that relays a fresh message row
 * (message:new/deleted/react/edited/pinned/viewed, poll:voted,
 * link:preview, translation:added). `message` is the authoritative row.
 */
export interface SocketMessageEvent {
  type:
    | 'message:new'
    | 'message:deleted'
    | 'message:react'
    | 'message:edited'
    | 'message:pinned'
    | 'message:viewed'
    | 'poll:voted'
    | 'link:preview'
    | 'translation:added'
  message: WireChatMessage
  /** ids of all members except the actor. */
  recipientIds: string[]
  conversationId?: string
}

/** S→C conversation:updated — metadata changed (members, name, photo, flags). */
export interface ConversationUpdatedEvent {
  conversationId: string
  conversation?: WireConversationSummary
}

/** S→C voice:roster — who is in the voice room (VoicePeer). */
export interface VoicePeer {
  userId: string
  name: string
  username: string | null
  color: string
  joinedAt?: number
}

export interface VoiceRosterEvent {
  conversationId: string
  roster: VoicePeer[]
}

/** S→C voice:ptt — push-to-talk latch state of one peer. */
export interface VoicePttEvent {
  conversationId: string
  userId: string
  active: boolean
}

/** voice:chunk — 16 kHz PCM base64 frames (250 ms / 4000 samples), relayed to the room. */
export interface VoiceChunkEvent {
  conversationId: string
  userId: string
  seq: number
  data: string
}

/** S→C voice:transcript — ephemeral caption strip. */
export interface VoiceTranscriptEvent {
  conversationId: string
  userId: string
  text: string
}

/** S→C stage:state — full stage roster (host/speakers/hand-queue/muted). */
export interface StageStateEvent {
  conversationId: string
  state: Record<string, unknown>
}

/** S→C stage:ended — host closed the stage. */
export interface StageEndedEvent {
  conversationId: string
}

/** S→C space:state — spatial map snapshot (players + positions). */
export interface SpaceStateEvent {
  conversationId: string
  state: Record<string, unknown>
}

// ─────────────────────────────────────────────────────────────
// Call signaling (mirrors web src/lib/call-types.ts — R33-a)
// ─────────────────────────────────────────────────────────────

export type CallKind = 'voice' | 'video'
export type CallStatus = 'completed' | 'missed' | 'declined'
export type CallCancelReason = 'timeout' | 'cancel' | 'busy' | 'offline'

/** Fields every call:* payload carries. */
export interface CallSignalBase {
  callId: string
  conversationId: string
  from: string
  to: string
  kind: CallKind
}

/** C→S/S→C call:offer — opens the ring (30 s server-side timeout). */
export interface CallOfferPayload extends CallSignalBase {
  sdp: string
  callerName: string
  callerColor: string
  callerAvatar: string | null
}

/** call:answer — callee accepted; SDP answer attached. */
export interface CallAnswerPayload extends CallSignalBase {
  sdp: string
}

/** call:ice — thin ICE candidate triple (JSON-safe everywhere). */
export interface CallIcePayload extends CallSignalBase {
  candidate: { candidate?: string; sdpMid?: string | null; sdpMLineIndex?: number | null }
}

/** call:reject — callee declined (busy / explicit). */
export interface CallRejectPayload extends CallSignalBase {
  reason?: 'busy' | 'declined'
}

/** call:cancel — caller gave up before answer. */
export interface CallCancelPayload extends CallSignalBase {
  reason: CallCancelReason
}

/** call:hangup — either side ended an active call. */
export interface CallHangupPayload extends CallSignalBase {
  durationMs?: number
}

// ─────────────────────────────────────────────────────────────
// REST contract (unchanged from v1)
// ─────────────────────────────────────────────────────────────

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

/**
 * N3: the live wire groups reactions per emoji (serializers.groupReactions).
 * Android (ReactionDto) tolerates both this and the flat legacy shape.
 */
export interface WireReactionGroup {
  emoji: string
  userIds: string[]
  count: number
}

/** GET/POST /api/users — identity picker + contacts (N3). */
export interface WireUser {
  id: string
  name: string
  username?: string | null
  about?: string | null
  color?: string | null
  avatar?: string | null
  statusEmoji?: string | null
  statusText?: string | null
  createdAt?: string | null
  lastSeenAt?: string | null
  verified?: boolean | null
}

export interface WireUsersPage {
  users: WireUser[]
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
  reactions?: WireReactionGroup[] | WireReaction[]
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

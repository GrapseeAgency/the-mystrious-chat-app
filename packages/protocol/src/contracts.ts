/**
 * Pulse shared protocol — the single wire contract every client renders.
 * Web (Next.js), Android (Ktor DTOs) and iOS (Codable) all parse these shapes.
 * Source of truth: the web API's serializers (src/lib/serializers.ts + types.ts).
 * Change here → change there, in the same commit.
 */

export const SOCKET_EVENTS = [
  'message:new',
  'message:updated',
  'message:deleted',
  'typing',
  'presence',
  'voice:transcript',
  'call:ring',
  'call:accept',
  'call:end',
] as const

export type SocketEvent = (typeof SOCKET_EVENTS)[number]

/** Error envelope every REST route returns on failure. */
export interface ApiError {
  error: string
}

/** HTTP status → client failure kind. Android `PulseResult.Kind` mirrors this. */
export function failureKindFor(status: number): string {
  if (status === 401) return 'AUTH'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NOT_FOUND'
  if (status === 422) return 'VALIDATION'
  if (status === 429) return 'RATE_LIMITED'
  if (status >= 500) return 'SERVER'
  return 'UNKNOWN'
}

/** Core wire shapes (mirrored by Kotlin Models.kt and Swift PulseModels.swift). */
export interface WireUser {
  id: string
  name: string
  handle: string
  avatar: string | null
  bio: string | null
  lastSeen: string | null
  verified: boolean
}

export interface WireConversation {
  id: string
  kind: 'DM' | 'GROUP' | 'CHANNEL' | 'SPACE' | 'VOICE' | 'STAGE'
  title: string
  avatar: string | null
  lastMessagePreview: string | null
  lastActivityAt: string | null
  unreadCount: number
  isPinned: boolean
  isMuted: boolean
  isArchived: boolean
}

export interface WireMessage {
  id: string
  conversationId: string
  authorId: string
  authorName: string
  kind: 'TEXT' | 'IMAGE' | 'VOICE' | 'VIDEO' | 'FILE' | 'POLL' | 'RED_PACKET' | 'SYSTEM'
  body: string
  createdAt: string
  editedAt: string | null
  deletedAt: string | null
  replyToId: string | null
  threadRootId: string | null
  pinnedAt: string | null
  viewedOnce: boolean
}

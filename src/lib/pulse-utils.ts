// ─────────────────────────────────────────────────────────────
// Pulse Chat — client-side shared helpers (pure, no React)
// Avatar gradients, initials, time formatting, REST fetch helper.
// ─────────────────────────────────────────────────────────────
import type { AppUser, ConversationSummary } from '@/lib/types'

// ── Colors ───────────────────────────────────────────────────

export type AvatarColor =
  | 'emerald'
  | 'rose'
  | 'amber'
  | 'violet'
  | 'teal'
  | 'orange'
  | 'pink'
  | 'cyan'

export const PULSE_COLORS = [
  'emerald',
  'rose',
  'amber',
  'violet',
  'teal',
  'orange',
  'pink',
  'cyan',
] as const satisfies readonly AvatarColor[]

/** tailwind gradient classes keyed by avatar color (verbatim strings for JIT). */
export const AVATAR_GRADIENTS: Record<AvatarColor, string> = {
  emerald: 'from-emerald-400 to-emerald-600',
  rose: 'from-rose-400 to-rose-600',
  amber: 'from-amber-400 to-amber-600',
  violet: 'from-violet-400 to-violet-600',
  teal: 'from-teal-400 to-teal-600',
  orange: 'from-orange-400 to-orange-600',
  pink: 'from-pink-400 to-pink-600',
  cyan: 'from-cyan-400 to-cyan-600',
}

export function gradientFor(color: string): string {
  const key = PULSE_COLORS.find((c) => c === color)
  return AVATAR_GRADIENTS[key ?? 'emerald']
}

/** Group avatars — violet-family gradients picked by hashing the conv id. */
const GROUP_GRADIENTS = [
  'from-violet-400 to-purple-600',
  'from-fuchsia-400 to-purple-600',
  'from-purple-400 to-violet-700',
  'from-fuchsia-500 to-violet-700',
]

export function groupGradientFor(id: string): string {
  return GROUP_GRADIENTS[hashString(id) % GROUP_GRADIENTS.length]
}

export function hashString(value: string): number {
  let h = 0
  for (let i = 0; i < value.length; i += 1) {
    h = (h * 31 + value.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

// ── REST fetch helper ────────────────────────────────────────

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

function errorMessageFromBody(body: unknown, fallback: string): string {
  if (body !== null && typeof body === 'object' && !Array.isArray(body)) {
    const maybe = (body as Record<string, unknown>).error
    if (typeof maybe === 'string' && maybe.length > 0) return maybe
  }
  return fallback
}

/** JSON fetch against the Next API routes; throws ApiError with status. */
export async function apiJson<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    cache: 'no-store',
  })
  let body: unknown = null
  try {
    body = await res.json()
  } catch {
    body = null
  }
  if (!res.ok) {
    throw new ApiError(res.status, errorMessageFromBody(body, `Request failed (${res.status})`))
  }
  return body as T
}

export function jsonBody(payload: Record<string, unknown>): RequestInit {
  return { method: 'POST', body: JSON.stringify(payload) }
}

/** crypto.randomUUID with a fallback for odd browsers. */
export function uid(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return `c${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
  }
}

/** Best-effort raw haptic ping (preference-unaware; prefer haptic() from pulse-settings). */
export function buzz(pattern: number = 20): void {
  const nav = typeof navigator !== 'undefined' ? navigator : undefined
  const vib = nav as (Navigator & { vibrate?: (p: number | number[]) => boolean }) | undefined
  vib?.vibrate?.(pattern)
}

// ── Messages ─────────────────────────────────────────────────

/** Reaction palette shown in the message action sheet + chips. */
export const REACTION_CHOICES = ['👍', '❤️', '😂', '😮', '😢', '🎉'] as const

/** Quick composer emoji strip. */
export const EMOJI_PICKER_CHOICES = [
  '😀', '😂', '🥹', '😍', '😎', '🤔', '😴', '🥳',
  '👍', '🙏', '👏', '🔥', '❤️', '💜', '✨', '🎉',
  '🚀', '🌈', '☀️', '🌙', '☕', '🍕', '🎂', '⚽',
] as const

const JUMBO_EMOJI_RE = /^(?:\p{Extended_Pictographic}|\p{Emoji_Component}|\s|\u200d|\ufe0f){1,9}$/u

/** Pure-emoji short messages render extra large (WhatsApp-style). */
export function isJumboEmoji(content: string): boolean {
  const trimmed = content.trim()
  if (trimmed.length === 0 || trimmed.length > 24) return false
  if (!JUMBO_EMOJI_RE.test(trimmed)) return false
  return /\p{Extended_Pictographic}/u.test(trimmed)
}

/** Split text into plain/url segments so bubbles can auto-link URLs. */
export function splitUrlSegments(text: string): Array<{ kind: 'text' | 'url'; value: string }> {
  const URL_RE = /(https?:\/\/[^\s<]+|www\.[^\s<]+)/gi
  const out: Array<{ kind: 'text' | 'url'; value: string }> = []
  let last = 0
  for (const match of text.matchAll(URL_RE)) {
    const idx = match.index ?? 0
    if (idx > last) out.push({ kind: 'text', value: text.slice(last, idx) })
    out.push({ kind: 'url', value: match[0] })
    last = idx + match[0].length
  }
  if (last < text.length) out.push({ kind: 'text', value: text.slice(last) })
  return out
}

// ── Time formatting (en-US) ──────────────────────────────────

const hmFormatter = new Intl.DateTimeFormat('en-US', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

const dayMonthFormatter = new Intl.DateTimeFormat('en-US', { day: 'numeric', month: 'short' })

const monthYearFormatter = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' })

function safeDate(iso: string): Date | null {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

function isSameCalendarDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

/** "14:05" */
export function formatTime(iso: string): string {
  const d = safeDate(iso)
  return d ? hmFormatter.format(d) : ''
}

/**
 * Conversation-row stamp:
 * today → HH:mm · yesterday → "Yesterday" · else "3 Aug"
 */
export function formatListStamp(iso: string, now: Date = new Date()): string {
  const d = safeDate(iso)
  if (!d) return ''
  if (isSameCalendarDay(d, now)) return hmFormatter.format(d)
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (isSameCalendarDay(d, yesterday)) return 'Yesterday'
  return dayMonthFormatter.format(d)
}

/** Day chip label inside the chat room. */
export function formatDayChip(iso: string, now: Date = new Date()): string {
  const d = safeDate(iso)
  if (!d) return ''
  if (isSameCalendarDay(d, now)) return 'Today'
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (isSameCalendarDay(d, yesterday)) return 'Yesterday'
  return dayMonthFormatter.format(d)
}

/** Same calendar day between two ISO strings? */
export function isSameDayIso(aIso: string, bIso: string): boolean {
  const a = safeDate(aIso)
  const b = safeDate(bIso)
  return a !== null && b !== null && isSameCalendarDay(a, b)
}

/** "August 2026" */
export function formatMemberSince(iso: string): string {
  const d = safeDate(iso)
  return d ? monthYearFormatter.format(d) : ''
}

// ── Conversation helpers ─────────────────────────────────────

export interface MemberWithOptionalRead extends AppUser {
  lastReadAt?: string
}

/** Summary members carry lastReadAt at runtime though typed plain. */
export function withReadWatermarks(members: AppUser[]): MemberWithOptionalRead[] {
  return members as MemberWithOptionalRead[]
}

export function otherMemberOf(
  conversation: Pick<ConversationSummary, 'members'>,
  myId: string,
): MemberWithOptionalRead | null {
  return conversation.members.find((m) => m.id !== myId) ?? conversation.members[0] ?? null
}

export function conversationDisplayName(
  conversation: Pick<ConversationSummary, 'name' | 'isGroup' | 'members'>,
  myId: string,
): string {
  if (conversation.isGroup) return conversation.name?.trim() || 'Group'
  const other = otherMemberOf(conversation, myId)
  return other ? other.name : 'You'
}

/** Single-line preview of the last message for the chats list. */
export function conversationPreview(
  conversation: ConversationSummary,
  myId: string,
): { text: string; deleted: boolean; mine: boolean; senderName: string; isReply: boolean } {
  const last = conversation.lastMessage
  if (!last) {
    return { text: 'No messages yet', deleted: false, mine: false, senderName: '', isReply: false }
  }
  if (last.deletedAt) {
    return { text: '🚫 message deleted', deleted: true, mine: false, senderName: '', isReply: false }
  }
  const mine = last.senderId === myId
  const collapsed = last.content.replace(/\s+/g, ' ').trim()
  return {
    text: collapsed,
    deleted: false,
    mine,
    senderName: last.sender.name,
    isReply: last.replyTo !== null,
  }
}

/** Prefix shown before preview text ("You: ", "Maya: " in groups…). */
export function conversationPreviewPrefix(
  preview: ReturnType<typeof conversationPreview>,
  isGroup: boolean,
): string {
  if (preview.deleted || !preview.text) return ''
  const replyArrow = preview.isReply ? '↩ ' : ''
  if (preview.mine) return `${replyArrow}You: `
  if (isGroup) return `${replyArrow}${preview.senderName}: `
  return replyArrow
}

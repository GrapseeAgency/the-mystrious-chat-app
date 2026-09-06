// ─────────────────────────────────────────────────────────────
// Pulse Chat — full-screen chat room overlay.
// Bubbles with clustering, day chips, typing indicators,
// read receipts, optimistic sending, delete-for-everyone.
// ─────────────────────────────────────────────────────────────
'use client'

import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react'
import { AnimatePresence, animate, motion, useMotionValue, useSpring, useTransform, useVelocity } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import {
  ArrowDown,
  Bell,
  BellOff,
  CalendarClock,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronLeft,
  CloudOff,
  ChevronUp,
  Clock,
  Copy,
  CornerDownRight,
  Crown,
  Dices,
  EllipsisVertical,
  EyeOff,
  Flame,
  Forward,
  Gamepad2,
  Gift,
  Globe,
  HelpCircle,
  ImagePlus,
  Info,
  Link2,
  ListTodo,
  LoaderCircle,
  Lock,
  LogOut,
  Map as MapIcon,
  MapPin,
  Megaphone,
  MessageSquare,
  MessagesSquare,
  Mic,
  Minus,
  PartyPopper,
  Pause,
  Pencil,
  Phone,
  Pin,
  PinOff,
  Presentation,
  PictureInPicture2,
  Play,
  Plus,
  Podcast,
  Radio,
  Reply,
  RotateCcw,
  Search,
  SendHorizontal,
  ShieldCheck,
  Smile,
  Sparkles,
  SquareKanban,
  Sticker,
  Star,
  Timer,
  Trash2,
  Trophy,
  UserPlus,
  UserRoundMinus,
  VenetianMask,
  Video,
  Vote,
  VolumeX,
  X,
  Zap,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { toast } from 'sonner'
import type {
  AppUser,
  ChatMessage,
  ConversationDetail,
  ConversationSummary,
  MessageAuthor,
  ReminderItem,
  SavedItem,
  ScheduledItem,
  TopicSummary,
} from '@/lib/types'
import {
  apiJson,
  compressImageToDataUrl,
  conversationDisplayName,
  formatDayChip,
  formatListStamp,
  formatTime,
  hashString,
  isJumboEmoji,
  isSameDayIso,
  jsonBody,
  otherMemberOf,
  REACTION_CHOICES,
  EMOJI_PICKER_CHOICES,
  splitUrlSegments,
  uid,
} from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { spring, ease, pressTap, pressSpring, fireParticles, type ParticleKind } from '@/lib/motion'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
import { pulseOutboxStore, outboxCount } from '@/lib/pulse-outbox'
import { ForwardSheet } from '@/components/chat/forward-sheet'
import {
  GlassMenu,
  GlassMenuItem,
  GlassMenuLabel,
  GlassMenuSeparator,
  GlassMenuStrip,
} from '@/components/ui/glass-menu'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { cn } from '@/lib/utils'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { useMounted } from '@/hooks/use-mounted'
import { usePrefsValues } from '@/lib/prefs'
import { applyConvTint, effectiveConvWallpaper, getConvTheme } from '@/lib/conv-theme'
import type { PulsePrefs } from '@/lib/prefs-defaults'
import {
  MessageEffectsLayer,
  isMessageEffect,
  type ActiveEffect,
  type EffectOrigin,
  type MessageEffectName,
} from '@/components/chat/message-effects'
import { StickerPicker, stickerGradient, type StickerPick } from '@/components/chat/sticker-picker'
import {
  LocationBubble,
  LocationShareSheet,
  parseLocationPayload,
  type LocationPayload,
} from '@/components/chat/location-share'
import { SlashPalette } from '@/components/chat/slash-palette'
import { VoiceRoomSheet, useVoiceRoom } from "@/components/chat/voice-room-sheet"
import { useWhiteboardSheet } from "@/components/chat/whiteboard-sheet"
// ── R23: beyond-chat wave 2 — red packets, games, kanban, events ──
import RedPacketBubble from "@/components/chat/redpacket-bubble"
import { useRedPacketSheet } from "@/components/chat/redpacket-sheet"
import GameTicTacToeCard from "@/components/chat/game-tictactoe-card"
import { useKanbanSheet } from "@/components/chat/kanban-sheet"
import { useEventsSheet } from "@/components/chat/events-sheet"
// ── R24-b: topics rail + stage/space/tournament (parallel crews' sheets) ──
import { TopicBar } from "@/components/chat/topic-bar"
import { useStageSheet } from "@/components/chat/stage-room-sheet"
import { useSpaceSheet } from "@/components/chat/space-sheet"
import { useTournamentSheet } from "@/components/chat/tournament-sheet"
import TournamentCard from "@/components/chat/tournament-card"
import {
  TOPIC_CREATED_EVENT,
  STAGE_OPEN_EVENT,
  SPACE_OPEN_EVENT,
  TOURNAMENT_OPEN_EVENT,
} from "@/components/chat/slash-palette"

/** /game palette → chat-room creates the match (DM: vs the peer; group: open) */
const NEW_GAME_EVENT = 'pulse:new-game'
import { PipChat } from '@/components/chat/pip-chat'
import { usePipChat } from '@/components/chat/pip-store'
import { GroupInfoSheet } from '@/components/chat/group-info-sheet'
import { useNavStyle } from '@/components/chat/nav-router'
import { useHashRoute, navigateHash, replaceHash, backHash } from '@/lib/hash-router'
// ── R27-c: chat-room hash sub-pages + compact glass pins sheet ──
import { RoomInfoPage } from '@/components/chat/room-info-page'
import { RoomSearchPage } from '@/components/chat/room-search-page'
import { RoomPinsSheet } from '@/components/chat/room-pins-sheet'
// ── R37: DM header ShieldCheck badge — the shared safety-number sheet lives
// in its own file (also mounted by the room info Encryption row); this room
// only renders the badge and opens the sheet on tap.
import { SafetySheet, safetyKey, type SafetyState } from '@/components/chat/safety-sheet'
// ── R30-b: per-message reminders — glass sheet + due-loop + jump event ──
import {
  RemindersSheet,
  REMINDER_JUMP_EVENT,
  fetchReminders,
  parseRelativeReminder,
  remindersKey,
  useReminderDueLoop,
  type ReminderJumpDetail,
} from '@/components/chat/reminders-sheet'
// ── R33-a: 1:1 voice/video calls — since R35-b the call session + overlay
// live at SHELL level (main-shell) so rings surface app-wide; the room only
// hands its DM peer to the shell through the onStartCall callback.
import type { CallPeer } from '@/components/chat/call-overlay'
import type { CallKind } from '@/lib/call-types'

interface DetailResponse {
  conversation: ConversationDetail
}
interface MessagesResponse {
  messages: ChatMessage[]
  hasMore?: boolean
  total?: number
}
interface SendResponse {
  message: ChatMessage
  /** R31-a: present ONLY when this send changed the streak (grown or restarted) */
  streak?: { count: number; best: number; continued: boolean } | null
  /** R31-a: XP actually granted by this send (0 once the daily cap is hit) */
  xpAwarded?: number
}
interface DeleteResponse {
  message: ChatMessage
}
interface SearchResponse {
  messages: ChatMessage[]
  total?: number
}

const CLUSTER_WINDOW_MS = 5 * 60 * 1000
const NEAR_BOTTOM_PX = 160
const OLDER_PAGE_SIZE = 40
const MESSAGES_PAGE_SIZE = 200
const MIN_VOICE_MS = 600
/** Discord/WhatsApp-flavored slash commands understood by the composer. */
const SLASH_COMMANDS = [
  { cmd: '/me', args: '<action>', help: 'Send an italic action line' },
  { cmd: '/shrug', args: '[text]', help: 'Append ¯\\_(ツ)_/¯' },
  { cmd: '/tableflip', args: '[text]', help: 'Append (╯°□°）╯︵ ┻━┻' },
  { cmd: '/unflip', args: '[text]', help: 'Prefix ┬─┬ ノ( ゜-゜ノ' },
  { cmd: '/roll', args: '[AdM]', help: 'Roll dice, e.g. /roll 2d6' },
  { cmd: '/poll', args: '', help: 'Open the live-poll builder' },
  { cmd: '/schedule', args: '', help: 'Schedule this message for later' },
  { cmd: '/sticker', args: '', help: 'Open the sticker packs' },
  { cmd: '/location', args: '', help: 'Share a live map pin' },
  { cmd: '/topic', args: '<name>', help: 'Create a topic and file here' },
  { cmd: '/stage', args: '', help: 'Open the live stage room' },
  { cmd: '/space', args: '', help: 'Open the spatial space' },
  { cmd: '/tournament', args: '', help: 'Start a group tournament' },
  { cmd: '/effects', args: '<effect>', help: 'confetti · lasers · echo · sparkles' },
  { cmd: '/help', args: '', help: 'Show every command' },
] as const

/** Lucide glyph for effect toasts / chips (R26-b emoji purge). */
const EFFECT_ICON: Record<MessageEffectName, LucideIcon> = {
  confetti: PartyPopper,
  lasers: Zap,
  echo: Radio,
  sparkles: Sparkles,
}

/** Parse one rolled die — returns null on malformed input. */
function rollDice(spec: string): { rolls: number[]; total: number } | null {
  const m = /^(\d{1,2})d(\d{1,3})$/i.exec(spec.trim())
  const count = m ? Math.min(Math.max(parseInt(m[1], 10), 1), 12) : 1
  const sides = m ? Math.min(Math.max(parseInt(m[2], 10), 2), 1000) : 0
  if (!m && spec.trim().length > 0) return null
  if (!m) return null
  const rolls = Array.from({ length: count }, () => {
    const buf = new Uint32Array(1)
    crypto.getRandomValues(buf)
    return Math.floor((buf[0] / 4294967296) * sides) + 1
  })
  return { rolls, total: rolls.reduce((a, b) => a + b, 0) }
}

/** Bubble corner token ← prefs.bubbleRadius. */
const BUBBLE_RADIUS: Record<'md' | 'lg' | 'pill', string> = {
  md: 'rounded-xl',
  lg: 'rounded-2xl',
  pill: 'rounded-3xl',
}

/** Safe-parse a red-packet payload {packetId, …} — never throws. (R23-a) */
function parseRedPacketPayload(payload: string | null): { packetId: string } | null {
  const p = parseMessagePayload(payload)
  return typeof p.packetId === 'string' && p.packetId.length > 0 ? { packetId: p.packetId } : null
}

/** Safe-parse a game payload {matchId, …} — never throws. (R23-b) */
function parseGamePayload(payload: string | null): { matchId: string } | null {
  const p = parseMessagePayload(payload)
  return typeof p.matchId === 'string' && p.matchId.length > 0 ? { matchId: p.matchId } : null
}

/** Safe-parse a tournament payload {tournamentId, name, game} — never throws. (R24) */
function parseTournamentPayload(payload: string | null): { tournamentId: string } | null {
  const p = parseMessagePayload(payload)
  return typeof p.tournamentId === 'string' && p.tournamentId.length > 0
    ? { tournamentId: p.tournamentId }
    : null
}

// ── R24-b: client mirror of the server's deterministic incognito alias —
// identical FNV-1a + word lists to the messages route, so the OPTIMISTIC
// bubble already shows the exact alias the server will store. ──
const ANON_ADJECTIVES = ['Swift', 'Quiet', 'Neon', 'Ember', 'Frost', 'Lucky', 'Cosmic', 'Silent'] as const
const ANON_ANIMALS = ['Falcon', 'Otter', 'Panda', 'Wolf', 'Comet', 'Tiger', 'Raven', 'Fox'] as const

function anonStableHash(value: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

function anonAliasPreview(userId: string, conversationId: string): string {
  const hash = anonStableHash(`${userId}:${conversationId}`)
  const adjective = ANON_ADJECTIVES[hash % ANON_ADJECTIVES.length]
  const animal = ANON_ANIMALS[Math.floor(hash / ANON_ADJECTIVES.length) % ANON_ANIMALS.length]
  return `${adjective} the ${animal}`
}

/** Safe-parse a sticker payload {emoji, pack} — never throws. */
function parseSticker(payload: string | null): { emoji: string; pack: string } | null {
  const p = parseMessagePayload(payload)
  const emoji = typeof p.emoji === 'string' ? p.emoji : ''
  if (!emoji) return null
  const pack = typeof p.pack === 'string' ? p.pack : 'Pulse'
  return { emoji, pack }
}

/** Safe-parse a message payload blob — never throws, always an object. */
function parseMessagePayload(payload: string | null): Record<string, unknown> {
  if (!payload) return {}
  try {
    const raw: unknown = JSON.parse(payload)
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return {}
    return raw as Record<string, unknown>
  } catch {
    return {}
  }
}

/** Wallpaper prefs → soft radial glow colors for the chat background. */
function wallpaperGlows(wallpaper: PulsePrefs['wallpaper'], dark: boolean): [string, string] {
  switch (wallpaper) {
    case 'aurora':
      return dark
        ? ['rgba(16,185,129,0.12)', 'rgba(139,92,246,0.09)']
        : ['rgba(16,185,129,0.11)', 'rgba(139,92,246,0.07)']
    case 'dusk':
      return dark
        ? ['rgba(245,158,11,0.10)', 'rgba(244,63,94,0.09)']
        : ['rgba(245,158,11,0.10)', 'rgba(244,63,94,0.07)']
    case 'forest':
      return dark
        ? ['rgba(5,150,105,0.13)', 'rgba(132,204,22,0.07)']
        : ['rgba(5,150,105,0.12)', 'rgba(132,204,22,0.06)']
    case 'mono':
      return ['transparent', 'transparent']
    case 'none':
    default:
      return dark
        ? ['rgba(16,185,129,0.055)', 'rgba(20,184,166,0.04)']
        : ['rgba(16,185,129,0.05)', 'rgba(20,184,166,0.035)']
  }
}

interface SlashOutcome {
  kind: 'send'
  content: string
}

/** Transform a leading slash command into real message content (or a UI action code). */
function applySlash(
  rawInput: string,
):
  | SlashOutcome
  | { kind: 'poll' }
  | { kind: 'schedule' }
  | { kind: 'remind'; arg: string }
  | { kind: 'help' }
  | { kind: 'sticker' }
  | { kind: 'location' }
  | { kind: 'topic'; name: string }
  | { kind: 'tool'; tool: 'whiteboard' | 'redpacket' | 'kanban' | 'events' | 'game' | 'stage' | 'space' | 'tournament' }
  | { kind: 'effect'; effect: MessageEffectName; content: string }
  | { kind: 'error'; message: string } {
  const input = rawInput.trim()
  const m = new RegExp('^' + String.fromCharCode(92) + '/(\\w+)(?:\\s+([\\s\\S]+))?$').exec(input)
  if (!m) return { kind: 'send', content: input }
  const [, word, rest] = m
  const arg = (rest ?? '').trim()
  switch (word.toLowerCase()) {
    case 'me': {
      if (arg.length === 0) return { kind: 'error', message: 'Usage: /me waves hello' }
      return { kind: 'send', content: `_${arg.slice(0, 1998)}_` }
    }
    case 'shrug':
      return { kind: 'send', content: `${arg}${arg.length > 0 ? ' ' : ''}¯\\_(ツ)_/¯` }
    case 'tableflip':
      return { kind: 'send', content: `${arg}${arg.length > 0 ? ' ' : ''}(╯°□°）╯︵ ┻━┻` }
    case 'unflip':
      return { kind: 'send', content: `┬─┬ ノ( ゜-゜ノ${arg.length > 0 ? ` ${arg}` : ''}` }
    case 'roll': {
      if (arg.length === 0) {
        const roll = rollDice('1d6')
        return { kind: 'send', content: `Rolled **1d6**: *${roll?.total ?? '?'}*` }
      }
      const roll = rollDice(arg)
      if (!roll) return { kind: 'error', message: 'Usage: /roll AdM — e.g. /roll 2d6' }
      const parts = roll.rolls.join(' + ')
      return { kind: 'send', content: `Rolled **${arg.toLowerCase()}**: ${parts} = *${roll.total}*` }
    }
    case 'poll':
      return { kind: 'poll' }
    case 'schedule':
      return { kind: 'schedule' }
    case 'remind':
      // R30-b: conversation-level reminder — the chat-room body parses the
      // trailing relative time (via parseRelativeReminder) and POSTs it.
      return { kind: 'remind', arg }
    case 'sticker':
      return { kind: 'sticker' }
    case 'location':
      return { kind: 'location' }
    case 'whiteboard':
    case 'redpacket':
    case 'kanban':
    case 'events':
    case 'game':
    case 'stage':
    case 'space':
    case 'tournament':
      // R23/R24: standalone tools — sheets/games live in the component body
      return { kind: 'tool', tool: word.toLowerCase() as 'whiteboard' | 'redpacket' | 'kanban' | 'events' | 'game' | 'stage' | 'space' | 'tournament' }
    case 'topic': {
      // R24-b: create a Zulip topic, then file the NEXT send under it
      if (arg.length === 0) return { kind: 'error', message: 'Usage: /topic Design' }
      return { kind: 'topic', name: arg }
    }
    case 'effects': {
      const effectWord = arg.split(/\s+/)[0]?.toLowerCase() ?? ''
      if (!isMessageEffect(effectWord)) {
        return { kind: 'error', message: 'Usage: /effects confetti|lasers|echo|sparkles [text]' }
      }
      const text = arg.slice(effectWord.length).trim()
      return { kind: 'effect', effect: effectWord, content: text }
    }
    case 'help':
      return { kind: 'help' }
    default: {
      // R21-a bot commands are answered server-side by @pulseai — pass them
      // through as a normal message so the bot engine can reply. Typos that
      // match nothing still surface the /help affordance below.
      const botCommands = new Set(['math', 'flip', '8ball', 'rps', 'dice', 'time', 'wallet'])
      if (botCommands.has(word.toLowerCase())) {
        return { kind: 'send', content: input }
      }
      return {
        kind: 'error',
        message: `Unknown command "/${word}" — try /help`,
      }
    }
  }
}
/** Effect name → app-wide particle burst kind (R22 premium FX layer). */
const EFFECT_PARTICLES: Record<MessageEffectName, ParticleKind> = {
  confetti: 'confetti',
  lasers: 'burst',
  echo: 'burst',
  sparkles: 'stars',
}

/** R30-b: quick remind presets — dates are computed per open, never cached. */
function remindPresets(): Array<{ label: string; at: Date }> {
  const now = new Date()
  const tomorrow9 = new Date(now)
  tomorrow9.setDate(tomorrow9.getDate() + 1)
  tomorrow9.setHours(9, 0, 0, 0)
  const nextWeek = new Date(now)
  nextWeek.setDate(nextWeek.getDate() + 7)
  return [
    { label: 'In 1 hour', at: new Date(now.getTime() + 3_600_000) },
    { label: 'In 3 hours', at: new Date(now.getTime() + 3 * 3_600_000) },
    { label: 'Tomorrow 9:00', at: tomorrow9 },
    { label: 'Next week', at: nextWeek },
  ]
}

/** Local-time min attribute for the custom reminder datetime-local input. */
function remindMinAttr(): string {
  const d = new Date(Date.now() - 60_000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * Fire the full-screen particle layer from an element's viewport position
 * (used for reaction bursts — one synchronous getBoundingClientRect, no rAF
 * churn). Respects reduced motion.
 */
function fireParticlesAt(el: Element | null, kind: ParticleKind = 'hearts', count = 24): void {
  if (!el || typeof window === 'undefined') return
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  const r = el.getBoundingClientRect()
  if (r.width === 0 && r.height === 0) return
  const w = window.innerWidth || 1
  const h = window.innerHeight || 1
  fireParticles({
    kind,
    count,
    x: Math.min(0.97, Math.max(0.03, (r.left + r.width / 2) / w)),
    y: Math.min(0.97, Math.max(0.03, (r.top + r.height / 2) / h)),
  })
}

/** "1:23" (minutes:seconds) for voice notes + record timer. */
function formatVoicems(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

type ClusterItem =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'unread'; key: string }
  | { kind: 'msg'; key: string; message: ChatMessage; head: boolean; tail: boolean }

export function ChatRoom({
  me,
  conversationId: conversationIdProp,
  unreadAnchorMs: unreadAnchorMsProp = null,
  initialJumpMessageId: initialJumpMessageIdProp = null,
  onStartCall,
  onClose,
}: {
  me: AppUser
  conversationId: string
  /** pre-open read watermark frozen by the chats list at tap time (unread divider) */
  unreadAnchorMs?: number | null
  /** global-search hit — jump + flash this message once history renders */
  initialJumpMessageId?: string | null
  /** R35-b: dial through the shell's single call session + overlay
      (omitted by hosts without the shell mount → call buttons hide) */
  onStartCall?: (call: { conversationId: string; peer: CallPeer; kind: CallKind }) => void
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const realtime = usePulseRealtime()
  const { resolvedTheme } = useTheme()
  const themeMounted = useMounted()
  const prefs = usePrefsValues()
  // floating bottom docks (acrylic dock / edge bar / radial FAB) need clearance;
  // the solid rail lives in a layout column → zero inset
  const [navStyle] = useNavStyle()
  const dockInset = navStyle === 'rail' ? 0 : 84
  const startPipChat = usePipChat((s) => s.open)
  const closePipChat = usePipChat((s) => s.close)
  const pipConversationId = usePipChat((s) => (s.isOpen ? s.conversationId : null))

  // ── in-room conversation switching ────────────────────────
  // Tapping a profile sheet's "Message" opens the DM WITHOUT tearing this
  // room down: the whole component re-keys its queries onto the new id.
  const [switchedId, setSwitchedId] = useState<string | null>(null)
  const conversationId = switchedId ?? conversationIdProp
  /** anchor/jump overrides reset when the user hops to another chat in-room */
  const [anchorOverride, setAnchorOverride] = useState<number | null | undefined>(undefined)
  const [jumpOverride, setJumpOverride] = useState<string | null | undefined>(undefined)
  const unreadAnchorMs = anchorOverride !== undefined ? anchorOverride : unreadAnchorMsProp
  const initialJumpMessageId = jumpOverride !== undefined ? jumpOverride : initialJumpMessageIdProp

  useEffect(() => {
    // external navigation (chats list) always wins over an in-room switch
    setSwitchedId(null)
    setAnchorOverride(undefined)
    setJumpOverride(undefined)
    // R24-b: topics are per-conversation — never leak a view across rooms
    setActiveTopicId(null)
    setAnonNext(false)
    anonNextRef.current = false
  }, [conversationIdProp])

  // device connectivity → offline texts are queued in the outbox
  const [isOffline, setIsOffline] = useState(false)
  useEffect(() => {
    const sync = () => setIsOffline(!navigator.onLine)
    sync()
    window.addEventListener('online', sync)
    window.addEventListener('offline', sync)
    return () => {
      window.removeEventListener('online', sync)
      window.removeEventListener('offline', sync)
    }
  }, [])

  // ── keyboard lift (R22): visualViewport shrink → composer rides the keyboard ──
  // Transform-only (no layout thrash); only while a text field actually holds
  // focus so browser-chrome collapses never bounce the room.
  useEffect(() => {
    const vv = window.visualViewport
    if (!vv) return
    let raf = 0
    const update = () => {
      raf = 0
      const active = document.activeElement
      const typing =
        active instanceof HTMLElement &&
        (active.tagName === 'TEXTAREA' || active.tagName === 'INPUT')
      const overlap = Math.max(0, window.innerHeight - vv.height - vv.offsetTop)
      const lift = typing && overlap > 90 ? Math.min(overlap, 420) : 0
      setKbdLift((prev) => (Math.abs(prev - lift) > 1 ? lift : prev))
    }
    const schedule = () => {
      if (!raf) raf = requestAnimationFrame(update)
    }
    vv.addEventListener('resize', schedule)
    vv.addEventListener('scroll', schedule)
    window.addEventListener('focusin', schedule)
    window.addEventListener('focusout', schedule)
    update()
    return () => {
      vv.removeEventListener('resize', schedule)
      vv.removeEventListener('scroll', schedule)
      window.removeEventListener('focusin', schedule)
      window.removeEventListener('focusout', schedule)
      if (raf) cancelAnimationFrame(raf)
    }
  }, [])

  // mount watermark / send pop / missed-count effects live further down —
  // they depend on queries + mutations declared below (see "scrolling" section).

  // restore persisted draft once per opened conversation
  const [input, setInput] = useState(() => pulseDraftsStore.getState().drafts[conversationId] ?? '')
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  /** header menu → inline mute preset choices */
  const [muteChoicesOpen, setMuteChoicesOpen] = useState(false)
  const [selected, setSelected] = useState<ChatMessage | null>(null)
  /** viewport point the action menu sprouts from (null = keyboard-open → center) */
  const [menuAnchor, setMenuAnchor] = useState<{ x: number; y: number } | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [showJump, setShowJump] = useState(false)
  /** messages that landed while scrolled away — badge on the jump-to-latest pill */
  const [missedCount, setMissedCount] = useState(0)
  const missedCountRef = useRef(0)
  const lastSeenLenRef = useRef(0)
  // ── R22 composer premium state ────────────────────────────────
  /** attachments tray (springs open above the capsule) */
  const [trayOpen, setTrayOpen] = useState(false)
  const [trayEffectsOpen, setTrayEffectsOpen] = useState(false)
  /** mirror for the stable autosize callback (no re-creation on toggle) */
  const trayOpenRef = useRef(false)
  /** capsule focus-within → emerald hairline ring */
  const [composerFocus, setComposerFocus] = useState(false)
  /** px the composer is lifted while the on-screen keyboard is open */
  const [kbdLift, setKbdLift] = useState(0)
  /** 0→N success pop tick for the send/mic slot after a message lands */
  const [sendPop, setSendPop] = useState(0)
  const wasSendingRef = useRef(false)
  /** mount watermark — only messages newer than this animate their entrance */
  const mountMsRef = useRef(0)
  /** real ids that just replaced optimistic temps — skip their re-entrance */
  const landedIdsRef = useRef<Map<string, number>>(new Map())
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)
  /** message being edited (Telegram-style composer edit mode) */
  const [editing, setEditing] = useState<ChatMessage | null>(null)
  /** pinned-messages sheet */
  const [pinnedOpen, setPinnedOpen] = useState(false)
  // ── R30-b: reminders sheet + per-message remind picker ─────
  const [remindersOpen, setRemindersOpen] = useState(false)
  /** message the remind picker anchors to (null = picker closed) */
  const [remindTarget, setRemindTarget] = useState<ChatMessage | null>(null)
  /** picker's inline custom datetime row visibility + value */
  const [remindCustom, setRemindCustom] = useState(false)
  const [remindCustomAt, setRemindCustomAt] = useState('')
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null)
  /** {message, emoji} → who-reacted sheet */
  const [reactionInfo, setReactionInfo] = useState<{ message: ChatMessage; emoji: string } | null>(null)
  /** seen-by detail sheet (read receipts) */
  const [seenByOpen, setSeenByOpen] = useState(false)
  /** message whose info the sheet shows — null = the latest own message (read-by stack tap) */
  const [infoMessage, setInfoMessage] = useState<ChatMessage | null>(null)
  const [sendingImage, setSendingImage] = useState(false)
  /** uploaded image awaiting an optional caption → caption sheet */
  const [pendingImage, setPendingImage] = useState<{ imagePath: string; preview: string } | null>(null)
  const [captionDraft, setCaptionDraft] = useState('')
  const [hasMoreHistory, setHasMoreHistory] = useState(false)
  /** mirror of hasMoreHistory readable from stable callbacks without re-creating them */
  const hasMoreHistoryRef = useRef(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [recording, setRecording] = useState(false)
  const [recordMs, setRecordMs] = useState(0)
  const [sendingVoice, setSendingVoice] = useState(false)

  // ── search overlay + jump-to-message ───────────────────────
  /** message currently flashing (sub-page search hit / reply jump) */
  const [highlight, setHighlight] = useState<{ id: string; nonce: number } | null>(null)
  const highlightTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // ── forward-message sheet (fresh mount per open) ──────────
  const [forwardTarget, setForwardTarget] = useState<ChatMessage | null>(null)
  const [forwardGeneration, setForwardGeneration] = useState(0)
  const [forwardMounted, setForwardMounted] = useState(false)
  const [forwardOpen, setForwardOpen] = useState(false)

  // ── threads (Slack/Zulip) ───────────────────────────────────
  /** open thread root — drawer shows its replies */
  const [threadRoot, setThreadRoot] = useState<ChatMessage | null>(null)
  /** lifted thread composer draft (survives drawer re-mounts) */
  const [threadDraft, setThreadDraft] = useState('')

  // ── live polls ─────────────────────────────────────────────
  const [pollBuilderOpen, setPollBuilderOpen] = useState(false)

  // ── scheduled sends (Telegram-style) ──────────────────────
  const [scheduleFor, setScheduleFor] = useState<string | null>(null) // pending draft text
  const [scheduledListOpen, setScheduledListOpen] = useState(false)

  // disappearing-message TTL submenu inside the header menu
  const [ttlChoicesOpen, setTtlChoicesOpen] = useState(false)
  /** slash-command cheat-sheet dialog */
  const [helpOpen, setHelpOpen] = useState(false)

  // ── stickers · location · effects (R19-b) ──────────────────
  const [stickerOpen, setStickerOpen] = useState(false)
  const [locationOpen, setLocationOpen] = useState(false)
  /** armed full-screen effect applied to the NEXT sent message */
  const [pendingEffect, setPendingEffect] = useState<MessageEffectName | null>(null)
  /** Esc-dismisses the slash palette until the draft changes again */
  const [slashDismissed, setSlashDismissed] = useState(false)
  /** full-screen group management sheet (R19-c contract) */
  const [groupInfoOpen, setGroupInfoOpen] = useState(false)

  // ── live voice room (R21-b) — engine survives sheet close ──
  const [voiceOpen, setVoiceOpen] = useState(false)
  const voice = useVoiceRoom(conversationId, me)

  // ── collaborative whiteboard (R21-c) — palette event opens it ──
  const whiteboard = useWhiteboardSheet(conversationId, me.id)

  // ── R24-b: Zulip-style topics (groups) + incognito arming ─────
  /** active topic view — null = General (the implicit whole-room stream) */
  const [activeTopicId, setActiveTopicId] = useState<string | null>(null)
  /** Venetian-mask arming — next send posts anonymously (groups only) */
  const [anonNext, setAnonNext] = useState(false)
  const anonNextRef = useRef(false)
  /** previous activeTopicId — detects topic switches to re-arm scroll anchoring */
  const prevTopicRef = useRef<string | null>(null)

  const viewportRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const nearBottomRef = useRef(true)

  const applyHasMore = useCallback((next: boolean) => {
    hasMoreHistoryRef.current = next
    setHasMoreHistory(next)
  }, [])
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** pointer coords captured at press-start → menu anchor when the hold fires */
  const menuPointRef = useRef<{ x: number; y: number } | null>(null)
  /** until this timestamp, the click that ends a long-press is swallowed */
  const suppressPressRef = useRef(0)
  /** flipped (inside rAF) after the first history fetch so render stays ref-free */
  const [historyLoaded, setHistoryLoaded] = useState(false)
  /** pending scroll-anchor restore after prepending an older page */
  const scrollRestoreRef = useRef<{ prevHeight: number; prevTop: number } | null>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const recordChunksRef = useRef<Blob[]>([])
  const recordStartedAtRef = useRef(0)
  const recordTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const recordCancelRef = useRef(false)

  // ── data ───────────────────────────────────────────────────

  const detail = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: async (): Promise<ConversationDetail> => {
      const res = await apiJson<DetailResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversation
    },
    staleTime: 15_000,
    // safety net: keeps read-ticks/membership fresh even if the socket path degrades
    refetchInterval: 6_000,
  })

  // R24-b: General view = whole room (existing key — unchanged behavior);
  // an active topic gets its own key so caches never bleed between views.
  const messagesKey =
    activeTopicId === null
      ? (['messages', conversationId] as const)
      : (['messages', conversationId, activeTopicId] as const)

  const messages = useQuery({
    queryKey: messagesKey,
    queryFn: async (): Promise<ChatMessage[]> => {
      const topicSuffix = activeTopicId !== null ? `&topicId=${encodeURIComponent(activeTopicId)}` : ''
      const res = await apiJson<MessagesResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=${MESSAGES_PAGE_SIZE}${topicSuffix}`,
      )
      // Merge with whatever is cached (older pages loaded via "Load older")
      // so a background refetch never amputates already-loaded history.
      const previous = queryClient.getQueryData<ChatMessage[]>(messagesKey)
      if (!previous || previous.length === 0) {
        applyHasMore(res.total !== undefined ? res.messages.length < res.total : res.hasMore === true)
        return res.messages
      }
      const byId = new Map(previous.map((m) => [m.id, m]))
      for (const m of res.messages) byId.set(m.id, m)
      applyHasMore(res.total !== undefined ? byId.size < res.total : res.hasMore === true)
      return [...byId.values()].sort(
        (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
      )
    },
    staleTime: 15_000,
    // safety net: messages still arrive within seconds even without websocket realtime
    refetchInterval: 3_500,
  })

  /** Fetch the next page of history and prepend it, keeping scroll anchored. */
  const loadOlder = useCallback(async () => {
    const list = messages.data ?? []
    if (loadingOlder || list.length === 0) return
    const oldest = list[0]
    const el = viewportRef.current
    const prevHeight = el?.scrollHeight ?? 0
    const prevTop = el?.scrollTop ?? 0
    setLoadingOlder(true)
    try {
      const topicSuffix = activeTopicId !== null ? `&topicId=${encodeURIComponent(activeTopicId)}` : ''
      const res = await apiJson<MessagesResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=${OLDER_PAGE_SIZE}&before=${encodeURIComponent(oldest.createdAt)}${topicSuffix}`,
      )
      // Arm the anchor only now — the very next render (data applied) restores it.
      scrollRestoreRef.current = { prevHeight, prevTop }
      let cachedCount = res.messages.length
      queryClient.setQueryData<ChatMessage[]>(messagesKey, (old) => {
        if (!old || old.length === 0) return res.messages
        const known = new Set(old.map((m) => m.id))
        const additions = res.messages.filter((m) => !known.has(m.id))
        cachedCount = old.length + additions.length
        return [...additions, ...old]
      })
      applyHasMore(res.total !== undefined ? cachedCount < res.total : res.hasMore === true)
      haptic(6)
    } catch {
      toast.error('Could not load older messages')
    } finally {
      setLoadingOlder(false)
    }
  }, [messages.data, loadingOlder, conversationId, activeTopicId, messagesKey, queryClient, applyHasMore])

  // scroll anchor: after prepending, keep the viewport pinned to the same content
  useLayoutEffect(() => {
    const pending = scrollRestoreRef.current
    const el = viewportRef.current
    if (!pending || !el) return
    scrollRestoreRef.current = null
    el.scrollTop = el.scrollHeight - pending.prevHeight + pending.prevTop
  })

  // ── R24-b: message-cache plumbing that spans BOTH views ─────
  // The General view keeps the whole-room cache; the active topic view has
  // its own key. Mutations patch both so optimistic rows never vanish when
  // the user flips between General and a topic mid-flight.
  const patchMessageViews = useCallback(
    (updater: (old: ChatMessage[] | undefined) => ChatMessage[] | undefined) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], updater)
      if (activeTopicId !== null) {
        queryClient.setQueryData<ChatMessage[]>(['messages', conversationId, activeTopicId], updater)
      }
    },
    [queryClient, conversationId, activeTopicId],
  )

  // Switching topics swaps the message view — re-arm the first-load scroll
  // anchor so every topic starts pinned to its newest message.
  useEffect(() => {
    if (prevTopicRef.current === null) {
      prevTopicRef.current = activeTopicId
      return
    }
    if (prevTopicRef.current !== activeTopicId) {
      prevTopicRef.current = activeTopicId
      setHistoryLoaded(false)
      setJumpOverride(null)
    }
  }, [activeTopicId])

  // ── jump-to-message machinery (search hits + quoted replies) ─

  /** Smooth-scroll the thread so the target message sits mid-viewport. */
  const scrollToMessageEl = useCallback((messageId: string): boolean => {
    const vp = viewportRef.current
    if (!vp) return false
    const node = vp.querySelector<HTMLElement>(`[data-mid="${CSS.escape(messageId)}"]`)
    if (!node) return false
    const rect = node.getBoundingClientRect()
    const vpRect = vp.getBoundingClientRect()
    const target =
      vp.scrollTop + (rect.top - vpRect.top) - vp.clientHeight / 2 + rect.height / 2
    vp.scrollTo({ top: Math.max(0, target), behavior: 'smooth' })
    return true
  }, [])

  const flashHighlight = useCallback((messageId: string) => {
    if (highlightTimerRef.current !== null) clearTimeout(highlightTimerRef.current)
    setHighlight({ id: messageId, nonce: Date.now() })
    highlightTimerRef.current = setTimeout(() => {
      highlightTimerRef.current = null
      setHighlight(null)
    }, 1500)
  }, [])

  /**
   * Jump the thread to any message. When it predates the loaded window,
   * silently page back through history (bounded) until it shows up.
   */
  const jumpToMessage = useCallback(
    async (messageId: string) => {
      const readCache = () =>
        queryClient.getQueryData<ChatMessage[]>(['messages', conversationId]) ?? []
      haptic(8)
      if (readCache().some((m) => m.id === messageId)) {
        scrollToMessageEl(messageId)
        flashHighlight(messageId)
        return
      }
      let cached = readCache()
      let more = hasMoreHistoryRef.current
      let paged = 0
      while (more && cached.length > 0 && paged < 14) {
        paged += 1
        try {
          const res = await apiJson<SearchResponse>(
            `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=60&before=${encodeURIComponent(cached[0].createdAt)}`,
          )
          if (res.messages.length === 0) break
          // Plain prepend; no scroll-restore arm — we re-anchor on the hit below.
          queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
            const base = old ?? cached
            const known = new Set(base.map((m) => m.id))
            return [...res.messages.filter((m) => !known.has(m.id)), ...base].sort(
              (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
            )
          })
          const byId = new Map<string, ChatMessage>()
          for (const m of [...cached, ...res.messages]) byId.set(m.id, m)
          cached = [...byId.values()].sort(
            (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
          )
          more =
            res.total !== undefined ? cached.length < res.total : res.messages.length >= 60
          hasMoreHistoryRef.current = more
          if (cached.some((m) => m.id === messageId)) break
        } catch {
          toast.error('Could not page back through history')
          return
        }
      }
      if (paged > 0) applyHasMore(hasMoreHistoryRef.current)
      if (cached.some((m) => m.id === messageId)) {
        requestAnimationFrame(() => {
          scrollToMessageEl(messageId)
          flashHighlight(messageId)
        })
      } else {
        toast.info('That message could not be found in this chat')
      }
    },
    [conversationId, queryClient, scrollToMessageEl, flashHighlight, applyHasMore],
  )

  const jumpToReply = useCallback(
    (parentId: string) => {
      void jumpToMessage(parentId)
    },
    [jumpToMessage],
  )

  // ── R30-b: reminder jumps — same room → scroll to the anchor message;
  // other room → close the sheet and point at the right chat (cross-room
  // jump is a known gap, the toast keeps the outcome honest) ──
  useEffect(() => {
    const onReminderJump = (event: Event) => {
      const detail = (event as CustomEvent<ReminderJumpDetail>).detail
      if (!detail) return
      setRemindersOpen(false)
      if (detail.conversationId === conversationId) {
        if (detail.messageId) void jumpToMessage(detail.messageId)
      } else {
        toast.info(
          `That reminder lives in ${detail.conversationName || 'another chat'} — open it to see the message`,
        )
      }
    }
    window.addEventListener(REMINDER_JUMP_EVENT, onReminderJump)
    return () => window.removeEventListener(REMINDER_JUMP_EVENT, onReminderJump)
  }, [conversationId, jumpToMessage])

  // ── R27-c: hash-routed room sub-pages (#/room/<id>/info | /search) ──
  // The room itself is state-mounted (not hash-driven); its sub-pages ride
  // the real hash router so browser back works. Only a hash scoped to THIS
  // conversation opens a sub-page.
  const hashPath = useHashRoute().path
  const roomSubPage = useMemo<'info' | 'search' | null>(() => {
    if (!hashPath.startsWith('/room/')) return null
    const rest = hashPath.slice('/room/'.length)
    const slash = rest.indexOf('/')
    if (slash <= 0) return null
    if (rest.slice(0, slash) !== conversationId) return null
    const leaf = rest.slice(slash + 1)
    return leaf === 'info' || leaf === 'search' ? leaf : null
  }, [hashPath, conversationId])

  // room close / in-room switch → clear a lingering #/room/… hash in place
  useEffect(() => {
    return () => {
      if (typeof window !== 'undefined' && window.location.hash.startsWith('#/room/')) {
        replaceHash('/')
      }
    }
  }, [conversationId])

  // ── forward-message sheet ─────────────────────────────────

  const startForward = useCallback((message: ChatMessage | null) => {
    if (!message || message.deletedAt) return
    setForwardTarget(message)
    setForwardGeneration((g) => g + 1)
    setForwardMounted(true)
    setForwardOpen(false)
    setTimeout(() => setForwardOpen(true), 30)
  }, [])

  const handleForwardClose = useCallback((next: boolean) => {
    setForwardOpen(next)
    if (!next) setTimeout(() => setForwardMounted(false), 300)
  }, [])

  // unmount safety: clear highlight timers
  useEffect(
    () => () => {
      if (highlightTimerRef.current !== null) clearTimeout(highlightTimerRef.current)
    },
    [],
  )


  // ── realtime registration + read receipts ──────────────────

  useEffect(() => {
    realtime.setActiveConversation(conversationId)
    return () => realtime.setActiveConversation(null)
  }, [conversationId, realtime])

  /** POST read marker (visible rooms only) then patch local caches. */
  const markVisibleRead = useCallback(() => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    if ((messages.data ?? []).length === 0) return
    fetch(`/api/conversations/${encodeURIComponent(conversationId)}/read`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: me.id }),
    })
      .then(() => {
        const nowIso = new Date().toISOString()
        queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
          old
            ? {
                ...old,
                members: old.members.map((m) =>
                  m.id === me.id ? { ...m, lastReadAt: nowIso } : m,
                ),
              }
            : old,
        )
        // refresh list badges (my unread for this room is now zero)
        queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      })
      .catch(() => undefined)
  }, [conversationId, me.id, messages.data, queryClient])

  // opening the room + every new batch of messages ⇒ (debounced) read
  useEffect(() => {
    const timer = setTimeout(markVisibleRead, 600)
    return () => clearTimeout(timer)
  }, [markVisibleRead])

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'visible') markVisibleRead()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [markVisibleRead])

  // ── derived ────────────────────────────────────────────────

  const detailData = detail.data
  const other = useMemo(
    () => (detailData ? otherMemberOf(detailData, me.id) : null),
    [detailData, me.id],
  )
  const isGroup = detailData?.isGroup ?? false
  const displayName = detailData ? conversationDisplayName(detailData, me.id) : ''

  // ── R37: Signal paradigm — DM header ShieldCheck verification badge.
  // DMs ONLY (never groups/channels/self-chat — otherMemberOf falls back to
  // myself there, so isSelf is checked explicitly). Shares the
  // ['safety', peerId, meId] cache key with the room info page, so a verify
  // made in either surface flips both instantly.
  const dmPeer = !isGroup && other !== null && detailData?.isSelf !== true ? other : null
  const [safetyOpen, setSafetyOpen] = useState(false)
  const safety = useQuery({
    queryKey: dmPeer ? safetyKey(dmPeer.id, me.id) : ['safety', '-', me.id],
    queryFn: ({ queryKey }) =>
      apiJson<SafetyState>(
        `/api/users/${encodeURIComponent(queryKey[1])}/safety?userId=${encodeURIComponent(me.id)}`,
      ),
    enabled: dmPeer !== null,
    staleTime: 10_000,
  })

  // ── R33-a → R35-b: 1:1 voice/video calls — the WebRTC + signaling state
  // machine and the <CallOverlay> now live at SHELL level (main-shell), so
  // incoming rings surface app-wide; this room only forwards dial requests
  // ({ conversationId, peer, kind }) through the onStartCall prop.

  const recipients = useMemo(
    () => (detailData ? detailData.members.filter((m) => m.id !== me.id).map((m) => m.id) : []),
    [detailData, me.id],
  )

  // ── R23: external-message contract — sheets/cards that create messages
  // (red-packet send, game rematch) push the fresh ChatMessage through this
  // window event so the room appends it instantly; 3.5s polling is the fallback.
  // R24-b: topic-filed arrivals also append to the active topic view.
  useEffect(() => {
    const handler = (e: Event) => {
      const msg = (e as CustomEvent<ChatMessage>).detail
      if (!msg || msg.conversationId !== conversationId) return
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
        if (!old || old.some((m) => m.id === msg.id)) return old
        return [...old, msg].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))
      })
      if (activeTopicId !== null && msg.topicId === activeTopicId) {
        queryClient.setQueryData<ChatMessage[]>(['messages', conversationId, activeTopicId], (old) => {
          if (!old || old.some((m) => m.id === msg.id)) return old
          return [...old, msg].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))
        })
      }
    }
    window.addEventListener('pulse:external-message', handler)
    return () => window.removeEventListener('pulse:external-message', handler)
  }, [conversationId, activeTopicId, queryClient])

  // ── R23-b: /game palette entry → create a tic-tac-toe match.
  // DM: challenge the peer directly; group: open challenge anyone can claim.
  const createGame = useCallback(async () => {
    try {
      const res = await apiJson<{ match: { id: string }; message: ChatMessage }>('/api/games', {
        method: 'POST',
        body: JSON.stringify({
          userId: me.id,
          conversationId,
          ...(recipients.length === 1 ? { opponentId: recipients[0] } : {}),
        }),
      })
      window.dispatchEvent(new CustomEvent<ChatMessage>('pulse:external-message', { detail: res.message }))
      toast.success('Tic-tac-toe challenge sent')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Could not start the game')
    }
  }, [conversationId, me.id, recipients])

  useEffect(() => {
    const handler = () => {
      void createGame()
    }
    window.addEventListener(NEW_GAME_EVENT, handler)
    return () => window.removeEventListener(NEW_GAME_EVENT, handler)
  }, [createGame])

  const typers = realtime.typersIn(conversationId, me.id)
  const typerLabel = useMemo(() => {
    if (typers.length === 0) return ''
    if (!isGroup) return 'typing…'
    const names = typers.map((t) => t.userName)
    if (names.length === 1) return `${names[0]} is typing…`
    if (names.length === 2) return `${names[0]} and ${names[1]} are typing…`
    return `${names.length} people are typing…`
  }, [typers, isGroup])

  const onlineOthers = useMemo(
    () =>
      detailData
        ? detailData.members.filter((m) => m.id !== me.id && realtime.onlineIds.has(m.id)).length
        : 0,
    [detailData, me.id, realtime.onlineIds],
  )

  /** highest read watermark among OTHER members → double ticks */
  const othersMaxReadMs = useMemo(() => {
    if (!detailData) return Number.NEGATIVE_INFINITY
    let max = Number.NEGATIVE_INFINITY
    for (const m of detailData.members) {
      if (m.id === me.id) continue
      const t = Date.parse(m.lastReadAt)
      if (!Number.isNaN(t) && t > max) max = t
    }
    return max
  }, [detailData, me.id])

  // ── group read-by stack (last own message) ────────────

  /** newest own non-pending message still in the loaded window */
  const lastOwnMessage = useMemo(() => {
    const list = messages.data ?? []
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const m = list[i]
      if (m.senderId === me.id && m.deletedAt === null && !m.id.startsWith('temp-')) return m
    }
    return null
  }, [messages.data, me.id])

  /** members (≠ me) who have read the last own message → tiny avatar stack */
  const readByLast = useMemo(() => {
    if (!lastOwnMessage || !detailData) return null
    const createdMs = Date.parse(lastOwnMessage.createdAt)
    if (Number.isNaN(createdMs)) return null
    const members = detailData.members
      .filter((m) => m.id !== me.id && Date.parse(m.lastReadAt) >= createdMs)
      .map((m) => ({ id: m.id, name: m.name, color: m.color, avatar: m.avatar }))
    const others = detailData.members.length - 1
    return members.length > 0 ? { members, all: members.length >= others } : null
  }, [lastOwnMessage, detailData, me.id])

  /** tap the read-by stack → seen-by detail sheet (latest own message) */
  const openSeenBy = useCallback(() => {
    haptic(8)
    setInfoMessage(null)
    setSeenByOpen(true)
  }, [])

  /** bubble chip → open this root's thread sheet (Slack/Zulip) */
  const openThread = useCallback((message: ChatMessage) => {
    setThreadRoot(message)
  }, [])

  /** options dialog → per-message info sheet (any own message) */
  const openMessageInfo = useCallback((message: ChatMessage) => {
    haptic(8)
    setInfoMessage(message)
    setSelected(null)
    setSeenByOpen(true)
  }, [])

  const items = useMemo<ClusterItem[]>(() => {
    const list = messages.data ?? []
    const nowMs = Date.now()
    const visible = list.filter((m) => {
      if (m.parentId !== null) return false // Slack/Zulip: thread replies live in their own sheet
      if (m.expiresAt !== null && Date.parse(m.expiresAt) <= nowMs) return false
      return true
    })
    const now = new Date()

    interface Entry {
      message: ChatMessage
      head: boolean
    }
    const built: Entry[] = []
    let prev: ChatMessage | null = null
    for (const message of visible) {
      const clusterBreak =
        prev === null ||
        !isSameDayIso(prev.createdAt, message.createdAt) ||
        prev.senderId !== message.senderId ||
        // R24-b: an incognito send always opens a fresh cluster (different mask)
        prev.anon !== message.anon ||
        prev.anonAlias !== message.anonAlias ||
        Date.parse(message.createdAt) - Date.parse(prev.createdAt) > CLUSTER_WINDOW_MS
      built.push({ message, head: clusterBreak })
      prev = message
    }

    const out: ClusterItem[] = []
    let dayAnchor: ChatMessage | null = null
    let dividerPlaced = unreadAnchorMs === null
    for (let i = 0; i < built.length; i += 1) {
      const { message, head } = built[i]
      if (dayAnchor === null || !isSameDayIso(dayAnchor.createdAt, message.createdAt)) {
        out.push({ kind: 'day', key: `day-${message.id}`, label: formatDayChip(message.createdAt, now) })
        dayAnchor = message
      }
      if (
        !dividerPlaced &&
        message.senderId !== me.id &&
        message.deletedAt === null &&
        unreadAnchorMs !== null &&
        Date.parse(message.createdAt) > unreadAnchorMs
      ) {
        out.push({ kind: 'unread', key: 'unread-divider' })
        dividerPlaced = true
      }
      out.push({
        kind: 'msg',
        key: message.id,
        message,
        head,
        tail: i === built.length - 1 || built[i + 1].head,
      })
    }
    return out
  }, [messages.data, unreadAnchorMs, me.id])

  const lastMessageId =
    messages.data && messages.data.length > 0 ? messages.data[messages.data.length - 1].id : null

  /** rootId → live reply count (drives the ↳ chip under parent bubbles) */
  const threadCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const m of messages.data ?? []) {
      if (m.parentId === null || m.deletedAt !== null) continue
      counts.set(m.parentId, (counts.get(m.parentId) ?? 0) + 1)
    }
    return counts
  }, [messages.data])

  // ── scrolling ──────────────────────────────────────────────

  const scrollToBottom = useCallback((smooth: boolean) => {
    const el = viewportRef.current
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  }, [])

  /** a bubble image finished loading → re-anchor to bottom if we were there */
  const handleImageLoaded = useCallback(() => {
    if (nearBottomRef.current) {
      requestAnimationFrame(() => scrollToBottom(true))
    }
  }, [scrollToBottom])

  // ── full-screen message effects (confetti/lasers/echo/sparkles) ──
  const [activeEffect, setActiveEffect] = useState<ActiveEffect | null>(null)
  const effectQueueRef = useRef<Array<{ effect: MessageEffectName; origin: EffectOrigin }>>([])
  const effectNonceRef = useRef(0)
  const activeEffectRef = useRef<ActiveEffect | null>(null)
  /** cache-tail bookkeeping so incoming messages fire their effect exactly once */
  const seenTailRef = useRef<{ lastId: string | null; processed: Set<string> }>({
    lastId: null,
    processed: new Set(),
  })

  const triggerEffectFor = useCallback(
    (messageId: string, effect: MessageEffectName) => {
      if (prefs.reducedMotion) return // reduced motion → zero effects
      // R22: full-screen particle companion (confetti→confetti · sparkles→stars ·
      // lasers/echo→burst) fired from the composer position, layered UNDER the
      // existing per-message effect canvas.
      fireParticles({ kind: EFFECT_PARTICLES[effect], x: 0.5, y: 0.85, count: 90 })
      const viewport = viewportRef.current
      const host = viewport?.parentElement ?? null
      let origin: EffectOrigin = { x: 0.5, y: 0.62 }
      if (viewport && host) {
        const bubble = viewport.querySelector<HTMLElement>(`[data-mid="${CSS.escape(messageId)}"]`)
        if (bubble) {
          const b = bubble.getBoundingClientRect()
          const r = host.getBoundingClientRect()
          if (r.width > 0 && r.height > 0) {
            origin = {
              x: Math.min(0.97, Math.max(0.03, (b.left + b.width / 2 - r.left) / r.width)),
              y: Math.min(0.97, Math.max(0.03, (b.top + b.height / 2 - r.top) / r.height)),
            }
          }
        }
      }
      if (activeEffectRef.current === null) {
        effectNonceRef.current += 1
        const activated: ActiveEffect = { effect, origin, nonce: effectNonceRef.current }
        activeEffectRef.current = activated
        setActiveEffect(activated)
      } else if (effectQueueRef.current.length < 3) {
        // one canvas instance; rapid triggers line up instead of leaking
        effectQueueRef.current.push({ effect, origin })
      }
    },
    [prefs.reducedMotion],
  )

  const handleEffectDone = useCallback(() => {
    activeEffectRef.current = null
    setActiveEffect(null)
    requestAnimationFrame(() => {
      if (activeEffectRef.current !== null) return
      const next = effectQueueRef.current.shift()
      if (!next) return
      effectNonceRef.current += 1
      const activated: ActiveEffect = { effect: next.effect, origin: next.origin, nonce: effectNonceRef.current }
      activeEffectRef.current = activated
      setActiveEffect(activated)
    })
  }, [])

  /** Fire payload effects for messages appended to the tail of the cache. */
  useEffect(() => {
    const list = messages.data
    if (!list || list.length === 0) return
    const state = seenTailRef.current
    const tailId = list[list.length - 1].id
    if (state.lastId === null || !list.some((m) => m.id === state.lastId)) {
      // first load or cache reset (room switch) → seed silently, never replay history
      state.lastId = tailId
      state.processed = new Set(list.map((m) => m.id))
      return
    }
    state.lastId = tailId
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const m = list[i]
      if (state.processed.has(m.id)) break
      state.processed.add(m.id)
      const effect = parseMessagePayload(m.payload).effect
      if (isMessageEffect(effect)) triggerEffectFor(m.id, effect)
    }
    if (state.processed.size > 400) {
      state.processed = new Set(list.map((m) => m.id))
    }
  }, [messages.data, triggerEffectFor])

  useEffect(() => {
    if (lastMessageId === null || !historyLoaded) return
    // initial search-jump owns the first positioning — no bottom auto-scroll race
    if (initialJumpMessageId !== null) return
    requestAnimationFrame(() => {
      if (nearBottomRef.current) {
        scrollToBottom(true)
      } else {
        setShowJump(true)
        haptic(10)
      }
    })
  }, [lastMessageId, historyLoaded, scrollToBottom, initialJumpMessageId])

  useEffect(() => {
    if (messages.isSuccess && !historyLoaded) {
      // global-search hit: skip the plain bottom anchor, land on the hit instead.
      // Deferred until the room's slide-in spring has mostly settled — running the
      // centering math mid-flight measures a transformed layout and mis-aims.
      if (initialJumpMessageId !== null) {
        const t = setTimeout(() => {
          void jumpToMessage(initialJumpMessageId)
        }, 420)
        setHistoryLoaded(true)
        return () => clearTimeout(t)
      }
      const frame = requestAnimationFrame(() => {
        scrollToBottom(false)
        setHistoryLoaded(true)
      })
      return () => cancelAnimationFrame(frame)
    }
    return undefined
  }, [messages.isSuccess, historyLoaded, scrollToBottom, initialJumpMessageId, jumpToMessage])

  const handleScroll = useCallback(() => {
    const el = viewportRef.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    nearBottomRef.current = distance < NEAR_BOTTOM_PX
    if (nearBottomRef.current) {
      if (missedCountRef.current > 0) {
        missedCountRef.current = 0
        setMissedCount(0)
      }
      setShowJump(false)
    }
  }, [])

  // mount watermark: freeze "now" when the first history page commits —
  // only messages newer than this get the spring entrance (never history).
  useEffect(() => {
    if (historyLoaded) mountMsRef.current = Date.now()
  }, [historyLoaded])

  // off-screen arrivals → badge count on the jump-to-latest pill
  useEffect(() => {
    const len = messages.data?.length ?? 0
    if (nearBottomRef.current) {
      lastSeenLenRef.current = len
      if (missedCountRef.current !== 0) {
        missedCountRef.current = 0
        setMissedCount(0)
      }
    } else if (len > lastSeenLenRef.current) {
      missedCountRef.current += len - lastSeenLenRef.current
      lastSeenLenRef.current = len
      setMissedCount(missedCountRef.current)
    } else if (len < lastSeenLenRef.current) {
      // room switch / cache reset
      lastSeenLenRef.current = len
      missedCountRef.current = 0
      setMissedCount(0)
    }
  }, [messages.data])

  // ── sending ────────────────────────────────────────────────

  const sendMessage = useMutation({
    mutationFn: async ({
      clientId,
      content,
      replyToId,
      imagePath,
      audioPath,
      durationMs,
      parentId,
      viewOnce,
      kind,
      payload,
    }: {
      clientId: string
      content: string
      replyToId?: string
      imagePath?: string
      audioPath?: string
      durationMs?: number
      parentId?: string
      viewOnce?: boolean
      /** rich kinds: 'text' | 'sticker' | 'location' (effects ride kind:'text' + payload) */
      kind?: string
      /** structured extras — sticker {emoji,pack} · location {lat,lng,label} · {effect} */
      payload?: Record<string, unknown>
    }) => {
      const res = await apiJson<SendResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            senderId: me.id,
            content,
            ...(replyToId ? { replyToId } : {}),
            ...(imagePath ? { imagePath } : {}),
            ...(audioPath ? { audioPath, ...(durationMs ? { durationMs } : {}) } : {}),
            ...(parentId ? { parentId } : {}),
            ...(viewOnce ? { viewOnce: true } : {}),
            ...(kind ? { kind } : {}),
            ...(payload ? { payload } : {}),
            // R24-b: file the send under the active topic (thread replies stay
            // unfiled) + incognito arming (server honors it in groups only)
            ...(activeTopicId !== null && !parentId ? { topicId: activeTopicId } : {}),
            ...(anonNextRef.current && isGroup ? { anon: true } : {}),
          }),
        },
      )
      return { res, clientId }
    },
    onMutate: async ({ clientId, content, replyToId, imagePath, audioPath, durationMs, parentId, viewOnce, kind, payload }) => {
      const parentSnapshot = replyToId && replyTo && replyTo.id === replyToId
        ? {
            id: replyTo.id,
            content: replyTo.content,
            senderName: replyTo.sender.name,
            deleted: replyTo.deletedAt !== null,
          }
        : null
      // R24-b: the optimistic row already wears the incognito mask + topic
      // filing it will carry on the server (deterministic alias = same value).
      const tempAnon = anonNextRef.current && isGroup
      const temp: ChatMessage = {
        id: `temp-${clientId}`,
        conversationId,
        senderId: me.id,
        content,
        kind: kind ?? 'text',
        payload: payload ? JSON.stringify(payload) : null,
        deletedAt: null,
        createdAt: new Date().toISOString(),
        sender: { id: me.id, name: me.name, username: me.username, color: me.color, avatar: me.avatar },
        reactions: [],
        replyTo: parentSnapshot,
        imagePath: imagePath ?? null,
        audioPath: audioPath ?? null,
        durationMs: durationMs ?? null,
        editedAt: null,
        pinnedAt: null,
        pinnedBy: null,
        parentId: parentId ?? null,
        topicId: activeTopicId !== null && !parentId ? activeTopicId : null, // R24-b: active-topic filing
        anon: tempAnon,
        anonAlias: tempAnon ? anonAliasPreview(me.id, conversationId) : null,
        viewOnce: viewOnce === true,
        viewedAt: null,
        viewedBy: null,
        expiresAt: null,
        linkUrl: null,
        linkPreview: null,
        poll: null,
        translations: [],
      }
      patchMessageViews((old) => (old ? [...old, temp] : [temp]))
      if (parentId) {
        // optimistic echo inside the open thread sheet too
        queryClient.setQueryData<ChatMessage[]>(['thread', parentId], (old) => {
          const base = old ?? []
          return [...base.filter((m) => m.id !== temp.id), temp]
        })
      }
    },
    onSuccess: ({ res, clientId }, vars) => {
      const real = res.message
      // R31-a: Snapchat-style streak nudge — fires only when THIS send GREW
      // the streak (second-or-later consecutive day). Same-day re-sends and
      // restarts stay silent; one send = one bump per UTC day.
      const grewStreak = res.streak
      if (grewStreak && grewStreak.continued && grewStreak.count >= 2) {
        toast.success(
          grewStreak.count === 2 ? '2-day streak — keep it alive' : `${grewStreak.count}-day streak`,
          { icon: <Flame className="size-4 text-amber-500" aria-hidden /> },
        )
      }
      setReplyTo(null)
      // R24-b: incognito is one-shot — disarm after a successful send
      if (anonNextRef.current) {
        anonNextRef.current = false
        setAnonNext(false)
      }
      if (real.topicId !== null) {
        // keep the topic chip count badge honest right away
        void queryClient.invalidateQueries({ queryKey: ['topics', conversationId] })
      }
      // the real row replaces the optimistic temp — remember it so the bubble
      // entrance spring doesn't replay for a message that already animated in
      landedIdsRef.current.set(real.id, Date.now())
      if (landedIdsRef.current.size > 60) {
        const cutoff = Date.now() - 10_000
        for (const [id, ts] of landedIdsRef.current) if (ts < cutoff) landedIdsRef.current.delete(id)
      }
      patchMessageViews((old) => {
        if (!old) return [real]
        const hadReal = old.some((m) => m.id === real.id)
        const cleaned = old.filter(
          (m) =>
            m.id !== `temp-${clientId}` &&
            !(m.id.startsWith('temp-') && m.content === real.content && m.senderId === real.senderId),
        )
        return hadReal ? cleaned : [...cleaned, real]
      })
      if (vars.parentId) {
        queryClient.setQueryData<ChatMessage[]>(['thread', vars.parentId], (old) => {
          const base = old ?? []
          return base.some((m) => m.id === real.id)
            ? base.map((m) => (m.id === real.id ? real : m))
            : [...base.filter((m) => m.id !== `temp-${clientId}`), real]
        })
      }
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      // iMessage-grade send effects — play instantly for our own sends
      const sentEffect = parseMessagePayload(real.payload).effect
      if (isMessageEffect(sentEffect) && !prefs.reducedMotion) {
        seenTailRef.current.processed.add(real.id)
        triggerEffectFor(real.id, sentEffect)
      }
      // link previews: fire-and-forget unfurl on outbound URL messages
      if (!vars.parentId && /https?:\/\/|(^|\s)www\./i.test(real.content)) {
        void apiJson(`/api/messages/${encodeURIComponent(real.id)}/unfurl`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id }),
        }).catch(() => undefined)
      }
    },
    onError: (_error, { clientId }) => {
      patchMessageViews((old) => old?.filter((m) => m.id !== `temp-${clientId}`) ?? old)
      toast.error('Message failed to send')
    },
  })

  // send-button success pop: fires once per completed send (pending → done).
  // Declared after the mutation so the deps read live isPending values.
  useEffect(() => {
    const pendingNow = sendMessage.isPending
    if (wasSendingRef.current && !pendingNow) setSendPop((t) => t + 1)
    wasSendingRef.current = pendingNow
  }, [sendMessage.isPending])

  /** Toggle an emoji reaction (optimistic; server response is truth). */
  const toggleReaction = useMutation({
    mutationFn: async ({ messageId, emoji }: { messageId: string; emoji: string }) => {
      const res = await apiJson<SendResponse>(`/api/messages/${encodeURIComponent(messageId)}/react`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, emoji }),
      })
      return res.message
    },
    onMutate: async ({ messageId, emoji }) => {
      patchMessageViews((old) =>
        old
          ? old.map((m) => {
              if (m.id !== messageId) return m
              const groups = m.reactions.map((g) => ({ ...g, userIds: [...g.userIds] }))
              const mineIdx = groups.findIndex((g) => g.emoji === emoji)
              if (mineIdx >= 0) {
                const group = groups[mineIdx]
                const had = group.userIds.includes(me.id)
                if (had) {
                  group.userIds = group.userIds.filter((id) => id !== me.id)
                } else {
                  group.userIds.push(me.id)
                }
                group.count = group.userIds.length
                if (group.count === 0) groups.splice(mineIdx, 1)
              } else {
                groups.push({ emoji, userIds: [me.id], count: 1 })
              }
              return { ...m, reactions: groups }
            })
          : old,
      )
    },
    onSuccess: (real) => {
      patchMessageViews((old) =>
        old ? old.map((m) => (m.id === real.id ? { ...m, reactions: real.reactions } : m)) : old,
      )
    },
    onError: () => {
      toast.error('Reaction failed — try again')
      queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
    },
  })

  const handleToggleReaction = useCallback(
    (messageId: string, emoji: string) => {
      haptic(12)
      toggleReaction.mutate({ messageId, emoji })
    },
    [toggleReaction],
  )

  const deleteMessage = useMutation({
    mutationFn: async (messageId: string) => {
      return apiJson<DeleteResponse>(`/api/messages/${encodeURIComponent(messageId)}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requesterId: me.id }),
      })
    },
    onMutate: async (messageId) => {
      const previous = queryClient.getQueryData<ChatMessage[]>(['messages', conversationId])
      patchMessageViews((old) =>
        old
          ? old.map((m) => (m.id === messageId ? { ...m, deletedAt: new Date().toISOString() } : m))
          : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      const real = data.message
      patchMessageViews((old) =>
        old ? old.map((m) => (m.id === real.id ? real : m)) : old,
      )
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Message deleted')
    },
    onError: (_error, _messageId, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['messages', conversationId], context.previous)
      }
      // topic views heal via prefix invalidation
      void queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
      toast.error('Could not delete the message')
    },
    onSettled: () => {
      setSelected(null)
      setConfirmingDelete(false)
    },
  })

  // ── edit message (sender only) ───────────────────────────

  const editMessage = useMutation({
    mutationFn: async ({ messageId, content }: { messageId: string; content: string }) => {
      return apiJson<SendResponse>(`/api/messages/${encodeURIComponent(messageId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, content }),
      })
    },
    onMutate: async ({ messageId, content }) => {
      const previous = queryClient.getQueryData<ChatMessage[]>(['messages', conversationId])
      patchMessageViews((old) =>
        old
          ? old.map((m) =>
              m.id === messageId
                ? { ...m, content, editedAt: m.editedAt ?? new Date().toISOString() }
                : m,
            )
          : old,
      )
      return { previous }
    },
    onSuccess: ({ message: real }) => {
      patchMessageViews((old) =>
        old ? old.map((m) => (m.id === real.id ? real : m)) : old,
      )
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Message updated')
      haptic(10)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['messages', conversationId], context.previous)
      }
      // topic views heal via prefix invalidation
      void queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
      toast.error('Could not update the message')
    },
    onSettled: () => {
      setEditing(null)
    },
  })

  // ── pin / unpin (any participant, toggle) ────────────────

  const pinMessage = useMutation({
    mutationFn: async (messageId: string) => {
      return apiJson<SendResponse>(`/api/messages/${encodeURIComponent(messageId)}/pin`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      })
    },
    onMutate: async (messageId) => {
      const now = new Date().toISOString()
      patchMessageViews((old) =>
        old?.map((m) =>
          m.id === messageId
            ? { ...m, pinnedAt: m.pinnedAt ? null : now, pinnedBy: m.pinnedAt ? null : me.id }
            : m,
        ),
      )
    },
    onSuccess: ({ message: real }) => {
      patchMessageViews((old) =>
        old ? old.map((m) => (m.id === real.id ? real : m)) : old,
      )
      queryClient.invalidateQueries({ queryKey: ['pinned', conversationId] })
      toast.success(real.pinnedAt ? 'Message pinned' : 'Message unpinned')
      haptic(12)
    },
    onError: () => {
      toast.error('Could not update the pin')
      queryClient.invalidateQueries({ queryKey: ['messages', conversationId] })
    },
  })

  /** Authoritative pinned list (banner + sheet) — refreshed by pin events. */
  const pinnedQuery = useQuery({
    queryKey: ['pinned', conversationId],
    queryFn: async (): Promise<ChatMessage[]> => {
      const res = await apiJson<{ messages: ChatMessage[] }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/pinned?userId=${encodeURIComponent(me.id)}`,
      )
      return res.messages
    },
    staleTime: 4_000,
    refetchInterval: 15_000,
  })

  /** banner shows the newest pin (list is pinnedAt asc) */
  const pinnedList = pinnedQuery.data ?? []
  const latestPinned = pinnedList.length > 0 ? pinnedList[pinnedList.length - 1] : null
  const pinnedCount = pinnedList.length

  // ── live-poll actions ─────────────────────────────────────

  /** swap a fresh poll-bearing row into every cache it lives in */
  const applyPollRow = useCallback(
    (fresh: ChatMessage) => {
      patchMessageViews((old) =>
        old ? old.map((m) => (m.id === fresh.id ? fresh : m)) : old,
      )
    },
    [patchMessageViews],
  )

  const createPoll = useMutation({
    mutationFn: async ({ question, options }: { question: string; options: string[] }) => {
      return apiJson<SendResponse>(`/api/conversations/${encodeURIComponent(conversationId)}/poll`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ senderId: me.id, question, options }),
      })
    },
    onSuccess: ({ message }) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
        if (!old || old.length === 0) return [message]
        return old.some((m) => m.id === message.id) ? old : [...old, message]
      })
      setPollBuilderOpen(false)
      toast.success('Poll posted — tap an option to vote')
      haptic(12)
      requestAnimationFrame(() => scrollToBottom(true))
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not post the poll')
    },
  })

  const votePoll = useMutation({
    mutationFn: async ({ pollId, optionId }: { pollId: string; optionId: string }) => {
      return apiJson<SendResponse>(`/api/polls/${encodeURIComponent(pollId)}/vote`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id, optionId }),
      })
    },
    onSuccess: ({ message }) => applyPollRow(message),
    onError: () => toast.error('Vote failed — try again'),
  })

  const closePoll = useMutation({
    mutationFn: async (pollId: string) => {
      return apiJson<SendResponse>(`/api/polls/${encodeURIComponent(pollId)}/close`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      })
    },
    onSuccess: ({ message }) => {
      applyPollRow(message)
      toast.success('Voting closed — results are final')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not close the poll')
    },
  })

  const handleVote = useCallback(
    (pollId: string, optionId: string) => {
      haptic(10)
      votePoll.mutate({ pollId, optionId })
    },
    [votePoll],
  )

  // ── saved / starred messages (Telegram-style) ────────────

  const toggleSaved = useMutation({
    mutationFn: async (messageId: string) => {
      return apiJson<{ saved: boolean }>(`/api/messages/${encodeURIComponent(messageId)}/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      })
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['saved', me.id] })
      toast.success(data.saved ? 'Saved to your library' : 'Removed from saved')
      haptic(10)
    },
    onError: () => toast.error('Could not update saved state'),
  })

  // ── R24-b: Chanty-style message → kanban task conversion ──

  const convertToTask = useMutation({
    mutationFn: async (messageId: string) => {
      return apiJson<{ card: { id: string; title: string } }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/kanban`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, messageId }),
        },
      )
    },
    onSuccess: (data) => {
      toast.success('Task created from message')
      fireParticles({ kind: 'burst', count: 40 })
      haptic(12)
      // refresh the board if the kanban sheet has ever cached it
      void queryClient.invalidateQueries({ queryKey: ['kanban', conversationId] })
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not create the task')
    },
    onSettled: () => setSelected(null),
  })

  // ── view-once consumption ────────────────────────────────

  const consumeViewOnce = useMutation({
    mutationFn: async (messageId: string) => {
      return apiJson<SendResponse>(`/api/messages/${encodeURIComponent(messageId)}/viewed`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      })
    },
    onSuccess: ({ message }) => applyPollRow(message),
    onError: () => toast.error('Could not open this photo'),
  })

  /** open lightbox (consuming a view-once gate when needed) */
  const openImageGated = useCallback(
    (message: ChatMessage) => {
      if (!message.imagePath) return
      const src = `/api/uploads/${encodeURIComponent(message.imagePath)}`
      if (message.viewOnce && message.senderId !== me.id && message.viewedAt === null) {
        consumeViewOnce.mutate(message.id)
      }
      setLightboxSrc(src)
    },
    [consumeViewOnce, me.id],
  )

  // ── disappearing messages TTL ────────────────────────────

  const setTtl = useMutation({
    mutationFn: async (ttlSeconds: number) => {
      return apiJson<{ conversation: ConversationDetail }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/disappearing`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, ttlSeconds }),
        },
      )
    },
    onSuccess: (data) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], data.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      setMenuOpen(false)
      setTtlChoicesOpen(false)
      const t = data.conversation.ttlSeconds
      toast.success(
        t === 0 ? 'Disappearing messages off' : `New messages vanish after ${t === 86400 ? '24 hours' : t === 604800 ? '7 days' : '30 days'}`,
      )
      haptic(12)
    },
    onError: () => toast.error('Could not update disappearing messages'),
  })

  // ── R34-b: AI recap (Zoom AI-Companion parity) ───────────
  /** live recap card content — null = no card; auto-dismisses after 15s */
  const [recap, setRecap] = useState<{ text: string; basedOn: number } | null>(null)
  const recapMutation = useMutation({
    mutationFn: async () =>
      apiJson<{ recap: string; basedOn: number; cached: boolean }>('/api/ai/recap', {
        method: 'POST',
        body: JSON.stringify({ userId: me.id, conversationId }),
      }),
    onSuccess: (data) => {
      setRecap({ text: data.recap, basedOn: data.basedOn })
      haptic(12)
    },
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'Recap is unavailable right now'),
  })
  /** Gate: recap is for groups or DMs with real activity (>= 5 messages). */
  const requestRecap = useCallback(() => {
    const liveCount = (messages.data ?? []).filter((m) => m.deletedAt === null).length
    if (liveCount < 5) {
      toast.info('Recap needs at least 5 messages in this chat')
      return
    }
    recapMutation.mutate()
  }, [messages.data, recapMutation.mutate])
  // auto-dismiss the card so it never outstays its welcome
  useEffect(() => {
    if (recap === null) return
    const timer = setTimeout(() => setRecap(null), 15_000)
    return () => clearTimeout(timer)
  }, [recap])
  // a recap belongs to the room it was asked in
  useEffect(() => {
    setRecap(null)
  }, [conversationId])

  // ── scheduled sends ──────────────────────────────────────

  const scheduledQuery = useQuery({
    queryKey: ['scheduled', conversationId, me.id],
    queryFn: async (): Promise<ScheduledItem[]> => {
      const res = await apiJson<{ items: ScheduledItem[] }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/scheduled?userId=${encodeURIComponent(me.id)}`,
      )
      return res.items
    },
    enabled: scheduleFor !== null || scheduledListOpen,
  })

  const scheduleSend = useMutation({
    mutationFn: async ({ content, whenIso }: { content: string; whenIso: string }) => {
      return apiJson<{ item: ScheduledItem }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/scheduled`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ senderId: me.id, content, scheduledAt: whenIso }),
        },
      )
    },
    onSuccess: (data) => {
      setInput('')
      pulseDraftsStore.getState().clearDraft(conversationId)
      requestAnimationFrame(autosize)
      setScheduleFor(null)
      void scheduledQuery.refetch()
      toast.success(`Scheduled for ${formatListStamp(data.item.scheduledAt)} — it sends itself`)
      haptic(12)
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not schedule the message')
    },
  })

  const cancelScheduled = useMutation({
    mutationFn: async (id: string) => {
      return apiJson<{ ok: boolean }>(`/api/scheduled/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requesterId: me.id }),
      })
    },
    onSuccess: () => {
      void scheduledQuery.refetch()
      toast.success('Scheduled message cancelled')
    },
    onError: () => toast.error('Could not cancel'),
  })

  /** viewer's saved library (drives Save/Unsave label + profile screen) */
  const savedQuery = useQuery({
    queryKey: ['saved', me.id],
    queryFn: async (): Promise<SavedItem[]> => {
      const res = await apiJson<{ items: SavedItem[] }>(
        `/api/users/${encodeURIComponent(me.id)}/saved`,
      )
      return res.items
    },
    staleTime: 20_000,
  })
  const isSavedIds = useMemo(
    () =>
      new Set(
        (savedQuery.data ?? [])
          .filter((item) => item.message.conversationId === conversationId)
          .map((item) => item.message.id),
      ),
    [savedQuery.data, conversationId],
  )

  /** viewer's group role → announcement-mode lockout */
  const myRole = detailData?.members.find((m) => m.id === me.id)?.role ?? 'member'
  const broadcastLocked = isGroup && (detailData?.broadcastMode ?? false) && myRole !== 'admin'

  // ── R23: red packets + kanban + events — palette events open them ──
  const redPacket = useRedPacketSheet(conversationId, me.id)
  const sheetMembers = useMemo(
    () => (detailData?.members ?? []).map((m) => ({ id: m.id, name: m.name, color: m.color })),
    [detailData],
  )
  const kanban = useKanbanSheet(conversationId, me.id, sheetMembers, myRole)
  const events = useEventsSheet(conversationId, me.id, sheetMembers, myRole)

  // ── R30-b: per-message reminders — due-loop toasts, badge count, create ──
  useReminderDueLoop(me.id)
  const remindersQuery = useQuery({
    queryKey: remindersKey(me.id),
    queryFn: () => fetchReminders(me.id),
    staleTime: 15_000,
  })
  const upcomingReminderCount = (remindersQuery.data ?? []).filter(
    (item) => item.firedAt === null,
  ).length

  const createReminder = useCallback(
    async (messageId: string | null, remindAt: Date, note = ''): Promise<boolean> => {
      try {
        await apiJson<{ item: ReminderItem }>('/api/reminders', {
          method: 'POST',
          body: JSON.stringify({
            userId: me.id,
            conversationId,
            ...(messageId ? { messageId } : {}),
            note,
            remindAt: remindAt.toISOString(),
          }),
        })
        toast.success('Reminder set')
        haptic(12)
        await queryClient.invalidateQueries({ queryKey: remindersKey(me.id) })
        return true
      } catch (error) {
        toast.error(error instanceof Error ? error.message : 'Could not set the reminder')
        return false
      }
    },
    [conversationId, me.id, queryClient],
  )

  // ── R24-b: Zulip-style topic rail data (groups only) ─────────
  const topicsQuery = useQuery({
    queryKey: ['topics', conversationId],
    queryFn: async (): Promise<TopicSummary[]> => {
      const res = await apiJson<{ topics: TopicSummary[] }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/topics?userId=${encodeURIComponent(me.id)}`,
      )
      return res.topics
    },
    enabled: isGroup,
    staleTime: 10_000,
    // keeps chip count badges honest without a socket event per topic
    refetchInterval: 15_000,
  })
  const topics = topicsQuery.data ?? []
  const activeTopic =
    activeTopicId !== null ? (topics.find((t) => t.id === activeTopicId) ?? null) : null

  const createTopic = useCallback(
    async (name: string, emoji: string): Promise<string | null> => {
      try {
        const res = await apiJson<{ topic: TopicSummary }>(
          `/api/conversations/${encodeURIComponent(conversationId)}/topics`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: me.id, name, emoji }),
          },
        )
        await queryClient.invalidateQueries({ queryKey: ['topics', conversationId] })
        setActiveTopicId(res.topic.id)
        toast.success(`Filing to ${res.topic.emoji} ${res.topic.name} — next send lands there`)
        window.dispatchEvent(new CustomEvent(TOPIC_CREATED_EVENT, { detail: res.topic }))
        haptic(12)
        return res.topic.id
      } catch (error) {
        toast.error(error instanceof Error ? error.message : 'Could not create the topic')
        return null
      }
    },
    [conversationId, me.id, queryClient],
  )

  const selectTopic = useCallback((topicId: string | null) => {
    setActiveTopicId(topicId)
  }, [])

  // self-heal: the active topic was deleted (creator/admin) → back to General
  useEffect(() => {
    if (!isGroup || activeTopicId === null) return
    if (topicsQuery.isSuccess && !topics.some((t) => t.id === activeTopicId)) {
      setActiveTopicId(null)
    }
  }, [isGroup, topicsQuery.isSuccess, topics, activeTopicId])

  // ── R24: stage / space / tournament sheets (parallel crews' hooks) ──
  const stage = useStageSheet(conversationId, me)
  const space = useSpaceSheet(conversationId, me)
  const tournament = useTournamentSheet(conversationId, me)

  /** amber chip above the composer while delayed sends are pending */
  const scheduledChip = useMemo(() => {
    const items = scheduledQuery.data ?? []
    if (items.length === 0) return null
    return { count: items.length, next: formatListStamp(items[0].scheduledAt) }
  }, [scheduledQuery.data])

  // ── notification mute (per-user watermark) ─────────────

  const isRoomMuted =
    detailData != null &&
    detailData.myMutedUntil !== null &&
    Date.parse(detailData.myMutedUntil) > Date.now()

  const toggleRoomMute = useMutation({
    mutationFn: async (until: '8h' | '1w' | 'always' | null) => {
      return apiJson<{ ok: boolean; mutedUntil: string | null }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/mute`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, until }),
        },
      )
    },
    onSuccess: (data) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, myMutedUntil: data.mutedUntil } : old,
      )
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      setMenuOpen(false)
      setMuteChoicesOpen(false)
      toast.success(
        data.mutedUntil === null
          ? 'Notifications unmuted'
          : Date.parse(data.mutedUntil) - Date.now() > 20 * 365 * 24 * 3600 * 1000
            ? 'Muted — always'
            : `Muted until ${formatListStamp(data.mutedUntil)}`,
      )
    },
    onError: () => {
      toast.error('Could not update the mute')
    },
  })

  // ── composer behaviour ─────────────────────────────────────

  const autosize = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    // attachments tray open → clamp to a single line so the tray stays the star
    const cap = trayOpenRef.current ? 48 : 120
    el.style.height = `${Math.min(el.scrollHeight, cap)}px`
  }, [])

  /** tray toggle — collapses the textarea to one line while the tray is open */
  const setTray = useCallback(
    (open: boolean) => {
      trayOpenRef.current = open
      setTrayOpen(open)
      if (open) {
        // the tray overlays the slash palette — dismiss it until the draft changes
        setSlashDismissed(true)
        setTrayEffectsOpen(false)
      }
      requestAnimationFrame(autosize)
    },
    [autosize],
  )

  // R34-b: Esc closes the attachments tray (window capture, mirroring the palette)
  useEffect(() => {
    if (!trayOpen) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        setTray(false)
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [trayOpen, setTray])

  // ── R34-b: progressive-disclosure attachments tray ────────
  // Discord rule: the FREQUENT actions (photo, sticker, emoji, mic/voice)
  // stay on the composer bar; everything else lives here, GROUPED by
  // purpose behind the '+' toggle. No capability was removed — regrouped.
  // (Folders live in the Chats tab, not in a room's composer, so there is
  // no Folder tile; 'Topic' is groups-only by nature.)
  type TrayTile = {
    label: string
    help: string
    icon: LucideIcon
    tone: string
    disabled: boolean
    /** true → only meaningful in group conversations */
    groupOnly?: boolean
    run: () => void
  }
  const trayGroups: { title: string; tiles: TrayTile[] }[] = (
    [
      {
        title: 'Create',
        tiles: [
          {
            label: 'Poll',
            help: 'Live votes in this chat',
            icon: Vote,
            tone: 'bg-violet-500/10 text-violet-600 dark:text-violet-400',
            disabled: broadcastLocked,
            run: () => {
              setTray(false)
              setPollBuilderOpen(true)
            },
          },
          {
            label: 'Schedule',
            help: 'Send this message later',
            icon: CalendarClock,
            tone: 'bg-orange-500/10 text-orange-600 dark:text-orange-400',
            disabled: broadcastLocked,
            run: () => {
              const draft = input.trim()
              if (draft.length === 0 && scheduleFor === null) {
                toast.info('Type the message first, then schedule it')
                return
              }
              setTray(false)
              setScheduleFor(draft)
            },
          },
          {
            label: 'Whiteboard',
            help: 'Sketch together on one canvas',
            icon: Presentation,
            tone: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
            disabled: false,
            run: () => {
              setTray(false)
              whiteboard.setOpen(true)
            },
          },
          {
            label: 'Red packet',
            help: 'Wrap coins as a gift',
            icon: Gift,
            tone: 'bg-rose-500/10 text-rose-600 dark:text-rose-400',
            disabled: broadcastLocked,
            run: () => {
              setTray(false)
              redPacket.setOpen(true)
            },
          },
        ],
      },
      {
        title: 'Gather',
        tiles: [
          {
            label: 'Events',
            help: 'Plan meetups with RSVP',
            icon: CalendarDays,
            tone: 'bg-amber-500/10 text-amber-600 dark:text-amber-400',
            disabled: false,
            run: () => {
              setTray(false)
              events.setOpen(true)
            },
          },
          {
            label: 'Stage',
            help: 'Live audio stage for the room',
            icon: Podcast,
            tone: 'bg-teal-500/10 text-teal-600 dark:text-teal-400',
            disabled: false,
            run: () => {
              setTray(false)
              window.dispatchEvent(new CustomEvent(STAGE_OPEN_EVENT))
            },
          },
          {
            label: 'Space',
            help: 'Hang out in a spatial room',
            icon: MapIcon,
            tone: 'bg-amber-500/10 text-amber-600 dark:text-amber-400',
            disabled: false,
            run: () => {
              setTray(false)
              window.dispatchEvent(new CustomEvent(SPACE_OPEN_EVENT))
            },
          },
          {
            label: 'Game',
            help: 'Start tic-tac-toe here',
            icon: Gamepad2,
            tone: 'bg-violet-500/10 text-violet-600 dark:text-violet-400',
            disabled: false,
            run: () => {
              setTray(false)
              window.dispatchEvent(new CustomEvent(NEW_GAME_EVENT))
            },
          },
          {
            label: 'Tournament',
            help: 'Bracketed group competition',
            icon: Trophy,
            tone: 'bg-rose-500/10 text-rose-600 dark:text-rose-400',
            disabled: !isGroup,
            run: () => {
              setTray(false)
              if (!isGroup) {
                toast.error('Tournaments are for groups only')
                return
              }
              window.dispatchEvent(new CustomEvent(TOURNAMENT_OPEN_EVENT))
            },
          },
        ],
      },
      {
        title: 'Organise',
        tiles: [
          {
            label: 'Kanban',
            help: 'Group tasks on a board',
            icon: SquareKanban,
            tone: 'bg-teal-500/10 text-teal-600 dark:text-teal-400',
            disabled: false,
            run: () => {
              setTray(false)
              kanban.setOpen(true)
            },
          },
          {
            label: 'Topic',
            help: 'File the chat under a topic',
            icon: MessagesSquare,
            tone: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
            disabled: false,
            groupOnly: true,
            run: () => {
              setTray(false)
              // stage the /topic draft — the palette + parser take it from here
              setInput('/topic ')
              setSlashDismissed(false)
              requestAnimationFrame(() => {
                autosize()
                textareaRef.current?.focus()
              })
            },
          },
        ],
      },
      {
        title: 'Express',
        tiles: [
          {
            label: 'Effects',
            help: 'Confetti, lasers, echo, sparkles',
            icon: Sparkles,
            tone: 'bg-violet-500/10 text-violet-600 dark:text-violet-400',
            disabled: broadcastLocked,
            run: () => setTrayEffectsOpen((v) => !v),
          },
          {
            label: 'Commands',
            help: 'Every slash command',
            icon: Dices,
            tone: 'bg-teal-500/10 text-teal-600 dark:text-teal-400',
            disabled: false,
            run: () => {
              setTray(false)
              setHelpOpen(true)
            },
          },
          {
            label: 'Location',
            help: 'Drop a live map pin',
            icon: MapPin,
            tone: 'bg-teal-500/10 text-teal-600 dark:text-teal-400',
            disabled: broadcastLocked,
            run: () => {
              setTray(false)
              setLocationOpen(true)
            },
          },
          {
            label: 'Incognito',
            help: anonNext ? 'Armed — next send is anonymous' : 'Next send hides your name',
            icon: VenetianMask,
            tone: anonNext
              ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
              : 'bg-zinc-500/10 text-zinc-500 dark:text-zinc-400',
            disabled: false,
            groupOnly: true,
            run: () => {
              haptic(8)
              const next = !anonNextRef.current
              anonNextRef.current = next
              setAnonNext(next)
              if (next) setTray(false)
            },
          },
        ],
      },
    ] as { title: string; tiles: TrayTile[] }[]
  )
    .map((group) => ({
      ...group,
      tiles: group.tiles.filter((tile) => !tile.groupOnly || isGroup),
    }))
    .filter((group) => group.tiles.length > 0)

  const stopTyping = useCallback(() => {
    if (recipients.length > 0) {
      realtime.cancelTyping(conversationId, { viewerId: me.id, recipients })
    }
  }, [realtime, conversationId, me.id, recipients])

  useEffect(() => () => stopTyping(), [stopTyping])

  // ── in-room switches + profile wiring ─────────────────────

  /** Swap this mounted room onto another conversation (profile → "Message"). */
  const switchRoom = useCallback(
    (nextId: string) => {
      if (nextId === (switchedId ?? conversationIdProp)) return
      if (recorderRef.current) {
        toast.info('Finish or cancel the voice note first')
        return
      }
      stopTyping()
      haptic(10)
      setTray(false)
      setSwitchedId(nextId)
      setInput(pulseDraftsStore.getState().drafts[nextId] ?? '')
      setReplyTo(null)
      setEditing(null)
      setSelected(null)
      setPendingImage(null)
      setCaptionDraft('')
      setPendingEffect(null)
      setMenuOpen(false)
      setHighlight(null)
      setThreadRoot(null)
      setActiveTopicId(null)
      setAnonNext(false)
      anonNextRef.current = false
      setHistoryLoaded(false)
      setAnchorOverride(null)
      setJumpOverride(null)
      requestAnimationFrame(autosize)
    },
    [switchedId, conversationIdProp, stopTyping, autosize, setTray],
  )

  /** Profile sheet → "Message" → open (or create) the 1:1 DM right here. */
  const openDmWith = useCallback(
    async (userId: string) => {
      try {
        const res = await apiJson<{ conversation: ConversationSummary }>(
          '/api/conversations',
          jsonBody({ creatorId: me.id, memberIds: [userId] }),
        )
        await queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
        switchRoom(res.conversation.id)
      } catch (error) {
        toast.error(error instanceof Error ? error.message : 'Could not open the chat')
      }
    },
    [me.id, queryClient, switchRoom],
  )

  /** Sender/member avatar tap → the GLOBAL profile page (R27-a owns #/user/:id). */
  const openProfileForAuthor = useCallback((sender: MessageAuthor) => {
    navigateHash(`#/user/${encodeURIComponent(sender.id)}`)
  }, [])

  /** Member row tap → global profile page; closes any open dialog first. */
  const openProfileForUser = useCallback((user: AppUser) => {
    setInfoOpen(false)
    navigateHash(`#/user/${encodeURIComponent(user.id)}`)
  }, [])

  // ── @mention autocomplete (composer) ────────────────────

  /** caret position inside the textarea (tracked on every change) */
  const [mentionCaret, setMentionCaret] = useState(0)
  /** active `@token` immediately before the caret, else null */
  const mentionToken = useMemo(() => {
    const upto = input.slice(0, mentionCaret)
    const m = /(?:^|\s)@([^@\s]*)$/.exec(upto)
    return m ? { token: m[1], start: mentionCaret - m[1].length - 1 } : null
  }, [input, mentionCaret])
  const mentionMatches = useMemo(() => {
    if (mentionToken === null) return []
    const q = mentionToken.token.toLowerCase()
    return (detailData?.members ?? [])
      .filter((m) => m.name.toLowerCase().startsWith(q))
      .slice(0, 5)
  }, [mentionToken, detailData])

  const pickMention = useCallback(
    (member: { name: string }) => {
      if (mentionToken === null) return
      const before = input.slice(0, mentionToken.start)
      const after = input.slice(mentionCaret)
      const inserted = `@${member.name} `
      const next = before + inserted + after
      setInput(next)
      const caret = before.length + inserted.length
      setMentionCaret(caret)
      requestAnimationFrame(() => {
        autosize()
        const el = textareaRef.current
        if (el) {
          el.focus()
          el.setSelectionRange(caret, caret)
        }
      })
    },
    [input, mentionToken, mentionCaret, autosize],
  )

  // stable member-name list for mention chips in bubbles
  const memberNamesKey = (detailData?.members ?? []).map((m) => m.name).join('\u0000')
  const memberNames = useMemo(() => memberNamesKey.split('\u0000'), [memberNamesKey])

  // ── edit mode helpers ────────────────────────────────────

  const startEdit = useCallback(
    (message: ChatMessage) => {
      setSelected(null)
      setReplyTo(null)
      setEditing(message)
      setInput(message.content)
      requestAnimationFrame(() => {
        autosize()
        const el = textareaRef.current
        if (el) {
          el.focus()
          el.setSelectionRange(el.value.length, el.value.length)
        }
      })
    },
    [autosize],
  )

  const cancelEdit = useCallback(() => {
    setEditing(null)
    setInput('')
    pulseDraftsStore.getState().clearDraft(conversationId)
    requestAnimationFrame(autosize)
  }, [autosize, conversationId])

  const submit = useCallback(() => {
    const raw = input.trim()
    if (raw.length === 0) return
    setTray(false)

    // Telegram-style edit mode → PATCH instead of send
    if (editing) {
      if (editMessage.isPending) return
      if (raw !== editing.content) {
        editMessage.mutate({ messageId: editing.id, content: raw })
      } else {
        setEditing(null)
      }
      setInput('')
      pulseDraftsStore.getState().clearDraft(conversationId)
      requestAnimationFrame(autosize)
      return
    }

    // Discord/Twitch-flavored slash commands — parsed BEFORE any network call.
    // Everything they produce is real message content / a real sheet open.
    let transformed = ''
    if (raw.startsWith('/')) {
      const outcome = applySlash(raw)
      if (outcome.kind === 'error') {
        toast.error(outcome.message)
        return
      }
      if (outcome.kind === 'help') {
        setHelpOpen(true)
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        return
      }
      if (outcome.kind === 'topic') {
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        void createTopic(outcome.name, '💬')
        return
      }
      if (outcome.kind === 'tool') {
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        if (outcome.tool === 'whiteboard') whiteboard.setOpen(true)
        else if (outcome.tool === 'redpacket') redPacket.setOpen(true)
        else if (outcome.tool === 'kanban') kanban.setOpen(true)
        else if (outcome.tool === 'events') events.setOpen(true)
        else if (outcome.tool === 'stage') window.dispatchEvent(new CustomEvent(STAGE_OPEN_EVENT))
        else if (outcome.tool === 'space') window.dispatchEvent(new CustomEvent(SPACE_OPEN_EVENT))
        else if (outcome.tool === 'tournament') {
          if (!isGroup) {
            toast.error('Tournaments are for groups only')
          } else {
            window.dispatchEvent(new CustomEvent(TOURNAMENT_OPEN_EVENT))
          }
        }
        else window.dispatchEvent(new CustomEvent(NEW_GAME_EVENT))
        return
      }
      if (outcome.kind === 'poll') {
        setPollBuilderOpen(true)
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        return
      }
      if (outcome.kind === 'schedule') {
        setScheduleFor(null)
        setScheduledListOpen(true)
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        return
      }
      if (outcome.kind === 'remind') {
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        const parsed = parseRelativeReminder(outcome.arg)
        if (!parsed) {
          toast.error('Usage: /remind buy milk in 30m — try 30m, 2h, tomorrow, tonight, next week')
          return
        }
        void createReminder(null, parsed.remindAt, parsed.note)
        return
      }
      if (outcome.kind === 'sticker') {
        setStickerOpen(true)
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        return
      }
      if (outcome.kind === 'location') {
        setLocationOpen(true)
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        return
      }
      if (outcome.kind === 'effect') {
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
        requestAnimationFrame(autosize)
        if (outcome.content.length > 0) {
          if (sendMessage.isPending || isOffline) {
            if (isOffline) toast.error('Effects need a connection — try again when online')
            return
          }
          stopTyping()
          sendMessage.mutate({
            clientId: uid(),
            content: outcome.content,
            kind: 'text',
            payload: { effect: outcome.effect },
            ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
          })
        } else {
          setPendingEffect(outcome.effect)
          toast(`${outcome.effect} effect armed — type a message and send`)
          requestAnimationFrame(() => textareaRef.current?.focus())
        }
        return
      }
      transformed = outcome.content.trim()
      if (transformed.length === 0) return
    }

    const content = transformed.length > 0 ? transformed : input.trim()
    if (content.length === 0) return

    if (sendMessage.isPending) return
    stopTyping()
    setInput('')
    setSlashDismissed(false)
    pulseDraftsStore.getState().clearDraft(conversationId)
    requestAnimationFrame(autosize)

    const clientId = uid()
    const replyTarget = replyTo && !replyTo.deletedAt ? replyTo : null
    const armedEffect = pendingEffect
    if (armedEffect !== null) setPendingEffect(null)

    // Offline → hold in the persisted outbox; the realtime provider
    // flushes it (FIFO) as soon as connectivity returns.
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      // the outbox is text-only by design — effects never survive the queue
      const queuedTemp: ChatMessage = {
        id: `temp-${clientId}`,
        conversationId,
        senderId: me.id,
        content,
        deletedAt: null,
        createdAt: new Date().toISOString(),
        kind: 'text',
        payload: null,
        sender: { id: me.id, name: me.name, username: me.username, color: me.color, avatar: me.avatar },
        reactions: [],
        replyTo: replyTarget
          ? {
              id: replyTarget.id,
              content: replyTarget.content,
              senderName: replyTarget.sender.name,
              deleted: false,
            }
          : null,
        imagePath: null,
        audioPath: null,
        durationMs: null,
        editedAt: null,
        pinnedAt: null,
        pinnedBy: null,
        parentId: null,
        topicId: null,
        anon: false,
        anonAlias: null,
        viewOnce: false,
        viewedAt: null,
        viewedBy: null,
        expiresAt: null,
        linkUrl: null,
        linkPreview: null,
        poll: null,
        translations: [],
        _queued: true,
      }
      pulseOutboxStore.getState().enqueue({
        clientId,
        conversationId,
        content,
        ...(replyTarget ? { replyToId: replyTarget.id } : {}),
        sender: { id: me.id, name: me.name, username: me.username, color: me.color, avatar: me.avatar },
        replySnapshot: queuedTemp.replyTo,
        queuedAt: queuedTemp.createdAt,
      })
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? [...old, queuedTemp] : [queuedTemp],
      )
      toast('Queued — sends when you’re back online')
      haptic(10)
      return
    }

    sendMessage.mutate({
      clientId,
      content,
      ...(replyTarget ? { replyToId: replyTarget.id } : {}),
      ...(armedEffect !== null ? { kind: 'text', payload: { effect: armedEffect } } : {}),
    })
  }, [input, editing, editMessage, sendMessage, stopTyping, autosize, replyTo, conversationId, me, queryClient, pendingEffect, isOffline, setTray])

  /** Thread drawer composer — replies land under the root, never the main flow. */
  const submitThreadReply = useCallback(
    (text: string) => {
      const content = text.trim()
      if (content.length === 0 || !threadRoot || sendMessage.isPending) return
      haptic(8)
      setThreadDraft('')
      sendMessage.mutate({ clientId: uid(), content, parentId: threadRoot.id })
    },
    [threadRoot, threadDraft, sendMessage],
  )

  // ── sticker / location senders (R19-b) ─────────────────────

  /** Sticker tile tap → real kind:'sticker' message. */
  const sendSticker = useCallback(
    (pick: StickerPick) => {
      if (sendMessage.isPending || isOffline) {
        if (isOffline) toast.error('Stickers need a connection')
        return
      }
      sendMessage.mutate({
        clientId: uid(),
        content: '',
        kind: 'sticker',
        payload: { emoji: pick.emoji, pack: pick.pack },
        ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
      })
    },
    [sendMessage, replyTo, isOffline],
  )

  /** Confirm-sheet → real kind:'location' message. */
  const sendLocation = useCallback(
    (payload: LocationPayload) => {
      setLocationOpen(false)
      if (sendMessage.isPending || isOffline) {
        if (isOffline) toast.error('Location sharing needs a connection')
        return
      }
      haptic(12)
      sendMessage.mutate({
        clientId: uid(),
        content: '',
        kind: 'location',
        payload: { lat: payload.lat, lng: payload.lng, label: payload.label },
        ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
      })
    },
    [sendMessage, replyTo, isOffline],
  )

  /** Slash-palette row activated (tap / Enter) — fast-path over applySlash. */
  const runPaletteCommand = useCallback(
    (cmd: string) => {
      haptic(8)
      setSlashDismissed(true)
      const clearDraft = () => {
        setInput('')
        pulseDraftsStore.getState().clearDraft(conversationId)
      }
      if (cmd === '/sticker') {
        clearDraft()
        setStickerOpen(true)
        return
      }
      if (cmd === '/location') {
        clearDraft()
        setLocationOpen(true)
        return
      }
      if (cmd === '/poll') {
        clearDraft()
        setPollBuilderOpen(true)
        return
      }
      if (cmd === '/schedule') {
        clearDraft()
        setScheduleFor(null)
        setScheduledListOpen(true)
        return
      }
      if (cmd === '/help') {
        clearDraft()
        setHelpOpen(true)
        return
      }
      // R34-b: AI recap — same trigger as the header overflow entry
      if (cmd === '/recap') {
        clearDraft()
        requestRecap()
        return
      }
      // ── R23: standalone tools — clear the draft, then open/dispatch ──
      if (cmd === '/redpacket') {
        clearDraft()
        redPacket.setOpen(true)
        return
      }
      if (cmd === '/kanban') {
        clearDraft()
        kanban.setOpen(true)
        return
      }
      if (cmd === '/events') {
        clearDraft()
        events.setOpen(true)
        return
      }
      if (cmd === '/game') {
        clearDraft()
        window.dispatchEvent(new CustomEvent(NEW_GAME_EVENT))
        return
      }
      // ── R24-b: stage / space / tournament dispatch straight to the ──
      // parallel crews' hooks; /topic falls through to the staging tail so
      // typed args survive and applySlash creates the topic on Enter.
      if (cmd === '/stage') {
        clearDraft()
        window.dispatchEvent(new CustomEvent(STAGE_OPEN_EVENT))
        return
      }
      if (cmd === '/space') {
        clearDraft()
        window.dispatchEvent(new CustomEvent(SPACE_OPEN_EVENT))
        return
      }
      if (cmd === '/tournament') {
        clearDraft()
        if (!isGroup) {
          toast.error('Tournaments are for groups only')
        } else {
          window.dispatchEvent(new CustomEvent(TOURNAMENT_OPEN_EVENT))
        }
        return
      }
      if (cmd.startsWith('/effects')) {
        const effect = cmd.split(/\s+/)[1]
        clearDraft()
        if (isMessageEffect(effect)) {
          setPendingEffect(effect)
          toast(`${effect} effect armed — type a message and send`)
        }
        requestAnimationFrame(() => textareaRef.current?.focus())
        return
      }
      // text-transform commands: stage the command, keep any typed args
      setInput((prev) => {
        const args = prev.replace(/^\/\S*\s*/, '').trim()
        return args.length > 0 ? `${cmd} ${args}` : `${cmd} `
      })
      requestAnimationFrame(() => {
        autosize()
        textareaRef.current?.focus()
      })
    },
    [conversationId, autosize, isGroup, redPacket, kanban, events, requestRecap],
  )


  const handleInputChange = (value: string) => {
    setInput(value)
    setSlashDismissed(false)
    setMentionCaret(textareaRef.current?.selectionStart ?? value.length)
    autosize()
    if (draftTimerRef.current !== null) clearTimeout(draftTimerRef.current)
    if (value.trim().length > 0 && recipients.length > 0) {
      realtime.signalTyping(conversationId, {
        viewerId: me.id,
        userName: me.name,
        recipients,
      })
    } else {
      stopTyping()
    }
    // persist the draft — but never resurrect one the user just cleared/sent
    draftTimerRef.current = setTimeout(() => {
      draftTimerRef.current = null
      const live = textareaRef.current?.value ?? ''
      if (live !== value) return
      pulseDraftsStore.getState().setDraft(conversationId, live)
    }, 300)
  }

  /** Pick → compress → upload → open the caption sheet (send from there). */
  const handleImagePicked = async (file: File | undefined) => {
    if (!file || sendingImage) return
    setSendingImage(true)
    try {
      const dataUrl = await compressImageToDataUrl(file)
      const up = await apiJson<{ imagePath: string }>('/api/uploads', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dataUrl }),
      })
      haptic(12)
      setCaptionDraft('')
      setPendingImage({ imagePath: up.imagePath, preview: dataUrl })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not send the image')
    } finally {
      setSendingImage(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  /** Send the staged image with its (optional) caption. */
  const sendCaptionedImage = useCallback(() => {
    if (pendingImage === null || sendMessage.isPending) return
    const caption = captionDraft.trim().slice(0, 500)
    stopTyping()
    const imagePath = pendingImage.imagePath
    setPendingImage(null)
    setCaptionDraft('')
    sendMessage.mutate({
      clientId: uid(),
      content: caption,
      imagePath,
      ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
    })
  }, [pendingImage, captionDraft, sendMessage, stopTyping, replyTo])

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Escape' && editing) {
      event.preventDefault()
      cancelEdit()
      return
    }
    if (
      event.key === 'Enter' &&
      !event.shiftKey &&
      typeof window !== 'undefined' &&
      window.matchMedia('(min-width: 640px)').matches
    ) {
      // mention popup open → Enter picks the highlighted member first
      if (mentionMatches.length > 0 && mentionToken !== null) {
        event.preventDefault()
        pickMention(mentionMatches[0])
        return
      }
      event.preventDefault()
      submit()
    }
  }

  // ── voice notes ────────────────────────────────────────────

  const teardownRecorder = useCallback(() => {
    if (recordTimerRef.current !== null) {
      clearInterval(recordTimerRef.current)
      recordTimerRef.current = null
    }
    recorderRef.current = null
    setRecording(false)
    setRecordMs(0)
  }, [])

  /** stop = false → cancel (discard); stop = true → upload + send. */
  const finishRecording = useCallback(
    (send: boolean) => {
      const rec = recorderRef.current
      if (!rec) return
      recordCancelRef.current = !send
      try {
        rec.stop()
      } catch {
        teardownRecorder()
      }
    },
    [teardownRecorder],
  )

  const startRecording = useCallback(async () => {
    if (recorderRef.current || sendingVoice) return
    setTray(false)
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      toast.error('Voice notes are not supported in this browser')
      return
    }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      toast.error('Microphone access was denied — check browser permissions')
      return
    }
    const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
    const mimeType = candidates.find((t) => MediaRecorder.isTypeSupported(t))
    const rec = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)
    recordChunksRef.current = []
    recordCancelRef.current = false
    rec.ondataavailable = (event) => {
      if (event.data.size > 0) recordChunksRef.current.push(event.data)
    }
    rec.onstop = async () => {
      stream.getTracks().forEach((track) => track.stop())
      const elapsed = Date.now() - recordStartedAtRef.current
      const cancelled = recordCancelRef.current
      const chunks = recordChunksRef.current
      const type = rec.mimeType || 'audio/webm'
      teardownRecorder()
      if (cancelled || elapsed < MIN_VOICE_MS || chunks.length === 0) {
        if (!cancelled && elapsed < MIN_VOICE_MS) toast.info('Hold too short — voice note discarded')
        return
      }
      setSendingVoice(true)
      try {
        const buffer = await new Blob(chunks, { type }).arrayBuffer()
        const bytes = new Uint8Array(buffer)
        const CHUNK = 0x8000
        let binary = ''
        for (let i = 0; i < bytes.length; i += CHUNK) {
          binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
        }
        const up = await apiJson<{ filePath: string }>('/api/uploads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ dataUrl: `data:${type};base64,${btoa(binary)}` }),
        })
        haptic(12)
        sendMessage.mutate({
          clientId: uid(),
          content: '',
          audioPath: up.filePath,
          durationMs: Math.max(1, Math.round(elapsed / 100) * 100),
          ...(replyTo && !replyTo.deletedAt ? { replyToId: replyTo.id } : {}),
        })
      } catch (error) {
        toast.error(error instanceof Error ? error.message : 'Could not send the voice note')
      } finally {
        setSendingVoice(false)
      }
    }
    recorderRef.current = rec
    recordStartedAtRef.current = Date.now()
    setRecording(true)
    setRecordMs(0)
    rec.start(250)
    recordTimerRef.current = setInterval(() => {
      setRecordMs(Date.now() - recordStartedAtRef.current)
    }, 200)
    haptic(14)
  }, [sendMessage, replyTo, sendingVoice, teardownRecorder, setTray])

  // safety: leaving the room (or tab) mid-recording discards the note
  useEffect(
    () => () => {
      const rec = recorderRef.current
      if (rec) {
        recordCancelRef.current = true
        try {
          rec.stop()
        } catch {
          // already stopped
        }
      }
    },
    [],
  )

  // ── group management ───────────────────────────────────────

  const renameGroup = useMutation({
    mutationFn: async (name: string) => {
      return apiJson<DetailResponse>(`/api/conversations/${encodeURIComponent(conversationId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requesterId: me.id, name }),
      })
    },
    onSuccess: (res) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], res.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Group name updated')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not rename the group')
    },
  })

  const addMembers = useMutation({
    mutationFn: async (userIds: string[]) => {
      return apiJson<DetailResponse & { added: string[] }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id, userIds }),
        },
      )
    },
    onSuccess: (res) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], res.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(res.added.length === 1 ? '1 member added' : `${res.added.length} members added`)
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not add members')
    },
  })

  const leaveGroup = useMutation({
    mutationFn: async () => {
      return apiJson<{ ok: boolean }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id }),
        },
      )
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('You left the group')
      setInfoOpen(false)
      stopTyping()
      onClose()
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not leave the group')
    },
  })

  const setMemberRole = useMutation({
    mutationFn: async ({ userId, promote }: { userId: string; promote: boolean }) => {
      return apiJson<DetailResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members/${encodeURIComponent(userId)}`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id, action: promote ? 'promote' : 'demote' }),
        },
      )
    },
    onSuccess: (res, vars) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], res.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(vars.promote ? 'Promoted to admin' : 'Admin role removed')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not update the admin role')
    },
  })

  const removeMember = useMutation({
    mutationFn: async (userId: string) => {
      return apiJson<{ ok: boolean }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members/${encodeURIComponent(userId)}`,
        {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id }),
        },
      )
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Removed from the group')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not remove the member')
    },
  })

  const inviteLink = useMutation({
    mutationFn: async (regenerate: boolean) => {
      return apiJson<{ inviteCode: string }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/invite`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requesterId: me.id, regenerate }),
        },
      )
    },
    onSuccess: (res, regenerate) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (prev) =>
        prev ? { ...prev, inviteCode: res.inviteCode } : prev,
      )
      toast.success(regenerate ? 'Link replaced — old links no longer work' : 'Invite link ready to share')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not create the invite link')
    },
  })

  /** announcement-mode toggle (Discord stage / Telegram channel parity) */
  const toggleBroadcast = useMutation({
    mutationFn: async (broadcast: boolean) => {
      return apiJson<DetailResponse>(`/api/conversations/${encodeURIComponent(conversationId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requesterId: me.id, broadcast }),
      })
    },
    onSuccess: (res) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], res.conversation)
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(
        res.conversation.broadcastMode
          ? 'Announcement mode on — only admins can post'
          : 'Announcement mode off — everyone can post',
      )
      haptic(12)
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not update announcement mode')
    },
  })

  const onToggleBroadcast = useCallback(
    (broadcast: boolean) => toggleBroadcast.mutate(broadcast),
    [toggleBroadcast],
  )

  // ── long-press helpers ─────────────────────────────────────

  const clearLongPress = useCallback(() => {
    if (longPressRef.current !== null) {
      clearTimeout(longPressRef.current)
      longPressRef.current = null
    }
  }, [])

  /** Open the anchored glass action menu for a message (tap or long-press). */
  const openMessageMenu = useCallback(
    (message: ChatMessage, point?: { x: number; y: number }) => {
      if (Date.now() < suppressPressRef.current) {
        suppressPressRef.current = 0 // swallow the click that ends a long-press
        return
      }
      clearLongPress()
      setMenuAnchor(point ?? null)
      setSelected(message)
    },
    [clearLongPress],
  )

  const startLongPress = useCallback(
    (message: ChatMessage, point?: { x: number; y: number }) => {
      clearLongPress()
      menuPointRef.current = point ?? null
      longPressRef.current = setTimeout(() => {
        suppressPressRef.current = Date.now() + 700 // the release click must not re-open
        openMessageMenu(message, menuPointRef.current ?? undefined)
        longPressRef.current = null
      }, 450)
    },
    [clearLongPress, openMessageMenu],
  )

  /** Esc dismisses the anchored message menu. */
  useEffect(() => {
    if (selected === null) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSelected(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selected])

  const copySelected = async () => {
    if (!selected) return
    try {
      await navigator.clipboard.writeText(selected.content)
      toast.success('Copied to clipboard')
    } catch {
      toast.error('Clipboard is unavailable here')
    }
    setSelected(null)
  }

  const canDeleteSelected = selected !== null && selected.senderId === me.id && !selected.deletedAt

  const headerTitle = !isGroup && other ? other.name : displayName || 'Conversation'

  /** Discord-style custom status on DM partners surfaces in the room header */
  const dmStatus = !isGroup && other ? [other.statusEmoji, other.statusText].filter(Boolean).join(' ').trim() : ''
  const ttlSeconds = detailData?.ttlSeconds ?? 0
  const isBroadcast = isGroup && (detailData?.broadcastMode ?? false)

  const subtitle = typerLabel.length > 0
    ? typerLabel
    : isGroup
      ? isBroadcast
        ? `${detailData?.members.length ?? 0} subscribers · ${onlineOthers} online${ttlSeconds > 0 ? ' · disappearing' : ''}`
        : `${detailData?.members.length ?? 0} members · ${onlineOthers} online${ttlSeconds > 0 ? ' · disappearing' : ''}`
      : !other
        ? ''
        : dmStatus.length > 0
          ? realtime.onlineIds.has(other.id)
            ? `${dmStatus} · online`
            : `${dmStatus} · offline`
          : realtime.onlineIds.has(other.id)
            ? 'online'
            : 'offline'

  const isDark = themeMounted && resolvedTheme === 'dark'
  const dotColor = isDark ? 'rgba(255,255,255,0.055)' : 'rgba(0,0,0,0.05)'
  // layered wallpaper (prefs): soft glows over the dot grid — aurora/dusk/forest/mono/none
  // R29-a: per-conversation override (chat.convThemes) wins over the global default;
  // optional tint replaces the top glow color. Everything else unchanged.
  const [glowTop, glowBottom] = applyConvTint(
    wallpaperGlows(effectiveConvWallpaper(prefs, conversationId), isDark),
    getConvTheme(prefs, conversationId)?.tint,
  )

  return (
    <motion.div
      initial={{ y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={{ type: 'spring', stiffness: 320, damping: 34 }}
      className="absolute inset-0 z-40 flex flex-col bg-white/70 dark:bg-zinc-950/70"
      role="dialog"
      aria-label={`Conversation with ${headerTitle}`}
    >
      {/* R32: room canvas is translucent (was opaque bg-white/dark:bg-zinc-900) —
          the ui-root aurora washes glow through the whole room and the
          wallpaper glows below finally have light to work with. The only
          backdrop-blur layers in the room are the header + composer capsule. */}
      {/* R28 lead: floating pane manager — panes were orphaned (store writes
          with no renderer). Mounted once per room overlay, above content. */}
      <PipChat me={me} />

      {/* header */}
      <header className="relative z-20 flex min-h-14 shrink-0 items-center gap-1.5 border-b border-zinc-200/70 bg-white/60 px-2 pt-[env(safe-area-inset-top)] backdrop-blur-2xl backdrop-saturate-150 dark:border-zinc-800/80 dark:bg-zinc-950/55">
        <Button
          variant="ghost"
          size="icon"
          aria-label="Back to chats"
          onClick={() => {
            stopTyping()
            onClose()
          }}
          className="size-10 shrink-0 rounded-full text-zinc-600 hover:bg-transparent hover:text-zinc-900 active:scale-95 dark:text-zinc-300 dark:hover:text-white"
        >
          <ChevronLeft className="size-6" aria-hidden />
        </Button>
        {isGroup ? (
          <button
            type="button"
            aria-label="Show group info"
            onClick={() => {
              haptic(10)
              navigateHash(`#/room/${conversationId}/info`)
            }}
            className="shrink-0 rounded-full outline-none transition-transform duration-150 active:scale-90"
          >
            <GroupAvatar title={displayName} id={conversationId} size={36} />
          </button>
        ) : (
          <button
            type="button"
            aria-label={other ? `View ${other.name}'s profile` : 'Show info'}
            onClick={() => {
              haptic(10)
              if (other) navigateHash(`#/user/${encodeURIComponent(other.id)}`)
              else navigateHash(`#/room/${conversationId}/info`)
            }}
            className="shrink-0 rounded-full outline-none transition-transform duration-150 active:scale-90"
          >
            <UserAvatar
              name={other?.name ?? displayName}
              color={other?.color}
              size={36}
              showPresence
              online={other ? realtime.onlineIds.has(other.id) : false}
            />
          </button>
        )}
        <button
          type="button"
          onClick={() => navigateHash(`#/room/${conversationId}/info`)}
          aria-label="Chat info"
          className="ml-1.5 min-w-0 flex-1 text-left outline-none"
        >
          <p className="flex items-center gap-1 truncate text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
            <span className="truncate">{headerTitle}</span>
            {isBroadcast ? (
              <span
                className="flex shrink-0 items-center gap-0.5 rounded-full bg-emerald-500/10 px-1.5 py-0.5 text-[9px] font-bold text-emerald-600 dark:text-emerald-400"
                aria-label="Broadcast channel — only admins can post"
              >
                <Radio className="size-2.5" aria-hidden />
                Channel
              </span>
            ) : null}
            {isRoomMuted ? (
              <BellOff className="size-3.5 shrink-0 text-zinc-400 dark:text-zinc-500" aria-label="Notifications muted" />
            ) : null}
          </p>
          <AnimatePresence mode="wait" initial={false}>
            <motion.p
              key={subtitle}
              initial={{ opacity: 0, y: 3 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.18 }}
              className={cn(
                'truncate text-[11px]',
                typerLabel.length > 0
                  ? 'font-medium text-emerald-600 italic dark:text-emerald-400'
                  : 'text-zinc-500 dark:text-zinc-400',
              )}
            >
              {subtitle}
            </motion.p>
          </AnimatePresence>
        </button>

        {/* R37 — Signal paradigm: DM-only contact verification badge beside
            the call buttons. Verified peer → emerald ShieldCheck with a subtle
            emerald tint ring; otherwise a zinc outline icon with a tiny amber
            dot. Tap opens the shared safety-number sheet. */}
        {!isGroup && dmPeer ? (
          <Button
            variant="ghost"
            size="icon"
            aria-label={safety.data?.verified ? 'Verified' : 'Not verified'}
            onClick={() => {
              haptic(8)
              setSafetyOpen(true)
            }}
            className={cn(
              'relative size-10 shrink-0 rounded-full active:scale-95',
              safety.data?.verified
                ? 'bg-emerald-500/[0.07] text-emerald-500 ring-1 ring-inset ring-emerald-500/40 hover:bg-emerald-500/[0.12] hover:text-emerald-500'
                : 'text-zinc-500 hover:text-zinc-700 dark:hover:text-zinc-300',
            )}
          >
            <ShieldCheck className="size-5" aria-hidden />
            {!safety.data?.verified ? (
              <span
                aria-hidden
                className="absolute top-2 right-2 size-1.5 rounded-full bg-amber-500 ring-2 ring-white dark:ring-zinc-950"
              />
            ) : null}
          </Button>
        ) : null}

        {/* R33-a → R35-b: DM-only call buttons — voice always, video beside it.
            They dial through the SHELL's single call session (onStartCall);
            hosts without the shell wiring honestly show no dead buttons. */}
        {!isGroup && other && onStartCall ? (
          <>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Start voice call with ${other.name}`}
              onClick={() => {
                haptic(10)
                onStartCall({
                  conversationId,
                  peer: { id: other.id, name: other.name, color: other.color, avatar: other.avatar },
                  kind: 'voice',
                })
              }}
              className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
            >
              <Phone className="size-5" aria-hidden />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Start video call with ${other.name}`}
              onClick={() => {
                haptic(10)
                onStartCall({
                  conversationId,
                  peer: { id: other.id, name: other.name, color: other.color, avatar: other.avatar },
                  kind: 'video',
                })
              }}
              className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
            >
              <Video className="size-5" aria-hidden />
            </Button>
          </>
        ) : null}

        <Button
          variant="ghost"
          size="icon"
          aria-label={pipConversationId !== null ? 'Close mini chat window' : 'Open mini chat window'}
          aria-pressed={pipConversationId !== null}
          onClick={() => {
            haptic(10)
            if (pipConversationId !== null) {
              closePipChat()
            } else {
              startPipChat(conversationId)
            }
          }}
          className={cn(
            'size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300',
            pipConversationId !== null && 'text-emerald-600 dark:text-emerald-400',
          )}
        >
          <PictureInPicture2 className="size-5" aria-hidden />
        </Button>
        {/* R27-c: header sub-page entries — search + info for every room */}
        <Button
          variant="ghost"
          size="icon"
          aria-label="Search messages"
          onClick={() => {
            haptic(8)
            navigateHash(`#/room/${conversationId}/search`)
          }}
          className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
        >
          <Search className="size-5" aria-hidden />
        </Button>
        {/* R30-b: reminders — opens the glass reminders sheet; badge = upcoming count */}
        <Button
          variant="ghost"
          size="icon"
          aria-label={
            upcomingReminderCount > 0
              ? `Reminders — ${upcomingReminderCount} upcoming`
              : 'Reminders'
          }
          onClick={() => {
            haptic(8)
            setRemindersOpen(true)
          }}
          className="relative size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
        >
          <Bell className="size-5" aria-hidden />
          {upcomingReminderCount > 0 ? (
            <span className="absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-emerald-500 px-1 text-[9px] font-bold leading-none text-white">
              {upcomingReminderCount > 9 ? '9+' : upcomingReminderCount}
            </span>
          ) : null}
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Live voice room"
          aria-pressed={voice.inRoom}
          onClick={() => {
            haptic(10)
            setVoiceOpen(true)
          }}
          className={cn(
            'size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300',
            voice.inRoom && 'text-emerald-600 dark:text-emerald-400',
          )}
        >
          <Mic className="size-5" aria-hidden />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Chat info"
          onClick={() => {
            haptic(8)
            navigateHash(`#/room/${conversationId}/info`)
          }}
          className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
        >
          <Info className="size-5" aria-hidden />
        </Button>

        <Button
          variant="ghost"
          size="icon"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          aria-label="Conversation menu"
          onClick={() => {
            setMuteChoicesOpen(false)
            setMenuOpen((v) => !v)
          }}
          className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
        >
          <EllipsisVertical className="size-5" aria-hidden />
        </Button>

        <AnimatePresence>
          {menuOpen ? (
            <>
              <button
                type="button"
                aria-hidden
                tabIndex={-1}
                onClick={() => setMenuOpen(false)}
                className="fixed inset-0 z-30 cursor-default outline-none"
              />
              <motion.div
                initial={{ opacity: 0, scale: 0.92, y: -4 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95 }}
                transition={{ duration: 0.14 }}
                role="menu"
                className="absolute top-[calc(3.5rem+env(safe-area-inset-top))] right-2 z-40 min-w-44 overflow-hidden rounded-xl border border-zinc-200 bg-white p-1 shadow-lg dark:border-zinc-700 dark:bg-zinc-800"
              >
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setMenuOpen(false)
                    navigateHash(`#/room/${conversationId}/search`)
                  }}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                >
                  <Search className="size-4 text-emerald-500" aria-hidden />
                  Search messages
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setMenuOpen(false)
                    setInfoOpen(true)
                  }}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                >
                  <Info className="size-4 text-zinc-400" aria-hidden />
                  {isGroup ? 'Manage group' : 'Manage chat'}
                </button>
                {/* R34-b: AI recap — real LLM summary of the recent chat */}
                <button
                  type="button"
                  role="menuitem"
                  disabled={recapMutation.isPending}
                  onClick={() => {
                    setMenuOpen(false)
                    requestRecap()
                  }}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-200 dark:hover:bg-zinc-700"
                >
                  <Sparkles className="size-4 text-violet-500" aria-hidden />
                  Recap with AI
                </button>
                {isRoomMuted ? (
                  <button
                    type="button"
                    role="menuitem"
                    disabled={toggleRoomMute.isPending}
                    onClick={() => toggleRoomMute.mutate(null)}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-200 dark:hover:bg-zinc-700"
                  >
                    <VolumeX className="size-4 text-emerald-500" aria-hidden />
                    Unmute notifications
                  </button>
                ) : muteChoicesOpen ? (
                  <div className="px-1 pb-1 pt-0.5" role="group" aria-label="Mute duration">
                    <p className="px-2 pb-1 pt-1 text-[10px] font-bold uppercase tracking-widest text-zinc-400 dark:text-zinc-500">
                      Mute for
                    </p>
                    <div className="flex gap-1">
                      {([
                        { until: '8h', label: '8h' },
                        { until: '1w', label: '1w' },
                        { until: 'always', label: 'Always' },
                      ] as const).map((preset) => (
                        <button
                          key={preset.until}
                          type="button"
                          role="menuitem"
                          disabled={toggleRoomMute.isPending}
                          onClick={() => toggleRoomMute.mutate(preset.until)}
                          className="h-8 flex-1 rounded-lg bg-zinc-100 text-xs font-semibold text-zinc-700 outline-none transition-colors hover:bg-emerald-500/15 hover:text-emerald-700 active:scale-95 disabled:opacity-50 dark:bg-zinc-700 dark:text-zinc-200 dark:hover:bg-emerald-500/20 dark:hover:text-emerald-400"
                        >
                          {preset.label}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => setMuteChoicesOpen(true)}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                  >
                    <BellOff className="size-4 text-zinc-400" aria-hidden />
                    Mute notifications
                  </button>
                )}
                {ttlChoicesOpen ? (
                  <div className="px-1 pb-1 pt-0.5" role="group" aria-label="Disappearing messages">
                    <p className="flex items-center gap-1 px-2 pb-1 pt-1 text-[10px] font-bold uppercase tracking-widest text-zinc-400 dark:text-zinc-500">
                      <Timer className="size-3" aria-hidden />
                      New messages vanish after
                    </p>
                    <div className="grid grid-cols-4 gap-1">
                      {([0, 86_400, 604_800, 2_592_000] as const).map((t) => (
                        <button
                          key={t}
                          type="button"
                          role="menuitem"
                          disabled={setTtl.isPending}
                          onClick={() => setTtl.mutate(t)}
                          className={cn(
                            'h-8 rounded-lg text-xs font-semibold outline-none transition-colors active:scale-95 disabled:opacity-50',
                            ttlSeconds === t
                              ? 'bg-emerald-500/15 text-emerald-700 ring-1 ring-emerald-400 dark:text-emerald-300'
                              : 'bg-zinc-100 text-zinc-700 hover:bg-emerald-500/15 hover:text-emerald-700 dark:bg-zinc-700 dark:text-zinc-200',
                          )}
                        >
                          {t === 0 ? 'Off' : t === 86_400 ? '24h' : t === 604_800 ? '7d' : '30d'}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => setTtlChoicesOpen(true)}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                  >
                    <Timer className={cn('size-4', ttlSeconds > 0 ? 'text-emerald-500' : 'text-zinc-400')} aria-hidden />
                    Disappearing messages
                    {ttlSeconds > 0 ? (
                      <span className="ml-auto rounded-full bg-emerald-500/15 px-1.5 py-px text-[9px] font-bold uppercase text-emerald-600 dark:text-emerald-400">
                        {ttlSeconds === 86_400 ? '24h' : ttlSeconds === 604_800 ? '7d' : '30d'}
                      </span>
                    ) : null}
                  </button>
                )}
              </motion.div>
            </>
          ) : null}
        </AnimatePresence>
      </header>

      {/* live voice pill — mic stays warm with the sheet closed; tap to reopen (R21-b) */}
      <AnimatePresence>
        {voice.inRoom && !voiceOpen ? (
          <motion.button
            key="voice-live-pill"
            type="button"
            initial={{ opacity: 0, y: -12, scale: 0.9, x: '-50%' }}
            animate={{ opacity: 1, y: 0, scale: 1, x: '-50%' }}
            exit={{ opacity: 0, y: -8, scale: 0.95, x: '-50%' }}
            transition={{ type: 'spring', stiffness: 400, damping: 30 }}
            onClick={() => {
              haptic(10)
              setVoiceOpen(true)
            }}
            aria-label={`Reopen live voice room — ${voice.roster.length} ${voice.roster.length === 1 ? 'participant' : 'participants'}`}
            className="absolute top-[calc(3.75rem+env(safe-area-inset-top))] left-1/2 z-30 flex items-center gap-1.5 rounded-full border border-emerald-500/30 bg-zinc-950/85 py-1.5 pr-3 pl-2.5 text-xs font-bold text-emerald-300 shadow-lg shadow-emerald-950/40 backdrop-blur-md outline-none active:scale-95"
            style={{ willChange: 'transform' }}
          >
            <span className="relative flex size-2" aria-hidden>
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75" style={{ animationDuration: '1.4s' }} />
              <span className="relative inline-flex size-2 rounded-full bg-emerald-400" />
            </span>
            <Mic className="size-3.5" aria-hidden />
            Voice · {voice.roster.length} live
          </motion.button>
        ) : null}
      </AnimatePresence>

      {/* pinned banner — slim glass strip (R27-c); tap → compact glass pins sheet */}
      {latestPinned ? (
        <motion.button
          type="button"
          initial={prefs.reducedMotion ? false : { opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={spring.snappy}
          onClick={() => {
            haptic(8)
            setPinnedOpen(true)
          }}
          aria-label={`Open pinned messages — ${pinnedCount} pinned`}
          className="glass-sheen relative flex shrink-0 items-center gap-2 border-b border-zinc-200/70 bg-white/60 px-3 py-1.5 text-left backdrop-blur-xl transition-colors hover:bg-white/80 dark:border-zinc-700/70 dark:bg-zinc-900/60 dark:hover:bg-zinc-900/80"
        >
          <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-emerald-500/10" aria-hidden>
            <Pin className="size-3 rotate-45 text-emerald-500" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-[10px] font-bold uppercase tracking-wider text-emerald-600 dark:text-emerald-400">
              Pinned{pinnedCount > 1 ? ` · ${pinnedCount}` : ''}
            </span>
            <span className="block truncate text-xs text-zinc-600 dark:text-zinc-300">
              {latestPinned.content.replace(/\s+/g, ' ').trim().slice(0, 80) || 'Photo'}
            </span>
          </span>
          <ChevronUp className="size-3.5 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
        </motion.button>
      ) : null}

      {/* R24-b: Zulip-style topic rail — General + real topic chips (groups only) */}
      {isGroup ? (
        <TopicBar
          topics={topics}
          activeTopicId={activeTopicId}
          onSelect={selectTopic}
          onCreate={createTopic}
          reducedMotion={prefs.reducedMotion}
        />
      ) : null}

      {/* messages */}
      <div
        ref={viewportRef}
        onScroll={handleScroll}
        className="pulse-scroll relative min-h-0 flex-1 overflow-y-auto overscroll-contain bg-white/25 px-3 pt-3 pb-2 dark:bg-black/20"
        style={{
          backgroundImage: `radial-gradient(ellipse 90% 34% at 50% -8%, ${glowTop}, transparent 62%), radial-gradient(ellipse 110% 40% at 50% 110%, ${glowBottom}, transparent 62%), radial-gradient(circle, ${dotColor} 1px, transparent 1px)`,
          backgroundSize: '100% 100%, 100% 100%, 16px 16px',
          backgroundAttachment: 'local, local, scroll',
        }}
      >
        {messages.isPending && !historyLoaded ? (
          <div role="status" aria-label="Loading messages" className="space-y-3 pt-4">
            <Skeleton className="mx-auto h-5 w-24 rounded-full" />
            <Skeleton className="h-9 w-2/5 rounded-2xl" />
            <Skeleton className="ml-auto h-12 w-1/2 rounded-2xl" />
            <Skeleton className="h-9 w-1/3 rounded-2xl" />
            <Skeleton className="ml-auto h-9 w-2/5 rounded-2xl" />
          </div>
        ) : items.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center px-6 text-center">
            {/* R32 empty state — the reference glass: deep layered panel with a
                soft emerald glow blooming behind the glyph. */}
            <div className="glass-deep glass-sheen relative flex w-full max-w-[280px] flex-col items-center gap-3 rounded-[28px] px-6 py-8">
              <span
                aria-hidden
                className="pointer-events-none absolute -top-8 left-1/2 size-32 -translate-x-1/2 rounded-full bg-[radial-gradient(circle,rgba(16,185,129,0.3),transparent_65%)] blur-md"
              />
              <div
                aria-hidden
                className="relative flex size-16 items-center justify-center rounded-3xl bg-gradient-to-br from-emerald-400/20 to-emerald-600/10 text-emerald-500 ring-1 ring-inset ring-white/40 dark:from-emerald-400/15 dark:to-emerald-600/5 dark:ring-white/10"
              >
                <SendHorizontal className="size-7 -rotate-45" />
              </div>
              <div className="relative">
                <p className="text-sm font-semibold text-zinc-600 dark:text-zinc-300">No messages yet</p>
                <p className="mt-1 text-xs text-zinc-400 dark:text-zinc-500">
                  Say hello — your words travel in real time.
                </p>
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-col">
            {hasMoreHistory ? (
              <div className="flex justify-center pb-3">
                <button
                  type="button"
                  disabled={loadingOlder}
                  onClick={() => void loadOlder()}
                  className="flex items-center gap-1.5 rounded-full border border-zinc-200 bg-white/90 px-3.5 py-1.5 text-xs font-semibold text-zinc-500 shadow-sm outline-none backdrop-blur transition-colors hover:border-emerald-300 hover:text-emerald-600 active:scale-95 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-800/90 dark:text-zinc-400 dark:hover:border-emerald-500/50 dark:hover:text-emerald-400"
                >
                  {loadingOlder ? (
                    <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                  ) : (
                    <ChevronUp className="size-3.5" aria-hidden />
                  )}
                  {loadingOlder ? 'Loading…' : 'Load older messages'}
                </button>
              </div>
            ) : null}
            {items.map((item) =>
              item.kind === 'day' ? (
                <div key={item.key} className="sticky top-1 z-20 my-3 flex justify-center">
                  <motion.span
                    initial={prefs.reducedMotion ? false : { opacity: 0, y: -6, scale: 0.96 }}
                    animate={{ opacity: 1, y: 0, scale: 1 }}
                    transition={{ duration: 0.28, ease: ease.out }}
                    className="rounded-full bg-white/85 px-3 py-1 text-[11px] font-medium text-zinc-600 shadow-sm ring-1 ring-black/5 backdrop-blur-md dark:bg-zinc-800/85 dark:text-zinc-300 dark:ring-white/10"
                  >
                    {item.label}
                  </motion.span>
                </div>
              ) : item.kind === 'unread' ? (
                <motion.div
                  key={item.key}
                  initial={prefs.reducedMotion ? false : { opacity: 0, y: -4 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.28, ease: ease.out }}
                  className="my-3 flex items-center gap-2 px-1"
                  role="separator"
                  aria-label="Unread messages"
                >
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                  <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold tracking-widest text-emerald-600 backdrop-blur-sm dark:text-emerald-400">
                    UNREAD
                  </span>
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                </motion.div>
              ) : (
                <MessageRow
                  key={item.key}
                  message={item.message}
                  head={item.head}
                  mine={item.message.senderId === me.id}
                  isGroup={isGroup}
                  readMs={othersMaxReadMs}
                  myId={me.id}
                  myName={me.name}
                  memberNames={memberNames}
                  readBy={
                    isGroup && lastOwnMessage !== null && item.message.id === lastOwnMessage.id
                      ? readByLast
                      : null
                  }
                  onPress={openMessageMenu}
                  onStartLongPress={startLongPress}
                  onEndLongPress={clearLongPress}
                  onToggleReaction={handleToggleReaction}
                  onReply={(m) => {
                    setReplyTo(m)
                    requestAnimationFrame(() => textareaRef.current?.focus())
                  }}
                  onReactionInfo={(m, emoji) => setReactionInfo({ message: m, emoji })}
                  onOpenImageGated={openImageGated}
                  onJumpToReply={jumpToReply}
                  onOpenSeenBy={openSeenBy}
                  onImageLoad={handleImageLoaded}
                  threadCount={threadCounts.get(item.message.id) ?? 0}
                  onOpenThread={openThread}
                  onVote={handleVote}
                  onClosePoll={(pollId) => closePoll.mutate(pollId)}
                  bubbleRadius={prefs.bubbleRadius}
                  density={prefs.density}
                  onOpenProfile={openProfileForAuthor}
                  highlighted={highlight !== null && highlight.id === item.message.id}
                  reducedMotion={prefs.reducedMotion}
                  justArrived={
                    item.message.id.startsWith('temp-') ||
                    (mountMsRef.current > 0 &&
                      !landedIdsRef.current.has(item.message.id) &&
                      Date.parse(item.message.createdAt) > mountMsRef.current)
                  }
                />
              ),
            )}

            <AnimatePresence>
              {typers.length > 0 ? (
                <motion.div
                  key="typing-bubble"
                  initial={{ opacity: 0, y: 8, scale: 0.96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 4, scale: 0.98 }}
                  transition={spring.snappy}
                  className="mt-1.5 flex items-end gap-1.5"
                >
                  {isGroup ? (
                    (() => {
                      const typer =
                        typers.find((t) => detailData?.members.some((m) => m.id === t.userId)) ??
                        typers[0]
                      return (
                        <UserAvatar
                          name={typer.userName}
                          color={
                            detailData?.members.find((m) => m.id === typer.userId)?.color ?? 'emerald'
                          }
                          avatar={detailData?.members.find((m) => m.id === typer.userId)?.avatar ?? null}
                          size={28}
                        />
                      )
                    })()
                  ) : other ? (
                    <UserAvatar name={other.name} color={other.color} avatar={other.avatar} size={28} />
                  ) : null}
                  {/* Telegram-style morphing pill: borderRadius breathes with the dots */}
                  <motion.div
                    animate={prefs.reducedMotion ? undefined : { borderRadius: ['1.25rem', '0.875rem', '1.25rem'] }}
                    transition={{ repeat: Infinity, duration: 0.72, ease: 'easeInOut' }}
                    className="rounded-2xl rounded-bl-md border border-zinc-100 bg-white px-3 py-2.5 shadow-sm dark:border-zinc-700 dark:bg-zinc-800"
                    style={{ willChange: 'border-radius' }}
                  >
                    <TypingDots reducedMotion={prefs.reducedMotion} />
                  </motion.div>
                </motion.div>
              ) : null}
            </AnimatePresence>
          </div>
        )}

        <AnimatePresence>
          {showJump ? (
            <motion.button
              type="button"
              aria-label={
                missedCount > 0
                  ? `Jump to newest messages — ${missedCount} new`
                  : 'Jump to newest messages'
              }
              initial={{ opacity: 0, y: 10, scale: 0.85 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.9 }}
              transition={{ type: 'spring', stiffness: 480, damping: 20 }}
              whileTap={{ scale: 0.94 }}
              onClick={() => {
                setShowJump(false)
                missedCountRef.current = 0
                setMissedCount(0)
                haptic(8)
                scrollToBottom(true)
              }}
              style={{ willChange: 'transform' }}
              className="sticky bottom-1 z-10 ml-auto mr-1 mt-2 flex items-center gap-1.5 rounded-full bg-emerald-500 py-2 pr-3.5 pl-3 text-xs font-semibold text-white shadow-lg shadow-emerald-600/30 outline-none"
            >
              New messages
              <ArrowDown className="size-3.5" aria-hidden />
              {missedCount > 0 ? (
                <motion.span
                  key={missedCount}
                  initial={prefs.reducedMotion ? false : { scale: 0.4, opacity: 0 }}
                  animate={{ scale: 1, opacity: 1 }}
                  transition={spring.bouncy}
                  className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-white px-1 text-[10px] font-bold text-emerald-600"
                >
                  {missedCount > 99 ? '99+' : missedCount}
                </motion.span>
              ) : null}
            </motion.button>
          ) : null}
        </AnimatePresence>
      </div>

      {/* composer — floating glass capsule (R22); rides the keyboard via visualViewport */}
      <motion.div
        animate={{ y: -kbdLift }}
        transition={spring.soft}
        style={{ willChange: 'transform' }}
        className="relative z-20 shrink-0"
      >
        <div className="bg-gradient-to-t from-zinc-100/90 via-zinc-100/45 to-transparent px-2 pt-1.5 pb-[max(0.5rem,env(safe-area-inset-bottom))] dark:from-zinc-950/85 dark:via-zinc-950/40 dark:to-transparent">
        <AnimatePresence initial={false}>
          {isOffline ? (
            <motion.div
              key="offline-pill"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-center gap-1.5 rounded-full bg-amber-50 px-3 py-1.5 text-[11px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-500/30">
                <CloudOff className="size-3.5 shrink-0" aria-hidden />
                <span>
                  Offline — messages you send will be queued
                  {outboxCount() > 0 ? ` (${outboxCount()} waiting)` : ''}
                </span>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <AnimatePresence initial={false}>
          {editing ? (
            <motion.div
              key="edit-bar"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-start gap-2 rounded-xl border-l-4 border-amber-400 bg-zinc-100 py-2 pr-2 pl-2.5 dark:bg-zinc-800">
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-1 text-xs font-bold text-amber-600 dark:text-amber-400">
                    <Pencil className="size-3" aria-hidden />
                    Editing message
                  </p>
                  <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">
                    {editing.content.replace(/\s+/g, ' ').slice(0, 120) || 'Media caption'}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Cancel editing"
                  onClick={cancelEdit}
                  className="rounded-full p-1.5 text-zinc-400 outline-none transition-colors hover:bg-zinc-200 hover:text-zinc-600 dark:hover:bg-zinc-700 dark:hover:text-zinc-200"
                >
                  <X className="size-4" aria-hidden />
                </button>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <AnimatePresence initial={false}>
          {activeTopic && !editing ? (
            <motion.div
              key="topic-filing-pill"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-center gap-1.5 rounded-full bg-emerald-50 px-3 py-1.5 text-[11px] font-semibold text-emerald-700 ring-1 ring-inset ring-emerald-200 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-500/30">
                <MessagesSquare className="size-3.5 shrink-0" aria-hidden />
                <span className="min-w-0 flex-1 truncate">Filing to #{activeTopic.name}</span>
                <button
                  type="button"
                  aria-label="Stop filing to this topic — back to General"
                  onClick={() => {
                    haptic(8)
                    setActiveTopicId(null)
                  }}
                  className="rounded-full p-0.5 outline-none transition-transform hover:scale-110 active:scale-90"
                >
                  <X className="size-3.5" aria-hidden />
                </button>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <AnimatePresence initial={false}>
          {replyTo ? (
            <motion.div
              key="reply-bar"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-start gap-2 rounded-xl border-l-4 border-emerald-500 bg-zinc-100 py-2 pr-2 pl-2.5 dark:bg-zinc-800">
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-bold text-emerald-600 dark:text-emerald-400">
                    Replying to {replyTo.sender.id === me.id ? 'yourself' : replyTo.sender.name}
                  </p>
                  <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">
                    {replyTo.deletedAt
                      ? 'Deleted message'
                      : replyTo.content.replace(/\s+/g, ' ').slice(0, 120)}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Cancel reply"
                  onClick={() => setReplyTo(null)}
                  className="rounded-full p-1.5 text-zinc-400 outline-none transition-colors hover:bg-zinc-200 hover:text-zinc-600 dark:hover:bg-zinc-700 dark:hover:text-zinc-200"
                >
                  <X className="size-4" aria-hidden />
                </button>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <AnimatePresence initial={false}>
          {scheduledChip !== null ? (
            <motion.div
              key="scheduled-chip"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <button
                type="button"
                onClick={() => setScheduledListOpen(true)}
                className="mb-2 flex w-full items-center gap-1.5 rounded-full bg-amber-50 px-3 py-1.5 text-left text-[11px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200 transition-colors hover:bg-amber-100 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-500/30"
              >
                <CalendarClock className="size-3.5 shrink-0" aria-hidden />
                {scheduledChip.next} · {scheduledChip.count} pending — tap to manage
              </button>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <AnimatePresence initial={false}>
          {pendingEffect !== null ? (
            <motion.div
              key="effect-chip"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-[11px] font-semibold text-violet-700 ring-1 ring-inset ring-violet-200 dark:bg-violet-500/10 dark:text-violet-300 dark:ring-violet-500/30">
                <Sparkles className="size-3.5 shrink-0" aria-hidden />
                <span className="min-w-0 flex-1 truncate">
                  {pendingEffect} effect armed — next message pops
                </span>
                <button
                  type="button"
                  aria-label="Cancel effect"
                  onClick={() => setPendingEffect(null)}
                  className="rounded-full p-0.5 outline-none transition-transform hover:scale-110 active:scale-90"
                >
                  <X className="size-3.5" aria-hidden />
                </button>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <AnimatePresence initial={false}>
          {anonNext && isGroup && !recording ? (
            <motion.div
              key="anon-pill"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="overflow-hidden"
            >
              <div className="mb-2 flex items-center gap-2 rounded-full bg-emerald-500/15 px-3 py-1.5 text-[11px] font-semibold text-emerald-700 ring-1 ring-inset ring-emerald-400/40 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-500/40">
                <motion.span
                  initial={prefs.reducedMotion ? false : { scale: 1 }}
                  animate={prefs.reducedMotion ? undefined : { scale: [1, 1.16, 1] }}
                  transition={{ duration: 0.55, ease: 'easeOut' }}
                  className="flex"
                >
                  <VenetianMask className="size-3.5 shrink-0" aria-hidden />
                </motion.span>
                <span className="min-w-0 flex-1">Incognito on — next message hides your name</span>
                <button
                  type="button"
                  aria-label="Turn off incognito"
                  onClick={() => {
                    setAnonNext(false)
                    anonNextRef.current = false
                  }}
                  className="rounded-full p-0.5 outline-none transition-transform hover:scale-110 active:scale-90"
                >
                  <X className="size-3.5" aria-hidden />
                </button>
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        {/* R34-b: AI recap card — pinned above the composer, auto-dismisses.
            Real LLM output via /api/ai/recap; failures surface as honest toasts. */}
        <AnimatePresence initial={false}>
          {recap !== null || recapMutation.isPending ? (
            <motion.div
              key="ai-recap-card"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={spring.soft}
              className="overflow-hidden"
            >
              <div className="glass-deep glass-sheen mb-2 rounded-2xl p-3">
                <div className="flex items-center gap-2">
                  <span
                    className="flex size-7 shrink-0 items-center justify-center rounded-full bg-violet-500/10 text-violet-600 dark:text-violet-400"
                    aria-hidden
                  >
                    <Sparkles className="size-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-bold text-zinc-900 dark:text-zinc-50">AI recap</p>
                    <p className="text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
                      {recapMutation.isPending
                        ? 'Summarizing the latest messages'
                        : recap
                          ? `Based on ${recap.basedOn} messages`
                          : ''}
                    </p>
                  </div>
                  {recap !== null && !recapMutation.isPending ? (
                    <button
                      type="button"
                      onClick={() => {
                        void navigator.clipboard
                          .writeText(recap.text)
                          .then(() => toast.success('Recap copied'))
                          .catch(() => toast.error('Could not copy the recap'))
                      }}
                      className="glass-pill flex h-7 shrink-0 items-center gap-1 px-2.5 text-[11px] font-bold text-emerald-600 outline-none transition-transform active:scale-95 dark:text-emerald-400"
                    >
                      <Copy className="size-3" aria-hidden />
                      Copy
                    </button>
                  ) : null}
                  <button
                    type="button"
                    aria-label="Dismiss recap"
                    onClick={() => setRecap(null)}
                    className="glass-pill flex size-7 shrink-0 items-center justify-center text-zinc-500 outline-none transition-transform active:scale-90 dark:text-zinc-400"
                  >
                    <X className="size-3.5" aria-hidden />
                  </button>
                </div>
                {recap !== null && !recapMutation.isPending ? (
                  <p className="mt-2 whitespace-pre-line text-[12.5px] leading-relaxed text-zinc-700 dark:text-zinc-200">
                    {recap.text}
                  </p>
                ) : (
                  <div className="mt-2 flex items-center gap-2 text-[12px] font-medium text-zinc-500 dark:text-zinc-400">
                    <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                    Reading the room…
                  </div>
                )}
              </div>
            </motion.div>
          ) : null}
        </AnimatePresence>

        {/* attachments tray — Discord-style progressive disclosure (R34-b):
            the bar keeps the frequent actions (photo/sticker/emoji/mic),
            everything else lives here GROUPED by purpose behind the '+'.
            Tap-away backdrop + Esc close it. */}
        <AnimatePresence>
          {trayOpen ? (
            <motion.button
              key="tray-backdrop"
              type="button"
              aria-hidden
              tabIndex={-1}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.16 }}
              onClick={() => setTray(false)}
              className="absolute inset-x-0 bottom-0 z-0 cursor-default bg-zinc-950/25 outline-none dark:bg-black/40"
              style={{ top: '-100vh' }}
            />
          ) : null}
        </AnimatePresence>
        <AnimatePresence initial={false}>
          {trayOpen && !recording ? (
            <motion.div
              key="attach-tray"
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={spring.soft}
              className="relative z-10 overflow-hidden"
            >
              <div
                role="group"
                aria-label="Attachments and tools"
                className="pulse-scroll mb-2 max-h-[min(58vh,440px)] overflow-y-auto rounded-3xl bg-white/60 p-2.5 ring-1 ring-inset ring-black/5 shadow-sm backdrop-blur-xl dark:bg-zinc-900/50 dark:ring-white/10"
              >
                {trayGroups.map((group, gi) => {
                  // one shared stagger timeline across all groups
                  const offset = trayGroups
                    .slice(0, gi)
                    .reduce((sum, g) => sum + g.tiles.length, 0)
                  return (
                    <div key={group.title} className={cn(gi > 0 && 'mt-3')}>
                      <span className="glass-pill mb-1.5 inline-flex rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
                        {group.title}
                      </span>
                      <div className="grid grid-cols-2 gap-2">
                        {group.tiles.map((tile, i) => (
                          <motion.button
                            key={tile.label}
                            type="button"
                            disabled={tile.disabled}
                            onClick={tile.run}
                            initial={
                              prefs.reducedMotion ? false : { opacity: 0, y: 10, scale: 0.94 }
                            }
                            animate={{ opacity: 1, y: 0, scale: 1 }}
                            transition={{
                              ...spring.bouncy,
                              delay: prefs.reducedMotion ? 0 : (offset + i) * 0.022,
                            }}
                            whileTap={tile.disabled ? undefined : { scale: 0.96 }}
                            className="flex w-full items-center gap-2.5 rounded-2xl bg-white/55 px-2.5 py-2 text-left ring-1 ring-inset ring-black/[0.04] outline-none transition-colors hover:bg-white/90 disabled:opacity-40 dark:bg-white/[0.04] dark:ring-white/[0.06] dark:hover:bg-white/[0.09]"
                          >
                            <span
                              className={cn(
                                'flex size-9 shrink-0 items-center justify-center rounded-full',
                                tile.tone,
                              )}
                              aria-hidden
                            >
                              <tile.icon className="size-[18px]" />
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-[12.5px] font-semibold text-zinc-700 dark:text-zinc-100">
                                {tile.label}
                              </span>
                              <span className="block truncate text-[10px] text-zinc-400 dark:text-zinc-500">
                                {tile.help}
                              </span>
                            </span>
                          </motion.button>
                        ))}
                      </div>
                    </div>
                  )
                })}
              </div>
              {trayEffectsOpen ? (
                <motion.div
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={spring.soft}
                  className="mb-2 flex gap-1.5"
                  role="group"
                  aria-label="Arm a message effect"
                >
                  {(Object.keys(EFFECT_ICON) as MessageEffectName[]).map((effectName) => {
                    const EffectIcon = EFFECT_ICON[effectName]
                    return (
                      <motion.button
                        key={effectName}
                        type="button"
                        whileTap={{ scale: 0.92 }}
                        transition={spring.bouncy}
                        onClick={() => {
                          setPendingEffect(effectName)
                          setTray(false)
                          toast(`${effectName} effect armed — type a message and send`)
                          requestAnimationFrame(() => textareaRef.current?.focus())
                        }}
                        className="flex flex-1 items-center justify-center gap-1 rounded-full bg-violet-500/10 py-2 text-[11px] font-bold text-violet-700 ring-1 ring-inset ring-violet-500/25 outline-none transition-colors hover:bg-violet-500/20 dark:text-violet-300"
                      >
                        <EffectIcon className="size-3.5 shrink-0" aria-hidden />
                        {effectName}
                      </motion.button>
                    )
                  })}
                </motion.div>
              ) : null}
            </motion.div>
          ) : null}
        </AnimatePresence>

        {broadcastLocked ? (
          <div className="glass-deep glass-sheen flex items-center justify-center gap-2 rounded-2xl px-3 py-3 text-xs font-semibold text-zinc-500 dark:text-zinc-400">
            <Lock className="size-4 shrink-0 text-emerald-500" aria-hidden />
            Only admins can post
          </div>
        ) : null}

        <div
          onFocusCapture={() => setComposerFocus(true)}
          onBlurCapture={(e) => {
            if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setComposerFocus(false)
          }}
          className={cn(
            'relative flex items-end gap-1 rounded-[26px] p-1.5 shadow-[0_8px_32px_rgba(0,0,0,0.18)] backdrop-blur-2xl bg-white/80 ring-1 ring-inset ring-black/[0.06] dark:bg-zinc-900/70 dark:ring-white/10 dark:shadow-[0_8px_32px_rgba(0,0,0,0.5)]',
            broadcastLocked && 'pointer-events-none select-none opacity-40',
          )}
        >
          {/* emerald focus hairline — springs in whenever the capsule holds focus */}
          <motion.div
            aria-hidden
            initial={false}
            animate={{ opacity: composerFocus ? 1 : 0, scale: composerFocus ? 1 : 0.985 }}
            transition={spring.soft}
            className="pointer-events-none absolute inset-0 rounded-[26px] ring-2 ring-inset ring-emerald-500/40"
          />
          {/* slash-command palette (Discord/Slack-style) — fast-path over the plain parser */}
          {!editing && !recording ? (
            <SlashPalette
              open={input.startsWith('/') && !broadcastLocked && !slashDismissed}
              query={input}
              onSelect={runPaletteCommand}
              onDismiss={() => setSlashDismissed(true)}
            />
          ) : null}
          {/* @mention autocomplete (Slack/Discord-style) */}
          {mentionMatches.length > 0 ? (
            <div
              role="listbox"
              aria-label="Mention suggestions"
              className="absolute bottom-full left-0 right-0 z-30 mb-2 overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-lg shadow-zinc-900/10 dark:border-zinc-700 dark:bg-zinc-800"
            >
              {mentionMatches.map((m, i) => (
                <button
                  key={m.id}
                  type="button"
                  role="option"
                  aria-selected={i === 0}
                  onClick={() => pickMention(m)}
                  className={cn(
                    'flex w-full items-center gap-2.5 px-3 py-2 text-left outline-none transition-colors',
                    i === 0
                      ? 'bg-emerald-50 dark:bg-emerald-500/10'
                      : 'hover:bg-zinc-100 dark:hover:bg-zinc-700',
                  )}
                >
                  <UserAvatar name={m.name} color={m.color} avatar={m.avatar} size={26} />
                  <span className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">{m.name}</span>
                  {m.id === me.id ? (
                    <span className="text-[10px] font-medium text-zinc-400 dark:text-zinc-500">(you)</span>
                  ) : null}
                </button>
              ))}
            </div>
          ) : null}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            aria-hidden
            tabIndex={-1}
            onChange={(e) => void handleImagePicked(e.target.files?.[0])}
          />
          {recording ? (
            <>
              <motion.button
                type="button"
                aria-label="Cancel recording"
                onClick={() => finishRecording(false)}
                whileTap={{ scale: 0.9 }}
                transition={pressSpring}
                className="flex size-11 shrink-0 items-center justify-center rounded-full bg-rose-100 text-rose-600 outline-none transition-colors hover:bg-rose-200 dark:bg-rose-500/15 dark:text-rose-400 dark:hover:bg-rose-500/25"
              >
                <X className="size-5" aria-hidden />
              </motion.button>
              <motion.div
                role="status"
                aria-label="Recording voice note"
                initial={{ opacity: 0, x: -14 }}
                animate={{ opacity: 1, x: 0 }}
                transition={spring.snappy}
                className="flex h-11 flex-1 items-center gap-2.5 rounded-full border border-rose-200 bg-rose-50 px-4 dark:border-rose-500/30 dark:bg-rose-500/10"
              >
                {/* live pulsing red radar ring around the record indicator */}
                <span className="relative flex size-2.5 shrink-0" aria-hidden>
                  {!prefs.reducedMotion ? (
                    <motion.span
                      className="absolute inset-0 rounded-full border-2 border-rose-400"
                      animate={{ scale: [1, 2], opacity: [0.85, 0] }}
                      transition={{ repeat: Infinity, duration: 1.1, ease: 'easeOut' }}
                    />
                  ) : null}
                  <span className="relative inline-flex size-2.5 rounded-full bg-rose-500" />
                </span>
                <motion.span
                  initial={{ opacity: 0, y: -6, scale: 0.8 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  transition={spring.bouncy}
                  className="text-sm font-semibold tabular-nums text-rose-600 dark:text-rose-400"
                >
                  {formatVoicems(recordMs)}
                </motion.span>
                <span className="ml-auto truncate text-xs text-zinc-400 dark:text-zinc-500">
                  Recording voice note…
                </span>
              </motion.div>
              <motion.button
                type="button"
                aria-label="Stop and send voice note"
                disabled={sendingVoice}
                onClick={() => finishRecording(true)}
                whileTap={sendingVoice ? undefined : { scale: 0.9 }}
                transition={pressSpring}
                className="flex size-11 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600 text-white shadow-md shadow-emerald-600/25 outline-none transition-colors hover:brightness-105 disabled:opacity-60"
              >
                {sendingVoice ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <SendHorizontal className="size-5" aria-hidden />
                )}
              </motion.button>
            </>
          ) : (
            <>
              {/* + tray toggle — rotates 45° into a ✕ while the tray is open */}
              <motion.button
                type="button"
                aria-label={trayOpen ? 'Close attachments tray' : 'Open attachments tray'}
                aria-expanded={trayOpen}
                disabled={broadcastLocked}
                onClick={() => {
                  haptic(8)
                  setTray(!trayOpen)
                }}
                whileTap={broadcastLocked ? undefined : { scale: 0.88 }}
                animate={{ rotate: trayOpen ? 45 : 0 }}
                transition={spring.snappy}
                className={cn(
                  'flex size-11 shrink-0 items-center justify-center rounded-full outline-none transition-colors',
                  trayOpen
                    ? 'bg-zinc-900/5 text-zinc-700 dark:bg-white/10 dark:text-zinc-200'
                    : 'text-zinc-400 hover:bg-zinc-100 hover:text-emerald-600 dark:hover:bg-zinc-800',
                )}
              >
                <Plus className="size-6" aria-hidden />
              </motion.button>
              {/* R34-b: frequent actions stay on the bar (Discord rule) — photo + sticker */}
              <motion.button
                type="button"
                aria-label="Send a photo"
                disabled={sendingImage || broadcastLocked}
                onClick={() => fileInputRef.current?.click()}
                whileTap={sendingImage || broadcastLocked ? undefined : { scale: 0.88 }}
                transition={spring.snappy}
                className="flex size-11 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-emerald-600 disabled:opacity-50 dark:hover:bg-zinc-800 dark:hover:text-emerald-400"
              >
                {sendingImage ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <ImagePlus className="size-5" aria-hidden />
                )}
              </motion.button>
              <motion.button
                type="button"
                aria-label="Open sticker packs"
                disabled={broadcastLocked}
                onClick={() => {
                  haptic(8)
                  setStickerOpen(true)
                }}
                whileTap={broadcastLocked ? undefined : { scale: 0.88 }}
                transition={spring.snappy}
                className="flex size-11 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-amber-500 disabled:opacity-50 dark:hover:bg-zinc-800"
              >
                <Sticker className="size-5" aria-hidden />
              </motion.button>
              <textarea
                ref={textareaRef}
                value={input}
                rows={1}
                aria-label="Message input"
                placeholder="Type a message"
                maxLength={2000}
                enterKeyHint="send"
                onChange={(e) => handleInputChange(e.target.value)}
                onKeyDown={handleKeyDown}
                onBlur={stopTyping}
                className="pulse-scroll max-h-[120px] min-h-[44px] w-full flex-1 resize-none bg-transparent px-1 py-2.5 text-sm leading-snug text-zinc-900 outline-none transition-[height] duration-200 ease-out placeholder:text-zinc-400 dark:text-zinc-100 dark:placeholder:text-zinc-500"
              />
              {/* R24-b incognito arm moved to the tray's Express group (R34-b regroup) */}
              <Popover>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    aria-label="Insert emoji"
                    className="flex size-11 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-amber-500 active:scale-90 dark:hover:bg-zinc-800"
                  >
                    <Smile className="size-6" aria-hidden />
                  </button>
                </PopoverTrigger>
                <PopoverContent
                  side="top"
                  align="start"
                  sideOffset={10}
                  className="w-[272px] rounded-2xl p-2 dark:bg-zinc-800"
                >
                  <div className="grid grid-cols-8 gap-0.5">
                    {EMOJI_PICKER_CHOICES.map((emoji) => (
                      <button
                        key={emoji}
                        type="button"
                        aria-label={`Insert ${emoji}`}
                        onClick={() => {
                          setInput((prev) => prev + emoji)
                          requestAnimationFrame(() => {
                            autosize()
                            textareaRef.current?.focus()
                          })
                        }}
                        className="rounded-lg py-1 text-xl outline-none transition-transform hover:bg-zinc-100 hover:scale-125 active:scale-95 dark:hover:bg-zinc-700"
                      >
                        {emoji}
                      </button>
                    ))}
                  </div>
                </PopoverContent>
              </Popover>
              {input.trim().length === 0 && !editing && pendingEffect === null ? (
                <motion.button
                  type="button"
                  aria-label="Record voice note"
                  onClick={() => void startRecording()}
                  whileTap={pressTap}
                  transition={pressSpring}
                  className="flex size-11 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-zinc-500 outline-none transition-colors hover:bg-emerald-500/10 hover:text-emerald-600 dark:bg-zinc-800 dark:text-zinc-400 dark:hover:text-emerald-400"
                >
                  <motion.span
                    key={sendPop}
                    initial={false}
                    animate={sendPop > 0 && !prefs.reducedMotion ? { scale: [1, 1.18, 1] } : { scale: 1 }}
                    transition={{ duration: 0.32, times: [0, 0.45, 1], ease: 'easeOut' }}
                    className="flex"
                  >
                    <Mic className="size-5" aria-hidden />
                  </motion.span>
                </motion.button>
              ) : (
                <motion.button
                  type="button"
                  aria-label={editing ? 'Save edit' : 'Send message'}
                  disabled={input.trim().length === 0 || sendMessage.isPending || editMessage.isPending}
                  onClick={submit}
                  whileTap={input.trim().length > 0 && !sendMessage.isPending ? { scale: 0.88 } : undefined}
                  transition={pressSpring}
                  className={cn(
                    'flex size-11 shrink-0 items-center justify-center rounded-full outline-none transition-colors',
                    input.trim().length > 0
                      ? 'bg-gradient-to-br from-emerald-400 to-emerald-600 text-white shadow-md shadow-emerald-600/30'
                      : 'bg-zinc-200 text-zinc-400 dark:bg-zinc-700 dark:text-zinc-500',
                  )}
                >
                  <AnimatePresence mode="wait" initial={false}>
                    {sendMessage.isPending || editMessage.isPending ? (
                      <motion.span
                        key="sending"
                        initial={{ opacity: 0, scale: 0.55, rotate: -90 }}
                        animate={{ opacity: 1, scale: 1, rotate: 0 }}
                        exit={{ opacity: 0, scale: 0.55 }}
                        transition={spring.snappy}
                        className="flex"
                      >
                        <LoaderCircle className="size-5 animate-spin" aria-hidden />
                      </motion.span>
                    ) : editing ? (
                      <motion.span
                        key="edit"
                        initial={{ opacity: 0, scale: 0.55 }}
                        animate={{ opacity: 1, scale: 1 }}
                        exit={{ opacity: 0, scale: 0.55 }}
                        transition={spring.bouncy}
                        className="flex"
                      >
                        <Check className="size-5" aria-hidden />
                      </motion.span>
                    ) : input.trim().length > 0 ? (
                      <motion.span
                        key="plane"
                        initial={{ opacity: 0, x: 16, scale: 0.5, rotate: -35 }}
                        animate={{ opacity: 1, x: 0, scale: 1, rotate: 0 }}
                        exit={{ opacity: 0, x: -12, scale: 0.6 }}
                        transition={spring.bouncy}
                        className="flex"
                      >
                        <SendHorizontal className="size-5" aria-hidden />
                      </motion.span>
                    ) : (
                      <motion.span
                        key="idle-dot"
                        initial={{ opacity: 0, scale: 0.5 }}
                        animate={{ opacity: 1, scale: 1 }}
                        exit={{ opacity: 0, scale: 0.5 }}
                        transition={spring.snappy}
                        className="flex"
                      >
                        <span className="block size-2 rounded-full bg-current" aria-hidden />
                      </motion.span>
                    )}
                  </AnimatePresence>
                </motion.button>
              )}
            </>
          )}
        </div>
        </div>
      </motion.div>
      {/* clearance for the floating bottom dock (composer must never sit under it) */}
      <div className="shrink-0 bg-zinc-100/80 dark:bg-zinc-950/60" style={{ height: dockInset }} aria-hidden />

      {/* message actions — compact frosted glass menu anchored to the bubble (R26-b).
          Springs in from the tap point, dismisses on backdrop tap or Esc; all
          previous actions kept (reply, thread, copy, forward, save, task, pin,
          info, edit, delete-for-everyone). */}
      <AnimatePresence>
        {selected !== null ? (
          <MessageActionMenu
            key="message-action-menu"
            message={selected}
            anchor={menuAnchor}
            myId={me.id}
            isSaved={isSavedIds.has(selected.id)}
            canDelete={canDeleteSelected}
            savePending={toggleSaved.isPending}
            taskPending={convertToTask.isPending}
            pinPending={pinMessage.isPending}
            onClose={() => setSelected(null)}
            onReact={(emoji, el) => {
              const adding = !selected.reactions.some(
                (g) => g.emoji === emoji && g.userIds.includes(me.id),
              )
              if (adding) fireParticlesAt(el, 'hearts', 24)
              handleToggleReaction(selected.id, emoji)
              setSelected(null)
            }}
            onReply={() => {
              setReplyTo(selected)
              setSelected(null)
              requestAnimationFrame(() => textareaRef.current?.focus())
            }}
            onThread={(target) => {
              setSelected(null)
              openThread(target)
            }}
            onCopy={copySelected}
            onForward={(target) => {
              setSelected(null)
              startForward(target)
            }}
            onSave={() => {
              toggleSaved.mutate(selected.id)
              setSelected(null)
            }}
            onTask={() => convertToTask.mutate(selected.id)}
            onRemind={() => {
              const target = selected
              setSelected(null)
              if (target) {
                setRemindTarget(target)
                setRemindCustom(false)
                setRemindCustomAt('')
              }
            }}
            onPin={() => {
              const targetId = selected.id
              setSelected(null)
              pinMessage.mutate(targetId)
            }}
            onInfo={() => openMessageInfo(selected)}
            onEdit={() => startEdit(selected)}
            onDelete={() => setConfirmingDelete(true)}
          />
        ) : null}
      </AnimatePresence>

      {/* R30-b: remind-me time picker — compact glass panel for the selected message */}
      <AnimatePresence>
        {remindTarget !== null ? (
          <>
            <motion.button
              key="remind-picker-backdrop"
              type="button"
              aria-hidden
              tabIndex={-1}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.16 }}
              onClick={() => setRemindTarget(null)}
              className="fixed inset-0 z-[60] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
            />
            <GlassMenu
              key="remind-picker-panel"
              aria-label="Pick a reminder time"
              className="fixed bottom-[max(0.9rem,env(safe-area-inset-bottom))] left-1/2 z-[61] w-[min(320px,calc(100vw-16px))] -translate-x-1/2 rounded-2xl"
            >
              <GlassMenuLabel>Remind me</GlassMenuLabel>
              <GlassMenuSeparator className="mt-0.5" />
              {remindPresets().map((preset) => (
                <GlassMenuItem
                  key={preset.label}
                  icon={Clock}
                  label={preset.label}
                  onClick={() => {
                    const target = remindTarget
                    setRemindTarget(null)
                    void createReminder(target.id, preset.at)
                  }}
                />
              ))}
              <GlassMenuItem
                icon={CalendarClock}
                label="Custom…"
                active={remindCustom}
                onClick={() => setRemindCustom((v) => !v)}
              />
              {remindCustom ? (
                <div className="flex items-center gap-1.5 px-2 pb-1.5 pt-1">
                  <Input
                    type="datetime-local"
                    value={remindCustomAt}
                    min={remindMinAttr()}
                    onChange={(e) => setRemindCustomAt(e.target.value)}
                    aria-label="Custom reminder date and time"
                    className="h-9 flex-1 rounded-xl bg-zinc-100 text-[12px] dark:bg-zinc-800"
                  />
                  <Button
                    variant="outline"
                    disabled={remindCustomAt.length === 0}
                    onClick={() => {
                      const target = remindTarget
                      if (!target) return
                      const at = new Date(remindCustomAt)
                      if (Number.isNaN(at.getTime()) || at.getTime() < Date.now() - 60_000) {
                        toast.error('Pick a future date and time')
                        return
                      }
                      setRemindTarget(null)
                      void createReminder(target.id, at)
                    }}
                    className="h-9 rounded-xl px-3 text-xs font-bold"
                  >
                    Set
                  </Button>
                </div>
              ) : null}
            </GlassMenu>
          </>
        ) : null}
      </AnimatePresence>

      <AlertDialog open={confirmingDelete} onOpenChange={setConfirmingDelete}>
        <AlertDialogContent className="max-w-[320px] rounded-2xl bg-white dark:bg-zinc-900 sm:left-1/2 sm:translate-x-[-50%]">
          <AlertDialogHeader>
            <AlertDialogTitle className="tracking-tight">Delete this message?</AlertDialogTitle>
            <AlertDialogDescription className="text-[13px] leading-relaxed">
              It will be replaced with a tombstone for everyone here. This can&apos;t be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="gap-2">
            <AlertDialogCancel className="rounded-xl">Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={deleteMessage.isPending}
              onClick={() => {
                if (selected) deleteMessage.mutate(selected.id)
              }}
              className="rounded-xl bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/40"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* photo lightbox */}
      <AnimatePresence>
        {lightboxSrc ? (
          <motion.div
            key="lightbox"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.18 }}
            role="dialog"
            aria-label="Photo viewer"
            className="absolute inset-0 z-50 flex items-center justify-center bg-black/85 backdrop-blur-sm"
            onClick={() => setLightboxSrc(null)}
          >
            <motion.img
              src={lightboxSrc}
              alt="Shared photo enlarged"
              initial={{ scale: 0.88, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.94, opacity: 0 }}
              transition={{ type: 'spring', stiffness: 300, damping: 28 }}
              className="max-h-[74%] max-w-[92%] rounded-2xl shadow-2xl"
              onClick={(e) => e.stopPropagation()}
            />
            <button
              type="button"
              aria-label="Close photo viewer"
              onClick={() => setLightboxSrc(null)}
              className="absolute right-3 top-3 rounded-full bg-white/10 p-2 text-white outline-none transition-colors hover:bg-white/20"
            >
              <X className="size-5" aria-hidden />
            </button>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* caption sheet — staged image awaiting an optional caption */}
      <Drawer
        open={pendingImage !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPendingImage(null)
            setCaptionDraft('')
          }
        }}
      >
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-4 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          {pendingImage !== null ? (
            <div className="pb-2">
              <DrawerTitle className="sr-only">Send photo</DrawerTitle>
              <p className="pb-2 pt-1 text-center text-xs font-medium text-zinc-400 dark:text-zinc-500">
                Send to {headerTitle}
              </p>
              <div className="flex justify-center">
                <img
                  src={pendingImage.preview}
                  alt="Photo to send"
                  className="max-h-44 w-auto max-w-full rounded-2xl shadow-md"
                />
              </div>
              <div className="mt-3 flex items-center gap-2">
                <Input
                  autoFocus
                  value={captionDraft}
                  maxLength={500}
                  onChange={(e) => setCaptionDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault()
                      sendCaptionedImage()
                    }
                  }}
                  placeholder="Add a caption…"
                  aria-label="Photo caption"
                  className="h-11 flex-1 rounded-2xl border-zinc-200 bg-zinc-100 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
                <span
                  aria-hidden
                  className={cn(
                    'w-9 shrink-0 text-right text-[10px] tabular-nums',
                    captionDraft.length > 450 ? 'text-amber-500' : 'text-zinc-300 dark:text-zinc-600',
                  )}
                >
                  {500 - captionDraft.length}
                </span>
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  variant="outline"
                  onClick={() => {
                    setPendingImage(null)
                    setCaptionDraft('')
                  }}
                  className="h-11 flex-1 rounded-2xl text-sm font-medium"
                >
                  Cancel
                </Button>
                <Button
                  disabled={sendMessage.isPending}
                  onClick={sendCaptionedImage}
                  className="h-11 flex-[1.6] gap-1.5 rounded-2xl bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90"
                >
                  {sendMessage.isPending ? (
                    <LoaderCircle className="size-4 animate-spin" aria-hidden />
                  ) : (
                    <SendHorizontal className="size-4" aria-hidden />
                  )}
                  Send
                </Button>
              </div>
            </div>
          ) : null}
        </DrawerContent>
      </Drawer>

      {/* pinned messages — compact glass sheet (R27-c) */}
      <AnimatePresence>
        {pinnedOpen ? (
          <RoomPinsSheet
            onClose={() => setPinnedOpen(false)}
            pins={pinnedList}
            loading={pinnedQuery.isPending && pinnedList.length === 0}
            myId={me.id}
            unpinPending={pinMessage.isPending}
            onJump={(messageId) => {
              setPinnedOpen(false)
              void jumpToMessage(messageId)
            }}
            onUnpin={(messageId) => pinMessage.mutate(messageId)}
          />
        ) : null}
      </AnimatePresence>

      {/* reminders — compact glass sheet (R30-b); close refreshes the badge */}
      <AnimatePresence>
        {remindersOpen ? (
          <RemindersSheet
            myId={me.id}
            onClose={() => {
              setRemindersOpen(false)
              void queryClient.invalidateQueries({ queryKey: remindersKey(me.id) })
            }}
          />
        ) : null}
      </AnimatePresence>

      {/* 1:1 voice/video calls — since R35-b the overlay mounts ONCE at shell
          level (main-shell) so rings surface on every screen; nothing here. */}

      {/* who-reacted sheet */}
      <Drawer open={reactionInfo !== null} onOpenChange={(open) => !open && setReactionInfo(null)}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          <DrawerTitle className="sr-only">Reaction details</DrawerTitle>
          <DrawerDescription className="sr-only">Who reacted to this message</DrawerDescription>
          {reactionInfo ? (
            <div className="pb-2">
              <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
                <span className="text-lg leading-none">{reactionInfo.emoji}</span>
                {(() => {
                  const group = reactionInfo.message.reactions.find((g) => g.emoji === reactionInfo.emoji)
                  const n = group?.count ?? 0
                  return n === 1 ? '1 reaction' : `${n} reactions`
                })()}
              </p>
              <ul className="pulse-scroll max-h-56 overflow-y-auto py-1">
                {(reactionInfo.message.reactions
                  .find((g) => g.emoji === reactionInfo.emoji)
                  ?.userIds.map((userId) => ({
                    id: userId,
                    member: detailData?.members.find((m) => m.id === userId),
                  })) ?? [])
                  .map(({ id, member }) => (
                    <li key={id}>
                      <button
                        type="button"
                        disabled={member === undefined}
                        onClick={() => {
                          if (!member) return
                          setReactionInfo(null)
                          openProfileForUser(member)
                        }}
                        className="flex w-full items-center gap-3 rounded-xl px-2 py-2 text-left outline-none transition-colors hover:bg-zinc-50 active:bg-zinc-100 disabled:cursor-default dark:hover:bg-zinc-800/60"
                      >
                        <UserAvatar
                          name={member?.name ?? 'Unknown'}
                          color={member?.color ?? 'emerald'}
                          size={34}
                        />
                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                          {member?.name ?? 'Unknown'}
                          {id === me.id ? <span className="ml-1 text-xs text-zinc-400">(you)</span> : null}
                        </span>
                      </button>
                    </li>
                  ))}
              </ul>
              <button
                type="button"
                disabled={toggleReaction.isPending}
                onClick={() => {
                  handleToggleReaction(reactionInfo.message.id, reactionInfo.emoji)
                  setReactionInfo(null)
                }}
                className="mt-1 flex h-11 w-full items-center justify-center gap-2 rounded-2xl bg-emerald-500 text-sm font-bold text-white outline-none transition-transform hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-60"
              >
                <span className="text-base leading-none">{reactionInfo.emoji}</span>
                {reactionInfo.message.reactions
                  .find((g) => g.emoji === reactionInfo.emoji)
                  ?.userIds.includes(me.id)
                  ? 'Remove your reaction'
                  : `React ${reactionInfo.emoji}`}
              </button>
            </div>
          ) : null}
        </DrawerContent>
      </Drawer>

      {/* seen-by sheet — per-member read receipts for the latest or a picked own message */}
      <Drawer open={seenByOpen} onOpenChange={(open) => !open && setSeenByOpen(false)}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          <DrawerTitle className="sr-only">Message read receipts</DrawerTitle>
          <DrawerDescription className="sr-only">Per-member delivered and read times</DrawerDescription>
          {(() => {
            const infoTarget = infoMessage ?? lastOwnMessage
            if (!infoTarget) return null
            const othersCount = (detailData?.members.length ?? 1) - 1
            const readNow =
              detailData !== undefined && othersCount > 0
                ? detailData.members.filter(
                    (m) => m.id !== me.id && Date.parse(m.lastReadAt) >= Date.parse(infoTarget.createdAt),
                  ).length
                : 0
            const allRead = readNow >= othersCount && othersCount > 0
            return (
            <div className="pb-2">
              <div className="flex items-center justify-center gap-2 pb-1 pt-1">
                <CheckCheck className="size-4 text-emerald-500" aria-hidden />
                <p className="text-sm font-bold text-zinc-800 dark:text-zinc-100">
                  {allRead ? 'Seen by everyone' : 'Message info'}
                </p>
              </div>
              <p className="mx-auto mb-2 max-w-[300px] truncate text-center text-xs text-zinc-400 dark:text-zinc-500">
                {infoTarget.imagePath && !infoTarget.content
                  ? 'Photo'
                  : infoTarget.audioPath
                    ? 'Voice message'
                    : infoTarget.content}
              </p>
              <p className="mb-1 text-center text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
                Sent {formatTime(infoTarget.createdAt)}
                {othersCount > 0 ? ` · ${readNow}/${othersCount} read` : ''}
              </p>
              <ul className="pulse-scroll max-h-64 overflow-y-auto py-1">
                {(detailData?.members ?? [])
                  .filter((m) => m.id !== me.id)
                  .map((member) => {
                    const readMs = Date.parse(member.lastReadAt)
                    const msgMs = Date.parse(infoTarget.createdAt)
                    const read = !Number.isNaN(readMs) && !Number.isNaN(msgMs) && readMs >= msgMs
                    return (
                      <li key={member.id}>
                        <button
                          type="button"
                          onClick={() => {
                            setSeenByOpen(false)
                            openProfileForUser(member)
                          }}
                          className="flex w-full items-center gap-3 rounded-xl px-2 py-2.5 text-left outline-none transition-colors hover:bg-zinc-50 active:bg-zinc-100 dark:hover:bg-zinc-800/60"
                        >
                          <span className="relative">
                            <UserAvatar name={member.name} color={member.color} avatar={member.avatar} size={36} />
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                              {member.name}
                              {realtime.onlineIds.has(member.id) ? (
                                <span
                                  aria-label="Online now"
                                  className="ml-1.5 inline-block size-1.5 rounded-full bg-emerald-500 align-middle"
                                />
                              ) : null}
                            </span>
                            <span className="mt-0.5 block text-[11px] text-zinc-400 dark:text-zinc-500">
                              {read ? `Read at ${formatTime(member.lastReadAt)}` : 'Delivered'}
                            </span>
                          </span>
                          {read ? (
                            <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-emerald-500/15">
                              <CheckCheck className="size-3.5 text-emerald-600 dark:text-emerald-400" aria-hidden />
                            </span>
                          ) : (
                            <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-zinc-100 dark:bg-zinc-800">
                              <Check className="size-3.5 text-zinc-400 dark:text-zinc-500" aria-hidden />
                            </span>
                          )}
                        </button>
                      </li>
                    )
                  })}
              </ul>
              <button
                type="button"
                onClick={() => setSeenByOpen(false)}
                className="mt-1 flex h-11 w-full items-center justify-center rounded-2xl bg-zinc-100 text-sm font-semibold text-zinc-600 outline-none transition-transform hover:bg-zinc-200 active:scale-[0.98] dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-zinc-700"
              >
                Close
              </button>
            </div>
            )
          })()}
        </DrawerContent>
      </Drawer>

      {/* R27-c: hash-routed room sub-pages — info + search slide up over the room */}
      <AnimatePresence>
        {roomSubPage === 'info' ? (
          <RoomInfoPage
            key="room-info-page"
            me={me}
            conversationId={conversationId}
            detail={detailData}
            onlineIds={realtime.onlineIds}
            loadedMessages={messages.data ?? []}
            pinnedCount={pinnedCount}
            historyPartial={hasMoreHistory}
            reducedMotion={prefs.reducedMotion}
            onClose={() => backHash('/')}
            onOpenManager={() => setGroupInfoOpen(true)}
          />
        ) : null}
        {roomSubPage === 'search' ? (
          <RoomSearchPage
            key="room-search-page"
            me={me}
            conversationId={conversationId}
            loadedMessages={messages.data ?? []}
            reducedMotion={prefs.reducedMotion}
            onClose={() => backHash('/')}
            onJump={(messageId) => void jumpToMessage(messageId)}
          />
        ) : null}
      </AnimatePresence>

      {/* poll builder sheet */}
      <PollBuilderSheet
        open={pollBuilderOpen}
        onOpenChange={setPollBuilderOpen}
        submitting={createPoll.isPending}
        onSubmit={(question, options) => createPoll.mutate({ question, options })}
      />

      {/* R37 — DM-only safety-number sheet, opened by the header ShieldCheck
          badge (shared component; also mounted by the room info page). */}
      {!isGroup && dmPeer ? (
        <SafetySheet
          peer={dmPeer}
          meId={me.id}
          open={safetyOpen}
          onOpenChange={setSafetyOpen}
        />
      ) : null}

      {/* schedule sheet */}
      <ScheduleSheet
        draft={scheduleFor}
        onDraftChange={setScheduleFor}
        open={scheduleFor !== null}
        onOpenChange={(open) => {
          if (!open) setScheduleFor(null)
        }}
        chatTitle={headerTitle}
        pendingCount={scheduledQuery.data?.length ?? 0}
        sending={scheduleSend.isPending}
        onShowPending={() => {
          setScheduleFor(null)
          setScheduledListOpen(true)
        }}
        onSubmit={(whenIso) => {
          const content = (scheduleFor ?? '').trim()
          if (content.length === 0) return
          scheduleSend.mutate({ content, whenIso })
        }}
      />

      {/* scheduled sends manager */}
      <ScheduledListDrawer
        open={scheduledListOpen}
        onOpenChange={setScheduledListOpen}
        items={scheduledQuery.data ?? []}
        loading={scheduledQuery.isPending && !scheduledQuery.data}
        onCancel={(id) => cancelScheduled.mutate(id)}
      />

      {/* thread sheet (Slack/Zulip-style) */}
      <ThreadSheet
        root={threadRoot}
        onClose={() => {
          setThreadDraft('')
          setThreadRoot(null)
        }}
        myId={me.id}
        sending={sendMessage.isPending}
        text={threadDraft}
        onTextChange={setThreadDraft}
        onSend={() => submitThreadReply(threadDraft)}
        onOpenProfile={openProfileForAuthor}
      />

      {/* ── R19 toolkit overlays ───────────────────────────────── */}
      <StickerPicker open={stickerOpen} onOpenChange={setStickerOpen} onPick={sendSticker} />
      <LocationShareSheet
        open={locationOpen}
        onClose={() => setLocationOpen(false)}
        onConfirm={sendLocation}
      />
      <GroupInfoSheet
        open={groupInfoOpen}
        onClose={() => setGroupInfoOpen(false)}
        conversationId={conversationId}
        meId={me.id}
      />

      {/* ── R21-b live voice room (engine keeps running while closed) ── */}
      <AnimatePresence>
        {voiceOpen ? (
          <VoiceRoomSheet
            key="voice-room-sheet"
            title={headerTitle}
            myId={me.id}
            voice={voice}
            onClose={() => setVoiceOpen(false)}
          />
        ) : null}
      </AnimatePresence>
      {/* ── R21-c shared whiteboard (opened via /whiteboard palette entry) ── */}
      {whiteboard.node}
      {/* ── R23: red packets / kanban / events sheets (palette + tray entries) ── */}
      {redPacket.node}
      {kanban.node}
      {events.node}
      {/* ── R24: stage / space / tournament sheets (parallel crews' hooks) ── */}
      {stage.node}
      {space.node}
      {tournament.node}
      {/* R27-c: profile taps route to the global #/user/:id page — the
          in-room UserProfileSheet mount is gone; the sheet component stays
          in use by contacts-tab + group-info-sheet. */}
      <PipChat me={me} />
      {/* full-screen message effects — one canvas, queue upstream, zero pointer events */}
      <div className="pointer-events-none fixed inset-0 z-[80]" aria-hidden>
        <MessageEffectsLayer active={activeEffect} onDone={handleEffectDone} />
      </div>

      {/* slash-command cheat sheet */}
      <Dialog open={helpOpen} onOpenChange={setHelpOpen}>
        <DialogContent className="max-w-[320px] gap-3 rounded-2xl p-4 sm:left-1/2 sm:translate-x-[-50%] dark:bg-zinc-900">
          <DialogHeader className="text-left">
            <DialogTitle className="flex items-center gap-1.5 text-sm font-bold tracking-tight">
              <Dices className="size-4 text-teal-500" aria-hidden />
              Slash commands
            </DialogTitle>
            <DialogDescription className="text-xs">
              Type these at the start of the message box — Discord/Twitch style.
            </DialogDescription>
          </DialogHeader>
          <ul className="space-y-1.5">
            {SLASH_COMMANDS.map((c) => (
              <li key={c.cmd} className="flex items-baseline gap-2 rounded-lg bg-zinc-50 px-2 py-1.5 dark:bg-zinc-800/70">
                <code className="shrink-0 font-mono text-[12px] font-bold text-emerald-700 dark:text-emerald-400">
                  {c.cmd}
                  {c.args ? <span className="font-normal text-zinc-400"> {c.args}</span> : null}
                </code>
                <span className="min-w-0 flex-1 text-right text-[11px] text-zinc-500 dark:text-zinc-400">{c.help}</span>
              </li>
            ))}
          </ul>
          <Button variant="outline" onClick={() => setHelpOpen(false)} className="h-10 rounded-xl text-sm font-medium">
            Got it
          </Button>
        </DialogContent>
      </Dialog>

      {/* forward-message sheet */}
      {forwardMounted && forwardTarget ? (
        <ForwardSheet
          key={forwardGeneration}
          me={me}
          open={forwardOpen}
          onOpenChange={handleForwardClose}
          originId={conversationId}
          payload={{
            content: forwardTarget.content,
            imagePath: forwardTarget.imagePath,
            audioPath: forwardTarget.audioPath,
            durationMs: forwardTarget.durationMs,
          }}
        />
      ) : null}

      {/* info dialog */}
      <InfoDialog
        open={infoOpen}
        onOpenChange={setInfoOpen}
        detail={detailData ?? null}
        me={me}
        onlineIds={realtime.onlineIds}
        renamePending={renameGroup.isPending}
        onRename={(name) => renameGroup.mutate(name)}
        addMembersPending={addMembers.isPending}
        onAddMembers={(userIds) => addMembers.mutate(userIds)}
        leavePending={leaveGroup.isPending}
        onLeave={() => leaveGroup.mutate()}
        setRolePending={setMemberRole.isPending || removeMember.isPending}
        onSetRole={(userId, promote) => setMemberRole.mutate({ userId, promote })}
        onRemoveMember={(userId) => removeMember.mutate(userId)}
        invitePending={inviteLink.isPending}
        onInvite={(regenerate) => inviteLink.mutate(regenerate)}
        broadcastMode={detailData?.broadcastMode ?? false}
        broadcastPending={toggleBroadcast.isPending}
        onToggleBroadcast={onToggleBroadcast}
        onOpenMember={openProfileForUser}
      />
    </motion.div>
  )
}

// ── pieces ───────────────────────────────────────────────────

// ── message action menu (R26-b) — compact frosted glass panel sprouting
// from the tapped bubble. Replaces the old centered "Message options"
// dialog: reactions strip on top, icon rows in hairline-separated groups,
// owner-only rows, destructive delete last. Backdrop tap / Esc dismisses.

interface MessageActionMenuProps {
  message: ChatMessage
  /** viewport point the menu sprouts from (null → center fallback) */
  anchor: { x: number; y: number } | null
  myId: string
  isSaved: boolean
  canDelete: boolean
  savePending: boolean
  taskPending: boolean
  pinPending: boolean
  onClose: () => void
  onReact: (emoji: string, el: Element | null) => void
  onReply: () => void
  onThread: (target: ChatMessage) => void
  onCopy: () => void
  onForward: (target: ChatMessage) => void
  onSave: () => void
  onTask: () => void
  onRemind: () => void
  onPin: () => void
  onInfo: () => void
  onEdit: () => void
  onDelete: () => void
}

function MessageActionMenu({
  message,
  anchor,
  myId,
  isSaved,
  canDelete,
  savePending,
  taskPending,
  pinPending,
  onClose,
  onReact,
  onReply,
  onThread,
  onCopy,
  onForward,
  onSave,
  onTask,
  onRemind,
  onPin,
  onInfo,
  onEdit,
  onDelete,
}: MessageActionMenuProps) {
  const deleted = message.deletedAt !== null
  const mine = message.senderId === myId

  // Viewport clamp: sprout below the tap point, or above it when the lower
  // half is crowded; the row stack scrolls when it exceeds its side's space.
  const geometry = useMemo(() => {
    const vw = window.innerWidth
    const vh = window.innerHeight
    const width = 260
    const gap = 10
    const margin = 8
    const point = anchor ?? { x: vw / 2, y: vh * 0.42 }
    const left = Math.min(
      Math.max(margin, point.x - width / 2),
      Math.max(margin, vw - width - margin),
    )
    const spaceBelow = vh - point.y - gap - margin
    const spaceAbove = point.y - gap - margin
    const openUp = spaceAbove > spaceBelow
    const rowsMax = Math.max(160, Math.min(380, (openUp ? spaceAbove : spaceBelow) - 84))
    return { left, openUp, rowsMax, gap, point, vh }
  }, [anchor])

  // --menu-origin steers the glass panel's spring transform-origin toward the tap.
  const panelStyle: CSSProperties & { '--menu-origin': string } = {
    left: geometry.left,
    ...(geometry.openUp
      ? { bottom: geometry.vh - geometry.point.y + geometry.gap }
      : { top: geometry.point.y + geometry.gap }),
    '--menu-origin': geometry.openUp ? 'bottom left' : 'top left',
  }

  return (
    <>
      {/* backdrop — tap anywhere outside to dismiss */}
      <motion.div
        key="message-menu-backdrop"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.16 }}
        onClick={onClose}
        aria-hidden
        className="fixed inset-0 z-[60] bg-zinc-950/25 backdrop-blur-[2px] dark:bg-black/45"
      />
      <GlassMenu
        key="message-action-panel"
        aria-label="Message actions"
        className="fixed z-[61] w-[260px] max-w-[calc(100vw-16px)]"
        style={panelStyle}
      >
        {!deleted ? (
          <GlassMenuStrip role="group" aria-label="React to this message">
            {REACTION_CHOICES.map((emoji) => {
              const active = message.reactions.some(
                (g) => g.emoji === emoji && g.userIds.includes(myId),
              )
              return (
                <motion.button
                  key={emoji}
                  type="button"
                  aria-label={`React with ${emoji}`}
                  aria-pressed={active}
                  whileTap={{ scale: 0.82 }}
                  transition={pressSpring}
                  onClick={(e) => onReact(emoji, e.currentTarget)}
                  className={cn(
                    'flex size-9 items-center justify-center rounded-full text-xl outline-none transition-colors',
                    active
                      ? 'bg-emerald-500/15 ring-1 ring-inset ring-emerald-400/50'
                      : 'hover:bg-zinc-900/[0.06] dark:hover:bg-white/[0.08]',
                  )}
                >
                  {emoji}
                </motion.button>
              )
            })}
          </GlassMenuStrip>
        ) : (
          <p className="px-3 pb-1.5 pt-2 text-[12px] font-medium italic text-zinc-400 dark:text-zinc-500">
            This message was deleted.
          </p>
        )}

        <div className="pulse-scroll overflow-y-auto" style={{ maxHeight: geometry.rowsMax }}>
          <GlassMenuSeparator />
          <GlassMenuItem icon={Reply} label="Reply" disabled={deleted} onClick={onReply} />
          {message.parentId === null && !deleted ? (
            <GlassMenuItem
              icon={MessageSquare}
              label="Reply in thread"
              onClick={() => onThread(message)}
            />
          ) : null}
          <GlassMenuSeparator />
          <GlassMenuItem icon={Copy} label="Copy text" disabled={deleted} onClick={onCopy} />
          <GlassMenuItem
            icon={Forward}
            label="Forward to chat…"
            disabled={deleted}
            onClick={() => onForward(message)}
          />
          {!deleted ? (
            <GlassMenuItem
              icon={Star}
              label={isSaved ? 'Unsave' : 'Save message'}
              active={isSaved}
              disabled={savePending}
              onClick={onSave}
            />
          ) : null}
          {message.parentId === null && !deleted && message.kind === 'text' ? (
            <GlassMenuItem
              icon={taskPending ? LoaderCircle : ListTodo}
              label="Convert to task"
              disabled={taskPending}
              onClick={onTask}
            />
          ) : null}
          {!deleted ? (
            <GlassMenuItem icon={Bell} label="Remind me" onClick={onRemind} />
          ) : null}
          {!deleted ? (
            <GlassMenuItem
              icon={message.pinnedAt ? PinOff : Pin}
              label={message.pinnedAt ? 'Unpin' : 'Pin'}
              disabled={pinPending}
              onClick={onPin}
            />
          ) : null}
          {mine ? (
            <GlassMenuItem icon={Info} label="Message info" disabled={deleted} onClick={onInfo} />
          ) : null}
          {mine && !deleted && message.audioPath === null ? (
            <GlassMenuItem icon={Pencil} label="Edit message" onClick={onEdit} />
          ) : null}
          <GlassMenuSeparator />
          <GlassMenuItem
            icon={Trash2}
            label="Delete for everyone"
            destructive
            disabled={!canDelete}
            onClick={onDelete}
          />
        </div>
      </GlassMenu>
    </>
  )
}

function TypingDots({ reducedMotion = false }: { reducedMotion?: boolean }) {
  return (
    <span className="inline-flex items-end gap-1 py-0.5" aria-hidden>
      {[0, 1, 2].map((i) =>
        reducedMotion ? (
          <span
            key={i}
            className="size-1.5 rounded-full bg-zinc-400 dark:bg-zinc-500"
            style={{ opacity: 0.55 + i * 0.22 }}
          />
        ) : (
          // Telegram-grade squash & stretch: dots stretch into pills as they
          // rise (velocity), squash wide on landing — transform-only, GPU-friendly.
          <motion.span
            key={i}
            className="size-1.5 origin-bottom rounded-full bg-zinc-400 dark:bg-zinc-500"
            style={{ willChange: 'transform' }}
            animate={{
              y: [0, -4, -4, 0, 0],
              scaleY: [1, 1.55, 1.35, 0.7, 1],
              scaleX: [1, 0.8, 0.9, 1.2, 1],
              opacity: [0.45, 1, 0.95, 0.5, 0.45],
            }}
            transition={{
              repeat: Infinity,
              duration: 0.92,
              delay: i * 0.14,
              ease: 'easeInOut',
              times: [0, 0.38, 0.55, 0.8, 1],
            }}
          />
        ),
      )}
    </span>
  )
}

/** Deterministic decorative waveform bars derived from the message id. */
function voiceBars(seed: string, count = 26): number[] {
  let h = hashString(seed)
  const bars: number[] = []
  for (let i = 0; i < count; i += 1) {
    h = (h * 1103515245 + 12345) % 2147483648
    const v = Math.abs(h) / 2147483648
    bars.push(Math.round(28 + v * 72))
  }
  return bars
}

/** Voice-note bubble: play/pause + pseudo waveform + duration + progress. */
function VoiceBubble({
  src,
  durationMs,
  mine,
  seed,
}: {
  src: string
  durationMs: number | null
  mine: boolean
  seed: string
}) {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const [playing, setPlaying] = useState(false)
  const [progress, setProgress] = useState(0)
  const bars = useMemo(() => voiceBars(seed), [seed])

  const toggle = (event: React.SyntheticEvent) => {
    event.stopPropagation()
    const audio = audioRef.current
    if (!audio) return
    if (playing) {
      audio.pause()
    } else {
      void audio.play().catch(() => {
        toast.error('Could not play this voice note')
      })
    }
  }

  return (
    <div className="flex min-w-[196px] items-center gap-2.5 py-0.5">
      <button
        type="button"
        aria-label={playing ? 'Pause voice note' : 'Play voice note'}
        onClick={toggle}
        className={cn(
          'flex size-9 shrink-0 items-center justify-center rounded-full outline-none transition-transform active:scale-90',
          mine ? 'bg-white/20 text-white hover:bg-white/30' : 'bg-emerald-500 text-white hover:bg-emerald-500/90',
        )}
      >
        {playing ? <Pause className="size-4" aria-hidden /> : <Play className="size-4 translate-x-[1px]" aria-hidden />}
      </button>
      <div className="flex h-7 min-w-0 flex-1 items-center gap-[2.5px]" aria-hidden>
        {bars.map((height, i) => {
            const played = i / bars.length <= progress
            return (
              <span
                key={i}
                style={{ height: `${height}%` }}
                className={cn(
                  'w-[3px] shrink-0 rounded-full transition-colors',
                  played
                    ? mine
                      ? 'bg-white'
                      : 'bg-emerald-500'
                    : mine
                      ? 'bg-white/35'
                      : 'bg-zinc-300 dark:bg-zinc-600',
                )}
              />
            )
          })}
      </div>
      <span
        className={cn(
          'shrink-0 text-[10px] font-semibold tabular-nums',
          mine ? 'text-white/85' : 'text-zinc-400 dark:text-zinc-500',
        )}
      >
        {durationMs !== null ? formatVoicems(durationMs) : '--:--'}
      </span>
      <audio
        ref={audioRef}
        src={src}
        preload="none"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          setPlaying(false)
          setProgress(0)
        }}
        onTimeUpdate={() => {
          const audio = audioRef.current
          if (!audio || !Number.isFinite(audio.duration) || audio.duration <= 0) return
          setProgress(Math.min(1, audio.currentTime / audio.duration))
        }}
        className="hidden"
      />
    </div>
  )
}

interface MessageRowProps {
  message: ChatMessage
  head: boolean
  mine: boolean
  isGroup: boolean
  readMs: number
  myId: string
  /** viewer's display name — drives the mention-me highlight */
  myName: string
  /** member display names (stable ref) — drives @mention chips */
  memberNames: string[]
  /** group read-by stack for the last own message (null otherwise) */
  readBy: { members: Array<{ id: string; name: string; color: string; avatar: string | null }>; all: boolean } | null
  /** search/reply jump flash — ring-pulse this bubble briefly */
  highlighted: boolean
  /** live Slack/Zulip reply count for THIS thread root (0 = none) */
  threadCount: number
  /** tap (or keyboard Enter) on the bubble → anchored action menu; point = pointer coords */
  onPress: (message: ChatMessage, point?: { x: number; y: number }) => void
  onStartLongPress: (message: ChatMessage, point?: { x: number; y: number }) => void
  onEndLongPress: () => void
  onToggleReaction: (messageId: string, emoji: string) => void
  onReply: (message: ChatMessage) => void
  /** long-press a chip → who-reacted sheet */
  onReactionInfo: (message: ChatMessage, emoji: string) => void
  /** open photo lightbox — consumes a view-once gate transparently */
  onOpenImageGated: (message: ChatMessage) => void
  /** tap the quoted block → scroll to the parent message + flash */
  onJumpToReply: (parentMessageId: string) => void
  /** tap the read-by stack → seen-by detail sheet (groups only) */
  onOpenSeenBy: () => void
  /** bubble <img> finished decoding → caller re-anchors scroll */
  onImageLoad: () => void
  /** open this message's thread sheet */
  onOpenThread: (message: ChatMessage) => void
  onVote: (pollId: string, optionId: string) => void
  onClosePoll: (pollId: string) => void
  /** prefs: bubble corner style */
  bubbleRadius: 'md' | 'lg' | 'pill'
  /** prefs: row density */
  density: 'cozy' | 'compact'
  /** tap a sender avatar → open their profile sheet */
  onOpenProfile: (sender: MessageAuthor) => void
  /** spring-entrance for freshly arrived messages (history renders static) */
  justArrived: boolean
  /** prefs.reducedMotion mirror — gates entrance/tap/particle motion */
  reducedMotion: boolean
}

/** Escapes a member name for safe embedding in a RegExp. */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Formatting tokens (WhatsApp/Telegram/Discord-flavored):
 * ```pre``` · `code` · **bold** · *bold* · __underline__ · _italic_ · ~~strike~~ · ~strike~ · ||spoiler||
 * Order matters: multi-char tokens first so ** wins over *.
 */
const FORMAT_RE =
  /```([\s\S]+?)```|`([^`\n]+)`|\*\*([^*\n]+?)\*\*|__([^_\n]+?)__|~~([^~\n]+?)~~|\|\|([^|\n]+?)\|\||\*([^*\n]+?)\*|_([^_\n]+?)_|~([^~\n]+?)~/g

/** Discord-style blur-reveal spoiler — tap once to unmask. */
function SpoilerSpan({ children, mine }: { children: ReactNode; mine: boolean }) {
  const [revealed, setRevealed] = useState(false)
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        if (!revealed) {
          setRevealed(true)
          haptic(8)
        }
      }}
      aria-label={revealed ? undefined : 'Hidden spoiler — tap to reveal'}
      className="inline align-baseline outline-none"
    >
      <span
        className={cn(
          'rounded px-0.5 transition-all duration-300',
          revealed
            ? 'bg-transparent'
            : cn(
                'cursor-pointer select-none blur-[5px]',
                mine ? 'bg-white/25' : 'bg-zinc-500/20 dark:bg-white/25',
              ),
        )}
      >
        {children}
      </span>
    </button>
  )
}

/** Splits text into [plain, @mention, plain, …] runs against real member names. */
function buildMentionRuns(
  content: string,
  memberNames: string[],
): Array<{ text: string; mention: string | null }> {
  if (memberNames.length === 0) return [{ text: content, mention: null }]
  // longest names first so "Alice Chen" wins over a hypothetical "Alice"
  const names = [...memberNames].filter(Boolean).sort((a, b) => b.length - a.length)
  if (names.length === 0) return [{ text: content, mention: null }]
  const re = new RegExp(`@(${names.map(escapeRegExp).join('|')})`, 'gi')
  const runs: Array<{ text: string; mention: string | null }> = []
  let last = 0
  for (const m of content.matchAll(re)) {
    const idx = m.index ?? 0
    if (idx > last) runs.push({ text: content.slice(last, idx), mention: null })
    runs.push({ text: m[0], mention: m[1] })
    last = idx + m[0].length
  }
  if (last < content.length) runs.push({ text: content.slice(last), mention: null })
  return runs.length > 0 ? runs : [{ text: content, mention: null }]
}

/** Bubble body text: URL auto-linking + rich formatting + @mention chips (no HTML injection). */
function BubbleText({
  content,
  mine,
  memberNames,
}: {
  content: string
  mine: boolean
  memberNames: string[]
}) {
  const nodes: ReactNode[] = []
  let key = 0
  let linkKey = 0

  /** renders a plain run: URLs become safe anchors, the rest stays literal */
  const renderPlain = (text: string) => {
    const segments = splitUrlSegments(text)
    return segments.map((seg) =>
      seg.kind === 'url' ? (
        <a
          key={`lnk-${linkKey++}`}
          href={seg.value.startsWith('www.') ? `https://${seg.value}` : seg.value}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => e.stopPropagation()}
          className={cn(
            'underline underline-offset-2',
            mine
              ? 'text-white decoration-white/60 hover:decoration-white'
              : 'text-emerald-700 decoration-emerald-400/60 hover:decoration-emerald-600 dark:text-emerald-400',
          )}
        >
          {seg.value}
        </a>
      ) : (
        <span key={`txt-${linkKey++}`}>{seg.value}</span>
      ),
    )
  }

  for (const run of buildMentionRuns(content, memberNames)) {
    if (run.mention !== null) {
      nodes.push(
        <span
          key={`men-${key++}`}
          className="rounded bg-emerald-500/20 px-1 font-semibold text-emerald-800 dark:bg-emerald-400/25 dark:text-emerald-200"
        >
          @{run.mention}
        </span>,
      )
      continue
    }
    let last = 0
    for (const m of run.text.matchAll(FORMAT_RE)) {
      const idx = m.index ?? 0
      if (idx > last) {
        nodes.push(<span key={`p-${key++}`}>{renderPlain(run.text.slice(last, idx))}</span>)
      }
      const [full, pre, code, boldDouble, underline, strikeDouble, spoiler, boldSingle, italic, strikeSingle] = m
      if (pre !== undefined) {
        nodes.push(
          <span
            key={`pre-${key++}`}
            className={cn(
              'my-0.5 block whitespace-pre-wrap rounded-lg px-2 py-1.5 font-mono text-[12.5px] leading-snug',
              mine ? 'bg-black/20' : 'bg-zinc-100 dark:bg-black/40',
            )}
          >
            {pre}
          </span>,
        )
      } else if (code !== undefined) {
        nodes.push(
          <code
            key={`code-${key++}`}
            className={cn(
              'rounded px-1 py-0.5 font-mono text-[12.5px]',
              mine ? 'bg-black/20' : 'bg-zinc-100 dark:bg-black/40',
            )}
          >
            {code}
          </code>,
        )
      } else if (boldDouble !== undefined || boldSingle !== undefined) {
        nodes.push(
          <strong key={`b-${key++}`} className="font-bold">
            {boldDouble ?? boldSingle}
          </strong>,
        )
      } else if (underline !== undefined) {
        nodes.push(
          <span key={`u-${key++}`} className="underline underline-offset-2">
            {underline}
          </span>,
        )
      } else if (strikeDouble !== undefined || strikeSingle !== undefined) {
        nodes.push(
          <s key={`s-${key++}`} className="opacity-80">
            {strikeDouble ?? strikeSingle}
          </s>,
        )
      } else if (spoiler !== undefined) {
        nodes.push(
          <SpoilerSpan key={`sp-${key++}`} mine={mine}>
            {spoiler}
          </SpoilerSpan>,
        )
      } else if (italic !== undefined) {
        nodes.push(<em key={`i-${key++}`}>{italic}</em>)
      }
      last = idx + full.length
    }
    if (last < run.text.length) {
      nodes.push(<span key={`p-${key++}`}>{renderPlain(run.text.slice(last))}</span>)
    }
  }

  return (
    <p
      className={cn(
        'text-[14px] leading-snug break-words whitespace-pre-wrap',
        mine ? 'text-white' : 'text-zinc-900 dark:text-zinc-100',
      )}
    >
      {nodes}
    </p>
  )
}

/** Live-poll card rendered INSIDE a bubble (Discord-style bars + tallies). */
function PollCard({
  poll,
  mine,
  myId,
  onVote,
  onClose,
}: {
  poll: NonNullable<ChatMessage['poll']>
  mine: boolean
  myId: string
  onVote: (pollId: string, optionId: string) => void
  onClose: (pollId: string) => void
}) {
  const total = Math.max(poll.totalVotes, 0)
  return (
    <div className="min-w-[210px] py-0.5">
      <p
        className={cn(
          'mb-0.5 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider',
          mine ? 'text-white/75' : 'text-emerald-600 dark:text-emerald-400',
        )}
      >
        <Vote className="size-3" aria-hidden />
        {poll.closed ? 'Poll · Final results' : 'Live poll'}
      </p>
      <p className={cn('text-[14px] font-semibold leading-snug', mine ? 'text-white' : 'text-zinc-900 dark:text-zinc-100')}>
        {poll.question}
      </p>
      <div className="mt-1.5 space-y-1" role={poll.closed ? undefined : 'radiogroup'} aria-label="Poll options">
        {poll.options.map((option) => {
          const pct = total > 0 ? Math.round((option.voteCount / total) * 100) : 0
          const picked = option.votedBy.includes(myId)
          return (
            <button
              key={option.id}
              type="button"
              role={poll.closed ? undefined : 'radio'}
              aria-checked={picked || undefined}
              aria-label={`${option.text} — ${option.voteCount} ${option.voteCount === 1 ? 'vote' : 'votes'}`}
              onClick={(e) => {
                e.stopPropagation()
                if (!poll.closed && !picked) onVote(poll.id, option.id)
              }}
              className={cn(
                'relative block w-full overflow-hidden rounded-lg border px-2 py-1.5 text-left outline-none transition-colors',
                mine
                  ? 'border-white/25 hover:bg-white/10'
                  : 'border-zinc-200 hover:border-emerald-300 hover:bg-emerald-500/5 dark:border-zinc-600 dark:hover:border-emerald-500/60 dark:hover:bg-emerald-500/10',
                picked && (mine ? 'border-white bg-black/15' : 'border-emerald-400 bg-emerald-500/10'),
                poll.closed && 'cursor-default',
              )}
            >
              <span
                aria-hidden
                style={{ width: `${pct}%` }}
                className={cn(
                  'absolute inset-y-0 left-0 transition-all duration-500',
                  mine ? 'bg-black/25' : 'bg-emerald-500/15 dark:bg-emerald-400/20',
                )}
              />
              <span className="relative flex items-center justify-between gap-2">
                <span className={cn('flex min-w-0 items-center gap-1 text-[13px]', mine ? 'text-white' : 'text-zinc-800 dark:text-zinc-100')}>
                  <span className={cn('flex size-4 shrink-0 items-center justify-center rounded-full border', picked ? (mine ? 'border-white bg-white text-emerald-600' : 'border-emerald-500 bg-emerald-500 text-white') : mine ? 'border-white/50 text-transparent' : 'border-zinc-400 text-transparent dark:border-zinc-500')}>
                    <Check className="size-2.5" strokeWidth={4} aria-hidden />
                  </span>
                  <span className="truncate font-medium">{option.text}</span>
                </span>
                <span className={cn('shrink-0 text-[11px] font-bold tabular-nums', mine ? 'text-white/85' : 'text-zinc-500 dark:text-zinc-300')}>
                  {pct}%
                </span>
              </span>
            </button>
          )
        })}
      </div>
      <div className="mt-1 flex items-center justify-between gap-2">
        <p className={cn('text-[10px]', mine ? 'text-white/70' : 'text-zinc-400 dark:text-zinc-500')}>
          {total === 0 ? 'No votes yet' : `${total} ${total === 1 ? 'vote' : 'votes'}`} · {poll.closed ? 'closed' : 'tap an option to vote'}
        </p>
        {mine && !poll.closed ? (
          <button
            type="button"
            aria-label="Close this poll"
            onClick={(e) => {
              e.stopPropagation()
              onClose(poll.id)
            }}
            className={cn(
              'rounded-full px-2 py-0.5 text-[10px] font-bold outline-none transition-colors',
              mine ? 'text-white/85 hover:bg-white/15' : 'text-zinc-500 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-700',
            )}
          >
            End
          </button>
        ) : null}
      </div>
    </div>
  )
}

/** Cached Open-Graph link card under link messages. */
function LinkPreviewCard({
  preview,
  mine,
}: {
  preview: NonNullable<ChatMessage['linkPreview']>
  mine: boolean
}) {
  return (
    <a
      href={preview.url.startsWith('www.') ? `https://${preview.url}` : preview.url}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(e) => e.stopPropagation()}
      className={cn(
        'mt-1 block rounded-xl border p-2 outline-none transition-transform active:scale-[0.99]',
        mine ? 'border-white/25 bg-black/15 hover:bg-black/25' : 'border-zinc-200 bg-white hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900/70 dark:hover:bg-zinc-800',
      )}
    >
      {preview.imageUrl ? (
        <img
          src={preview.imageUrl}
          alt={preview.title ?? 'Link preview image'}
          loading="lazy"
          className="mb-1.5 max-h-32 w-full rounded-lg object-cover"
        />
      ) : null}
      <p className={cn('truncate text-[12px] font-bold', mine ? 'text-white' : 'text-zinc-800 dark:text-zinc-100')}>
        {preview.title ?? preview.url}
      </p>
      {preview.description ? (
        <p className={cn('mt-0.5 line-clamp-2 text-[11.5px] leading-snug', mine ? 'text-white/80' : 'text-zinc-500 dark:text-zinc-400')}>
          {preview.description}
        </p>
      ) : null}
      <p className={cn('mt-1 flex items-center gap-1 truncate text-[10px]', mine ? 'text-white/65' : 'text-zinc-400 dark:text-zinc-500')}>
        <Link2 className="size-3 shrink-0" aria-hidden />
        {preview.siteName ?? (() => { try { return new URL(preview.url.startsWith('www.') ? `https://${preview.url}` : preview.url).hostname } catch { return preview.url } })()}
      </p>
    </a>
  )
}
/** Per-message LLM translation — collapsed by default, tap to reveal. */
function TranslationLine({
  translations,
  mine,
}: {
  translations: Array<{ lang: string; text: string }>
  mine: boolean
}) {
  const [open, setOpen] = useState(false)
  const first = translations[0]
  if (!first) return null
  return (
    <div className="mt-1" onClick={(e) => e.stopPropagation()}>
      {!open ? (
        <button
          type="button"
          onClick={() => {
            setOpen(true)
            haptic(6)
          }}
          className={cn(
            'flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-semibold outline-none transition-colors',
            mine
              ? 'bg-white/20 text-white/90 hover:bg-white/30'
              : 'bg-emerald-500/10 text-emerald-700 hover:bg-emerald-500/20 dark:text-emerald-400',
          )}
        >
          <Globe className="size-3" aria-hidden />
          See translation
        </button>
      ) : (
        <p
          className={cn(
            'mt-0.5 rounded-lg border-l-2 px-2 py-1 text-[12.5px] italic leading-snug',
            mine
              ? 'border-white/50 bg-black/15 text-white/90'
              : 'border-emerald-400 bg-emerald-500/5 text-zinc-600 dark:border-emerald-500/70 dark:bg-emerald-500/10 dark:text-zinc-300',
          )}
        >
          {first.text}
        </p>
      )}
    </div>
  )
}

const MessageRow = memo(function MessageRow({
  message,
  head,
  mine,
  isGroup,
  readMs,
  myId,
  myName,
  memberNames,
  readBy,
  highlighted,
  threadCount,
  onPress,
  onStartLongPress,
  onEndLongPress,
  onToggleReaction,
  onReply,
  onReactionInfo,
  onOpenImageGated,
  onJumpToReply,
  onOpenSeenBy,
  onImageLoad,
  onOpenThread,
  onVote,
  onClosePoll,
  bubbleRadius,
  density,
  onOpenProfile,
  justArrived,
  reducedMotion,
}: MessageRowProps) {
  const deleted = message.deletedAt !== null
  const pending = message.id.startsWith('temp-')
  const queued = pending && message._queued === true // held in the offline outbox
  const createdMs = Date.parse(message.createdAt)
  const isRead = !Number.isNaN(createdMs) && createdMs <= readMs
  const interactive = !deleted && !pending
  const jumbo = !deleted && !message.imagePath && !message.audioPath && !message.poll && isJumboEmoji(message.content)
  const hasReactions = message.reactions.length > 0
  const isImage = !deleted && message.imagePath !== null
  const isVoice = !deleted && !isImage && message.audioPath !== null
  const isPoll = !deleted && message.poll !== null
  const isSticker = !deleted && !isImage && !isVoice && message.kind === 'sticker'
  const sticker = isSticker ? parseSticker(message.payload) : null
  const isLocation = !deleted && !isImage && !isVoice && message.kind === 'location'
  const loc = isLocation ? parseLocationPayload(message.payload) : null
  /** R23: red-packet + game cards render as self-contained chat cards */
  const isRedPacket = !deleted && !isImage && !isVoice && message.kind === 'redpacket'
  const redPacketInfo = isRedPacket ? parseRedPacketPayload(message.payload) : null
  const isGame = !deleted && !isImage && !isVoice && message.kind === 'game'
  const gameInfo = isGame ? parseGamePayload(message.payload) : null
  /** R24: tournament bracket cards live in chat like games/red packets */
  const isTournament = !deleted && !isImage && !isVoice && message.kind === 'tournament'
  const tournamentInfo = isTournament ? parseTournamentPayload(message.payload) : null
  /** R24-b: incognito sends wear a neutral zinc mask instead of name/avatar */
  const anonMasked = !deleted && message.anon && message.anonAlias !== null
  const senderLabel =
    anonMasked && message.anonAlias ? message.anonAlias : message.sender.name
  /** jumbo-emoji, stickers and location cards render without bubble chrome */
  const plainChrome = !deleted && (jumbo || isSticker || isLocation || ((isRedPacket && redPacketInfo !== null) || (isGame && gameInfo !== null) || (isTournament && tournamentInfo !== null)))
  /** Snapchat/WhatsApp view-once gates */
  const viewGated = isImage && message.viewOnce && !mine && message.viewedAt === null
  const viewBurned = isImage && message.viewOnce && !mine && message.viewedAt !== null
  const edited = message.editedAt !== null && !deleted
  const pinned = message.pinnedAt !== null && !deleted
  /** someone @mentioned the viewer → amber attention ring (WhatsApp/Telegram-style) */
  const mentionsMe =
    !deleted &&
    myName.length > 0 &&
    message.content.toLowerCase().includes(`@${myName.toLowerCase()}`)
  const dragMovedRef = useRef(false)
  const chipPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const chipFiredRef = useRef(false)

  const beginReply = () => {
    haptic(12)
    onReply(message)
  }

  // Swipe affordance driven by live bubble position: both sides share one
  // signal (offset along the natural direction), so dragging "the wrong way"
  // keeps every hint invisible.
  const bubbleX = useMotionValue(0)
  const towardX = useTransform(bubbleX, (v) => (mine ? -v : v))
  const hintOpacity = useTransform(towardX, [4, 28], [0, 1])
  const hintScale = useTransform(towardX, [4, 44], [0.5, 1.1])

  /** true when a ❤️ double-tap would ADD (not remove) the reaction */
  const doubleTapAddsHeart = !message.reactions.some(
    (g) => g.emoji === '❤️' && g.userIds.includes(myId),
  )

  const openReactionInfo = (emoji: string) => {
    haptic(10)
    onReactionInfo(message, emoji)
  }

  return (
    <div
      data-mid={message.id}
      className={cn(
        'flex w-full scroll-mt-24',
        mine ? 'justify-end' : 'justify-start',
        density === 'compact' ? (head ? 'mt-1' : 'mt-px') : head ? 'mt-2.5' : 'mt-0.5',
      )}
    >
      {!mine && isGroup ? (
        head ? (
          <div className="mr-1.5 flex shrink-0 items-end pb-5">
            {anonMasked ? (
              <span
                role="img"
                aria-label="Anonymous member"
                className="flex size-7 items-center justify-center rounded-full bg-zinc-200 text-zinc-500 dark:bg-zinc-700 dark:text-zinc-300"
              >
                <VenetianMask className="size-4" aria-hidden />
              </span>
            ) : (
              <button
                type="button"
                aria-label={`View ${message.sender.name}'s profile`}
                className="rounded-full outline-none transition-transform active:scale-90"
                onClick={(e) => {
                  e.stopPropagation()
                  onOpenProfile(message.sender)
                }}
              >
                <UserAvatar name={message.sender.name} color={message.sender.color} avatar={message.sender.avatar} size={28} />
              </button>
            )}
          </div>
        ) : (
          <span className="mr-1.5 block w-7 shrink-0" aria-hidden />
        )
      ) : null}

      <div className={cn('flex max-w-[78%] flex-col', mine ? 'items-end' : 'items-start')}>
        {!mine && isGroup && head && !deleted ? (
          <span className="mb-0.5 ml-1 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
            {senderLabel}
          </span>
        ) : null}

        <div className="relative flex w-full">
          {!mine ? (
            <motion.span
              aria-hidden
              initial={false}
              className="absolute left-0 top-1/2 -translate-y-1/2 rounded-full bg-emerald-500/10 p-1 text-emerald-500"
              style={{ opacity: hintOpacity, scale: hintScale, pointerEvents: 'none' }}
            >
              <Reply className="size-4" />
            </motion.span>
          ) : (
            <motion.span
              aria-hidden
              initial={false}
              className="absolute right-0 top-1/2 -translate-y-1/2 rounded-full bg-emerald-500/10 p-1 text-emerald-500"
              style={{ opacity: hintOpacity, scale: hintScale, pointerEvents: 'none' }}
            >
              <Reply className="size-4" />
            </motion.span>
          )}
          <motion.div
          initial={
            justArrived && !reducedMotion
              ? mine
                ? { opacity: 0, scaleX: 1.06, scaleY: 0.94, y: 6 } // Telegram-style squash & stretch on land
                : { opacity: 0, scale: 0.85, y: 8 } // incoming pop
              : false
          }
          animate={justArrived && !reducedMotion ? { opacity: 1, scaleX: 1, scaleY: 1, y: 0 } : undefined}
          transition={spring.bouncy}
          style={{ transformOrigin: mine ? '100% 100%' : '0% 100%' }}
          className="min-w-0"
        >
          <motion.div
          drag="x"
          dragConstraints={{ left: -64, right: 64 }}
          dragElastic={0.12}
          dragDirectionLock
          dragSnapToOrigin
          whileTap={interactive && !reducedMotion ? { scale: 0.97 } : undefined}
          transition={spring.snappy}
          style={{ x: bubbleX }}
          onDragStart={() => {
            dragMovedRef.current = false
            onEndLongPress()
          }}
          onDragEnd={(_e, info) => {
            const toward = mine ? -info.offset.x : info.offset.x
            if (toward > 28) {
              dragMovedRef.current = true
              beginReply()
            }
          }}
          onClick={(e) => {
            if (dragMovedRef.current) {
              dragMovedRef.current = false
              return
            }
            // R23: clicks inside self-contained cards (red packet, game board)
            // belong to the card — never open the message-options sheet
            if (e.target instanceof Element && e.target.closest('[data-card-interactive]')) return
            if (interactive && !isImage) onPress(message, { x: e.clientX, y: e.clientY })
          }}
          onPointerDown={(e) => {
            if (interactive) onStartLongPress(message, { x: e.clientX, y: e.clientY })
          }}
          onPointerUp={onEndLongPress}
          onPointerLeave={onEndLongPress}
          onDoubleClick={(e) => {
            if (interactive) {
              if (doubleTapAddsHeart && !reducedMotion) fireParticlesAt(e.currentTarget, 'hearts', 28)
              onToggleReaction(message.id, '❤️')
            }
          }}
          role={interactive && !isImage ? 'button' : undefined}
          tabIndex={interactive && !isImage ? 0 : undefined}
          onKeyDown={(event) => {
            if (!(event.target instanceof Element && event.target.closest('[data-card-interactive]')) && interactive && !isImage && event.key === 'Enter') onPress(message)
          }}
          className={cn(
            'relative select-none',
            highlighted && !deleted && 'animate-[pulse-message-flash_1.5s_ease-out_1]',
            plainChrome
              ? 'px-1 py-0.5'
              : isPoll || (isImage && !viewBurned)
                ? `${BUBBLE_RADIUS[bubbleRadius]} p-1 shadow-sm`
                : isVoice
                  ? `${BUBBLE_RADIUS[bubbleRadius]} px-2.5 py-2 shadow-sm`
                  : `${BUBBLE_RADIUS[bubbleRadius]} px-3 py-2 shadow-sm`,
            deleted &&
              cn(
                'border border-dashed italic',
                'rounded-2xl border-zinc-300 bg-transparent text-zinc-400 dark:border-zinc-600 dark:text-zinc-500',
                mine ? 'rounded-br-md opacity-80' : 'rounded-bl-md',
              ),
            !deleted && !plainChrome && (mine
              ? cn(
                  `${BUBBLE_RADIUS[bubbleRadius]} rounded-br-md bg-emerald-500 text-white`,
                  queued && 'ring-1 ring-inset ring-white/40 opacity-95', // queued: dashed-feel cue
                  mentionsMe && 'ring-2 ring-inset ring-amber-300/80', // you were mentioned
                )
              : cn(
                  'rounded-2xl rounded-bl-md border bg-white text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100',
                  mentionsMe
                    ? 'border-amber-400/70 ring-2 ring-inset ring-amber-300/60 dark:border-amber-400/60'
                    : 'border-zinc-100 dark:border-zinc-700',
                )),
            interactive
              ? cn(
                  'cursor-pointer focus-visible:ring-2 focus-visible:ring-emerald-500/50 active:brightness-95',
                  jumbo && 'rounded-2xl',
                )
              : '',
          )}
        >
          {deleted ? (
            <p className="text-[13px] leading-snug">This message was deleted</p>
          ) : (
            <>
              {message.replyTo ? (
                <button
                  type="button"
                  aria-label={
                    message.replyTo.deleted
                      ? 'Original message was deleted'
                      : 'Jump to quoted message'
                  }
                  disabled={message.replyTo.deleted}
                  onClick={(e) => {
                    e.stopPropagation()
                    const parent = message.replyTo
                    if (parent && !parent.deleted) {
                      haptic(8)
                      onJumpToReply(parent.id)
                    }
                  }}
                  className={cn(
                    'mb-1 block w-full rounded-md border-l-[3px] px-2 py-1 text-left outline-none transition-colors',
                    mine
                      ? 'border-white/70 bg-black/10 hover:bg-black/15'
                      : 'border-emerald-400 bg-zinc-100 hover:bg-zinc-200/70 dark:border-emerald-500/80 dark:bg-zinc-700/60 dark:hover:bg-zinc-700',
                    message.replyTo.deleted ? '' : 'cursor-pointer active:scale-[0.99]',
                  )}
                >
                  <p
                    className={cn(
                      'text-[11px] font-bold',
                      mine ? 'text-white/90' : 'text-emerald-700 dark:text-emerald-400',
                    )}
                  >
                    {message.replyTo.deleted
                      ? 'Deleted message'
                      : message.replyTo.senderName === message.sender.name
                        ? message.replyTo.senderName
                        : message.replyTo.senderName || 'Unknown'}
                  </p>
                  <p
                    className={cn(
                      'truncate text-[12px] leading-snug',
                      mine ? 'text-white/75' : 'text-zinc-500 dark:text-zinc-400',
                    )}
                  >
                    {message.replyTo.deleted
                      ? 'This message was deleted'
                      : message.replyTo.content.replace(/\s+/g, ' ').slice(0, 120)}
                  </p>
                </button>
              ) : null}
              {isPoll && message.poll ? (
                <PollCard
                  poll={message.poll}
                  mine={mine}
                  myId={myId}
                  onVote={onVote}
                  onClose={(pollId) => onClosePoll(pollId)}
                />
              ) : isImage ? (
                <>
                  {viewBurned ? (
                    <div
                      aria-label="View-once photo already opened"
                      className={cn(
                        'flex h-[168px] w-[220px] items-center justify-center gap-2 rounded-xl border border-dashed text-xs font-semibold',
                        mine ? 'border-white/40 text-white/85' : 'border-zinc-300 bg-zinc-100/70 text-zinc-500 dark:border-zinc-600 dark:bg-zinc-800/60 dark:text-zinc-400',
                      )}
                    >
                      <EyeOff className="size-4" aria-hidden />
                      Photo opened · gone forever
                    </div>
                  ) : (
                    <button
                      type="button"
                      aria-label={viewGated ? 'Tap to view this photo once' : 'Open photo'}
                      onClick={(e) => {
                        e.stopPropagation()
                        if (message.imagePath) onOpenImageGated(message)
                      }}
                      className="relative block overflow-hidden rounded-xl outline-none"
                    >
                      <img
                        src={`/api/uploads/${encodeURIComponent(message.imagePath as string)}`}
                        alt="Shared photo"
                        loading="lazy"
                        onLoad={onImageLoad}
                        className={cn(
                          'block max-h-[300px] w-auto max-w-full rounded-xl object-cover transition-transform active:scale-[0.985]',
                          pending && 'opacity-80',
                          viewGated && 'blur-2xl brightness-75 select-none',
                        )}
                      />
                      {viewGated ? (
                        <span className="absolute inset-0 flex flex-col items-center justify-center gap-1.5 text-white">
                          <EyeOff className="size-6 drop-shadow" aria-hidden />
                          <span className="rounded-full bg-black/55 px-3 py-1 text-[11px] font-bold backdrop-blur-sm">
                            Tap to view once
                          </span>
                          <span className="text-[9px] font-medium opacity-80">it disappears after opening</span>
                        </span>
                      ) : null}
                    </button>
                  )}
                  {message.content.trim().length > 0 ? (
                    <div className="px-0.5 pb-0.5">
                      <BubbleText content={message.content} mine={mine} memberNames={memberNames} />
                    </div>
                  ) : null}
                </>
              ) : isVoice && message.audioPath ? (
                <VoiceBubble
                  src={`/api/uploads/${encodeURIComponent(message.audioPath)}`}
                  durationMs={message.durationMs}
                  mine={mine}
                  seed={message.id}
                />
              ) : isSticker && sticker ? (
                <div
                  role="img"
                  aria-label={`Sticker ${sticker.emoji} from the ${sticker.pack} pack`}
                  className={cn(
                    'flex size-24 items-center justify-center rounded-3xl text-5xl shadow-md ring-1 ring-black/5 transition-transform active:scale-95',
                    stickerGradient(sticker.pack),
                  )}
                >
                  {sticker.emoji}
                </div>
              ) : isLocation && loc ? (
                <LocationBubble lat={loc.lat} lng={loc.lng} label={loc.label} mine={mine} />
              ) : isRedPacket && redPacketInfo ? (
                <div data-card-interactive className="w-56 sm:w-60">
                  <RedPacketBubble
                    packetId={redPacketInfo.packetId}
                    meId={myId}
                    mine={mine}
                    senderName={message.sender.name}
                  />
                </div>
              ) : isGame && gameInfo ? (
                <div data-card-interactive className="w-full max-w-[300px]">
                  <GameTicTacToeCard matchId={gameInfo.matchId} meId={myId} />
                </div>
              ) : isTournament && tournamentInfo ? (
                <div data-card-interactive className="w-full max-w-[300px]">
                  <TournamentCard tournamentId={tournamentInfo.tournamentId} meId={myId} />
                </div>
              ) : jumbo ? (
                <p className="text-[34px] leading-[1.2] break-words">{message.content}</p>
              ) : (
                <BubbleText content={message.content} mine={mine} memberNames={memberNames} />
              )}
              {!isPoll && message.linkPreview && !deleted ? (
                <LinkPreviewCard preview={message.linkPreview} mine={mine} />
              ) : null}
              {!mine && !deleted && message.translations.length > 0 ? (
                <TranslationLine translations={message.translations} mine={mine} />
              ) : null}
            </>
          )}
          <div
            className={cn(
              'mt-0.5 flex items-center justify-end gap-1 text-[10px]',
              deleted
                ? 'text-zinc-400 dark:text-zinc-500'
                : mine && !jumbo
                  ? 'text-white/80'
                  : 'text-zinc-400 dark:text-zinc-500',
            )}
          >
            <span className={jumbo ? 'opacity-70' : undefined}>{formatTime(message.createdAt)}</span>
            {message.expiresAt && !deleted ? (
              <Timer
                className="size-3 animate-pulse opacity-80"
                aria-label={`disappears at ${formatListStamp(message.expiresAt)}`}
              />
            ) : null}
            {pinned ? <Pin className="size-3 rotate-45 opacity-80" aria-label="pinned" /> : null}
            {edited ? (
              <span className="italic opacity-80" aria-label="message was edited">
                edited
              </span>
            ) : null}
            {mine && !deleted ? (
              queued ? (
                <CloudOff className="size-3 text-amber-200" aria-label="queued — sends when online" />
              ) : pending ? (
                <Clock className="size-3 opacity-90" aria-label="sending…" />
              ) : isRead ? (
                <CheckCheck className="size-3.5 text-white/90" aria-label="read" />
              ) : (
                <Check className="size-3 text-white/60" aria-label="sent" />
              )
            ) : null}
          </div>
        </motion.div>
        </motion.div>
        </div>

        {hasReactions && !deleted ? (
          <motion.div
            initial={{ opacity: 0, scale: 0.7, y: -2 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            transition={{ type: 'spring', stiffness: 500, damping: 24 }}
            className={cn(
              '-mt-1.5 z-10 flex flex-wrap gap-1',
              mine ? 'mr-2 justify-end' : 'ml-2 justify-start',
            )}
          >
            {message.reactions.map((group) => {
              const iReacted = group.userIds.includes(myId)
              return (
                <button
                  key={group.emoji}
                  type="button"
                  aria-label={`${group.emoji} ${group.count} — tap to toggle, hold for details`}
                  onClick={(e) => {
                    if (!chipFiredRef.current) {
                      // R22: hearts burst from the chip when a reaction is added
                      if (!iReacted && !reducedMotion) fireParticlesAt(e.currentTarget, 'hearts', 24)
                      onToggleReaction(message.id, group.emoji)
                    }
                    chipFiredRef.current = false
                  }}
                  onPointerDown={() => {
                    if (chipPressRef.current !== null) clearTimeout(chipPressRef.current)
                    chipFiredRef.current = false
                    chipPressRef.current = setTimeout(() => {
                      chipFiredRef.current = true
                      chipPressRef.current = null
                      openReactionInfo(group.emoji)
                    }, 380)
                  }}
                  onPointerUp={() => {
                    if (chipPressRef.current !== null) {
                      clearTimeout(chipPressRef.current)
                      chipPressRef.current = null
                    }
                  }}
                  onPointerLeave={() => {
                    if (chipPressRef.current !== null) {
                      clearTimeout(chipPressRef.current)
                      chipPressRef.current = null
                    }
                  }}
                  className={cn(
                    'flex items-center gap-0.5 rounded-full border px-1.5 py-0.5 text-[11px] shadow-sm backdrop-blur transition-transform active:scale-90',
                    iReacted
                      ? 'border-emerald-400 bg-emerald-50 dark:border-emerald-500/70 dark:bg-emerald-500/15'
                      : 'border-zinc-200 bg-white/95 dark:border-zinc-600 dark:bg-zinc-800/95',
                  )}
                >
                  <span className="text-xs leading-none">{group.emoji}</span>
                  {group.count > 1 ? (
                    <motion.span
                      key={`${group.count}-${iReacted}`}
                      initial={reducedMotion ? false : { scale: 1.45, opacity: 0.5 }}
                      animate={{ scale: 1, opacity: 1 }}
                      transition={spring.bouncy}
                      className={cn(
                        'font-semibold',
                        iReacted
                          ? 'text-emerald-700 dark:text-emerald-300'
                          : 'text-zinc-500 dark:text-zinc-300',
                      )}
                    >
                      {group.count}
                    </motion.span>
                  ) : null}
                </button>
              )
            })}
          </motion.div>
        ) : null}

        {threadCount > 0 && !deleted ? (
          <motion.button
            type="button"
            initial={{ opacity: 0, y: 2 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.16 }}
            onClick={(e) => {
              e.stopPropagation()
              haptic(8)
              onOpenThread(message)
            }}
            aria-label={`Open thread — ${threadCount} ${threadCount === 1 ? 'reply' : 'replies'}`}
            className={cn(
              'mt-0.5 flex max-w-[78%] items-center gap-1 rounded-full border bg-white/95 px-2 py-0.5 text-[10.5px] font-semibold shadow-sm outline-none transition-colors active:scale-95',
              mine
                ? 'mr-auto ml-0 border-emerald-200 text-emerald-700 hover:bg-emerald-50 dark:border-emerald-500/40 dark:text-emerald-400 dark:hover:bg-emerald-500/10'
                : 'ml-auto mr-0 border-emerald-200 text-emerald-700 hover:bg-emerald-50 dark:border-emerald-500/40 dark:text-emerald-400 dark:hover:bg-emerald-500/10',
            )}
          >
            <CornerDownRight className="size-3" aria-hidden />
            {threadCount} {threadCount === 1 ? 'reply' : 'replies'}
          </motion.button>
        ) : null}

        {mine && !deleted && !pending && isGroup && readBy !== null && readBy.members.length > 0 ? (
          <motion.div
            initial={{ opacity: 0, y: 2 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18 }}
            className="mt-0.5 flex justify-end pr-1"
          >
            <button
              type="button"
              onClick={onOpenSeenBy}
              aria-label={
                readBy.all ? 'Seen by everyone — show details' : `Read by ${readBy.members.length} — show details`
              }
              className="flex items-center gap-1.5 rounded-full px-1.5 py-0.5 outline-none transition-colors hover:bg-zinc-100/80 active:scale-95 dark:hover:bg-zinc-800/80"
            >
              <span className="text-[10px] font-medium text-zinc-400 transition-colors hover:text-zinc-500 dark:text-zinc-500 dark:hover:text-zinc-400">
                {readBy.all ? 'Seen' : `Read by ${readBy.members.length}`}
              </span>
              <span className="flex -space-x-1.5">
                {readBy.members.map((member) => (
                  <span
                    key={member.id}
                    className="overflow-hidden rounded-full ring-2 ring-zinc-50 dark:ring-zinc-900"
                  >
                    <UserAvatar name={member.name} color={member.color} avatar={member.avatar} size={14} />
                  </span>
                ))}
              </span>
            </button>
          </motion.div>
        ) : null}
      </div>
    </div>
  )
}, rowsEqual)

function rowsEqual(prev: MessageRowProps, next: MessageRowProps): boolean {
  if (prev.message !== next.message) return false
  if (
    prev.memberNames.length !== next.memberNames.length ||
    prev.memberNames.some((n, i) => n !== next.memberNames[i])
  ) {
    return false
  }
  return (
    prev.head === next.head &&
    prev.mine === next.mine &&
    prev.isGroup === next.isGroup &&
    prev.readMs === next.readMs &&
    prev.myId === next.myId &&
    prev.myName === next.myName &&
    prev.threadCount === next.threadCount &&
    prev.readBy === next.readBy &&
    prev.highlighted === next.highlighted &&
    prev.onPress === next.onPress &&
    prev.onStartLongPress === next.onStartLongPress &&
    prev.onEndLongPress === next.onEndLongPress &&
    prev.onToggleReaction === next.onToggleReaction &&
    prev.onReply === next.onReply &&
    prev.onReactionInfo === next.onReactionInfo &&
    prev.onOpenImageGated === next.onOpenImageGated &&
    prev.onJumpToReply === next.onJumpToReply &&
    prev.onOpenSeenBy === next.onOpenSeenBy &&
    prev.onImageLoad === next.onImageLoad &&
    prev.onOpenThread === next.onOpenThread &&
    prev.onVote === next.onVote &&
    prev.onClosePoll === next.onClosePoll &&
    prev.onOpenProfile === next.onOpenProfile &&
    prev.justArrived === next.justArrived &&
    prev.reducedMotion === next.reducedMotion
  )
}

function InfoDialog({
  open,
  onOpenChange,
  detail,
  me,
  onlineIds,
  renamePending,
  onRename,
  addMembersPending,
  onAddMembers,
  leavePending,
  onLeave,
  setRolePending,
  onSetRole,
  onRemoveMember,
  invitePending,
  onInvite,
  broadcastMode,
  broadcastPending,
  onToggleBroadcast,
  onOpenMember,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  detail: ConversationDetail | null
  me: AppUser
  onlineIds: ReadonlySet<string>
  renamePending: boolean
  onRename: (name: string) => void
  addMembersPending: boolean
  onAddMembers: (userIds: string[]) => void
  leavePending: boolean
  onLeave: () => void
  setRolePending: boolean
  onSetRole: (userId: string, promote: boolean) => void
  onRemoveMember: (userId: string) => void
  invitePending: boolean
  onInvite: (regenerate: boolean) => void
  /** announcement mode state + admin toggle */
  broadcastMode: boolean
  broadcastPending: boolean
  onToggleBroadcast: (broadcast: boolean) => void
  /** member avatar/name tap → other-user profile sheet (R26-b) */
  onOpenMember: (member: AppUser) => void
}) {
  // group-management local state (all resets happen in event handlers)
  const [editingName, setEditingName] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickedIds, setPickedIds] = useState<string[]>([])
  const [confirmingLeave, setConfirmingLeave] = useState(false)
  const [pendingRemoval, setPendingRemoval] = useState<string | null>(null)

  const usersQuery = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<{ users: AppUser[] }>('/api/users')
      return res.users
    },
    enabled: open && pickerOpen,
    staleTime: 10_000,
  })

  if (!detail) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="max-w-[320px] rounded-2xl sm:left-1/2 sm:translate-x-[-50%]">
          <DialogTitle className="sr-only">Chat info</DialogTitle>
          <DialogDescription className="sr-only">Loading chat details…</DialogDescription>
          <Skeleton className="h-40 w-full rounded-xl" />
        </DialogContent>
      </Dialog>
    )
  }
  const title = detail.isGroup
    ? detail.name?.trim() || 'Group'
    : otherMemberOf(detail, me.id)?.name ?? 'Direct message'

  const myRole = detail.members.find((m) => m.id === me.id)?.role ?? 'member'
  const isAdmin = myRole === 'admin'
  const adminCount = detail.members.filter((m) => m.role === 'admin').length
  const pendingRemovalTarget = detail.members.find((m) => m.id === pendingRemoval) ?? null

  const memberIds = new Set(detail.members.map((m) => m.id))
  const addableUsers = (usersQuery.data ?? []).filter((u) => !memberIds.has(u.id))

  const copyInvite = async () => {
    if (!detail.inviteCode) return
    const link = `${window.location.origin}/?join=${detail.inviteCode}`
    try {
      await navigator.clipboard.writeText(link)
      toast.success('Invite link copied to clipboard')
    } catch {
      toast.error('Could not copy the link')
    }
  }

  const closeDialog = () => {
    onOpenChange(false)
    setEditingName(false)
    setPickerOpen(false)
    setPickedIds([])
    setConfirmingLeave(false)
    setPendingRemoval(null)
  }

  return (
    <>
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : closeDialog())}>
      <DialogContent className="max-w-[340px] gap-4 rounded-2xl p-4 sm:left-1/2 sm:translate-x-[-50%] dark:bg-zinc-900">
        <DialogHeader>
          <div className="flex items-center gap-3">
            {detail.isGroup ? (
              <GroupAvatar title={title} id={detail.id} size={44} />
            ) : (
              (() => {
                const o = otherMemberOf(detail, me.id)
                return (
                  <button
                    type="button"
                    aria-label={o ? `View ${o.name}'s profile` : 'Show info'}
                    onClick={() => {
                      if (o) onOpenMember(o)
                    }}
                    className="shrink-0 rounded-full outline-none transition-transform duration-150 active:scale-90"
                  >
                    <UserAvatar
                      name={o?.name ?? title}
                      color={o?.color}
                      avatar={o?.avatar}
                      size={44}
                      showPresence
                      online={o ? onlineIds.has(o.id) : false}
                    />
                  </button>
                )
              })()
            )}
            <div className="min-w-0 flex-1 text-left">
              {detail.isGroup && editingName ? (
                <div className="flex items-center gap-1.5">
                  <input
                    autoFocus
                    value={nameDraft}
                    maxLength={48}
                    aria-label="Group name"
                    onChange={(e) => setNameDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && nameDraft.trim().length > 0 && !renamePending) {
                        onRename(nameDraft.trim())
                        setEditingName(false)
                      }
                      if (e.key === 'Escape') setEditingName(false)
                    }}
                    className="h-8 min-w-0 flex-1 rounded-lg border border-emerald-300 bg-white px-2 text-sm font-semibold outline-none focus:border-emerald-500 dark:border-emerald-500/50 dark:bg-zinc-800 dark:text-zinc-100"
                  />
                  <button
                    type="button"
                    aria-label="Save group name"
                    disabled={nameDraft.trim().length === 0 || renamePending}
                    onClick={() => {
                      onRename(nameDraft.trim())
                      setEditingName(false)
                    }}
                    className="rounded-lg bg-emerald-500 p-1.5 text-white outline-none transition-transform hover:bg-emerald-500/90 active:scale-90 disabled:opacity-50"
                  >
                    <Check className="size-3.5" aria-hidden />
                  </button>
                  <button
                    type="button"
                    aria-label="Cancel renaming"
                    onClick={() => setEditingName(false)}
                    className="rounded-lg p-1.5 text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800"
                  >
                    <X className="size-3.5" aria-hidden />
                  </button>
                </div>
              ) : (
                <div className="flex items-center gap-1">
                  <DialogTitle className="truncate text-base font-bold tracking-tight">{title}</DialogTitle>
                  {detail.isGroup && isAdmin && !pickerOpen ? (
                    <button
                      type="button"
                      aria-label="Rename group"
                      title="Only admins can rename this group"
                      onClick={() => {
                        setNameDraft(detail.name?.trim() ?? '')
                        setEditingName(true)
                      }}
                      className="rounded-md p-1 text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-emerald-600 active:scale-90 dark:hover:bg-zinc-800 dark:hover:text-emerald-400"
                    >
                      <Pencil className="size-3.5" aria-hidden />
                    </button>
                  ) : null}
                </div>
              )}
              <DialogDescription className="text-xs">
                {detail.isGroup
                  ? `${detail.members.length} member${detail.members.length === 1 ? '' : 's'} · ${adminCount} admin${adminCount === 1 ? '' : 's'}`
                  : 'Direct conversation'}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        {detail.isGroup && pickerOpen ? (
          <div className="space-y-2">
            <p className="px-0.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
              Tap people to add them to {title}.
            </p>
            <ul className="pulse-scroll max-h-64 space-y-1 overflow-y-auto pr-1">
              {usersQuery.isPending ? (
                <li className="flex justify-center py-6">
                  <LoaderCircle className="size-5 animate-spin text-zinc-400" aria-hidden />
                </li>
              ) : addableUsers.length === 0 ? (
                <li className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
                  Everyone on Pulse is already here.
                </li>
              ) : (
                addableUsers.map((user) => {
                  const picked = pickedIds.includes(user.id)
                  return (
                    <li key={user.id}>
                      <button
                        type="button"
                        role="checkbox"
                        aria-checked={picked}
                        onClick={() =>
                          setPickedIds((prev) =>
                            prev.includes(user.id)
                              ? prev.filter((id) => id !== user.id)
                              : [...prev, user.id],
                          )
                        }
                        className={cn(
                          'flex w-full items-center gap-3 rounded-xl p-2.5 text-left outline-none transition-colors',
                          picked
                            ? 'bg-emerald-500/10 ring-1 ring-emerald-400/60'
                            : 'bg-zinc-50 hover:bg-zinc-100 dark:bg-zinc-800/60 dark:hover:bg-zinc-800',
                        )}
                      >
                        <UserAvatar name={user.name} color={user.color} size={38} />
                        <span className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                          {user.name}
                        </span>
                        <span
                          className={cn(
                            'flex size-5 shrink-0 items-center justify-center rounded-full border-2 transition-colors',
                            picked
                              ? 'border-emerald-500 bg-emerald-500 text-white'
                              : 'border-zinc-300 dark:border-zinc-600',
                          )}
                          aria-hidden
                        >
                          {picked ? <Check className="size-3" /> : null}
                        </span>
                      </button>
                    </li>
                  )
                })
              )}
            </ul>
            <div className="flex gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  setPickerOpen(false)
                  setPickedIds([])
                }}
                className="h-10 flex-1 rounded-xl text-sm font-medium"
              >
                Cancel
              </Button>
              <Button
                disabled={pickedIds.length === 0 || addMembersPending}
                onClick={() => {
                  onAddMembers(pickedIds)
                  setPickerOpen(false)
                  setPickedIds([])
                }}
                className="h-10 flex-1 gap-1.5 rounded-xl bg-emerald-500 text-sm font-semibold text-white hover:bg-emerald-500/90"
              >
                {addMembersPending ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <UserPlus className="size-4" aria-hidden />
                )}
                Add {pickedIds.length > 0 ? pickedIds.length : ''}
              </Button>
            </div>
          </div>
        ) : (
          <>
            <ul className="pulse-scroll max-h-64 space-y-1 overflow-y-auto pr-1">
              {detail.members.map((member) => {
                const isMe = member.id === me.id
                const online = onlineIds.has(member.id)
                return (
                  <li
                    key={member.id}
                    className="flex items-start gap-3 rounded-xl bg-zinc-50 p-2.5 dark:bg-zinc-800/60"
                  >
                    <button
                      type="button"
                      aria-label={`View ${member.name}'s profile`}
                      onClick={() => onOpenMember(member)}
                      className="shrink-0 rounded-full outline-none transition-transform duration-150 active:scale-90"
                    >
                      <UserAvatar name={member.name} color={member.color} avatar={member.avatar} size={38} showPresence online={online} />
                    </button>
                    <div className="min-w-0 flex-1">
                      <div className="flex min-w-0 items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => onOpenMember(member)}
                          className="min-w-0 truncate text-sm font-medium text-zinc-900 outline-none transition-colors hover:text-emerald-600 dark:text-zinc-100 dark:hover:text-emerald-400"
                        >
                          {member.name}
                          {isMe ? <span className="ml-1 text-xs font-normal text-zinc-400">(you)</span> : null}
                        </button>
                        {detail.isGroup && member.role === 'admin' ? (
                          <span
                            aria-label={`${member.role === 'admin' ? member.name : ''} is a group admin`}
                            className="flex shrink-0 items-center gap-0.5 rounded-full bg-emerald-500/15 px-1.5 py-px text-[9px] font-bold uppercase tracking-wider text-emerald-600 dark:text-emerald-400"
                          >
                            <Crown className="size-2.5" aria-hidden />
                            Admin
                          </span>
                        ) : null}
                      </div>
                      <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">{member.about}</p>
                      {isAdmin && !isMe ? (
                        <div className="mt-1.5 flex items-center gap-1.5" role="group" aria-label={`Manage ${member.name}`}>
                          {member.role === 'admin' ? (
                            <button
                              type="button"
                              disabled={setRolePending}
                              onClick={() => onSetRole(member.id, false)}
                              aria-label={`Demote ${member.name} to member`}
                              title="Demote to member"
                              className="inline-flex h-6 items-center gap-1 rounded-md border border-zinc-200 bg-white px-1.5 text-[10px] font-semibold text-zinc-500 outline-none transition-colors hover:border-amber-300 hover:bg-amber-50 hover:text-amber-600 active:scale-95 disabled:opacity-50 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-400 dark:hover:border-amber-500/40 dark:hover:bg-amber-950/40 dark:hover:text-amber-400"
                            >
                              <Crown className="size-3" aria-hidden />
                              Demote
                            </button>
                          ) : (
                            <button
                              type="button"
                              disabled={setRolePending}
                              onClick={() => onSetRole(member.id, true)}
                              aria-label={`Promote ${member.name} to admin`}
                              title="Promote to admin"
                              className="inline-flex h-6 items-center gap-1 rounded-md border border-zinc-200 bg-white px-1.5 text-[10px] font-semibold text-zinc-500 outline-none transition-colors hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-600 active:scale-95 disabled:opacity-50 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-400 dark:hover:border-emerald-500/40 dark:hover:bg-emerald-950/40 dark:hover:text-emerald-400"
                            >
                              <Crown className="size-3" aria-hidden />
                              Promote
                            </button>
                          )}
                          <button
                            type="button"
                            disabled={setRolePending}
                            onClick={() => setPendingRemoval(member.id)}
                            aria-label={`Remove ${member.name} from the group`}
                            title="Remove from group"
                            className="inline-flex h-6 items-center gap-1 rounded-md border border-zinc-200 bg-white px-1.5 text-[10px] font-semibold text-zinc-500 outline-none transition-colors hover:border-destructive/40 hover:bg-destructive/10 hover:text-destructive active:scale-95 disabled:opacity-50 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-400"
                          >
                            <UserRoundMinus className="size-3" aria-hidden />
                            Remove
                          </button>
                        </div>
                      ) : null}
                    </div>
                    <span className="shrink-0 pt-1 text-right text-[10px] leading-tight text-zinc-400 dark:text-zinc-500">
                      {isMe ? (
                        <>
                          all caught up<br />
                          <span className="font-semibold text-zinc-500 dark:text-zinc-400">
                            read {formatListStamp(member.lastReadAt)}
                          </span>
                        </>
                      ) : (
                        <>
                          last read<br />
                          <span className="font-semibold text-zinc-500 dark:text-zinc-400">
                            {formatListStamp(member.lastReadAt)}
                          </span>
                        </>
                      )}
                    </span>
                  </li>
                )
              })}
            </ul>
            {detail.isGroup ? (
              <div className="flex flex-col gap-1.5">
                {isAdmin ? (
                  <button
                    type="button"
                    role="switch"
                    aria-checked={broadcastMode}
                    disabled={broadcastPending}
                    onClick={() => onToggleBroadcast(!broadcastMode)}
                    className={cn(
                      'flex w-full items-center gap-3 rounded-xl border p-3 text-left outline-none transition-colors disabled:opacity-60',
                      broadcastMode
                        ? 'border-emerald-400 bg-emerald-500/10'
                        : 'border-zinc-200 hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800/60',
                    )}
                  >
                    <span className={cn('flex size-8 shrink-0 items-center justify-center rounded-lg', broadcastMode ? 'bg-emerald-500 text-white' : 'bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-300')}>
                      <Megaphone className="size-4" aria-hidden />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Announcement mode</span>
                      <span className="mt-0.5 block text-[11px] leading-snug text-zinc-500 dark:text-zinc-400">
                        {broadcastMode ? 'Only admins can send — everyone else reads' : 'Everyone can post messages and polls'}
                      </span>
                    </span>
                    <span aria-hidden className={cn('relative h-5 w-9 shrink-0 rounded-full transition-colors', broadcastMode ? 'bg-emerald-500' : 'bg-zinc-300 dark:bg-zinc-600')}>
                      <span className={cn('absolute top-0.5 size-4 rounded-full bg-white shadow transition-all', broadcastMode ? 'left-[18px]' : 'left-0.5')} />
                    </span>
                  </button>
                ) : null}
                {isAdmin ? (
                  <div className="rounded-xl border border-dashed border-emerald-500/40 bg-emerald-500/5 p-3">
                    <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-emerald-600 dark:text-emerald-400">
                      <Link2 className="size-3" aria-hidden />
                      Invite link
                    </p>
                    {detail.inviteCode ? (
                      <>
                        <div className="mt-2 flex items-center gap-2">
                          <code className="min-w-0 flex-1 truncate rounded-lg bg-white px-2.5 py-1.5 font-mono text-[13px] font-bold tracking-[0.18em] text-zinc-800 ring-1 ring-zinc-200 dark:bg-zinc-800 dark:text-zinc-100 dark:ring-zinc-700">
                            {detail.inviteCode}
                          </code>
                          <button
                            type="button"
                            aria-label="Copy invite link"
                            title="Copy invite link"
                            onClick={copyInvite}
                            className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-500 text-white outline-none transition-transform hover:bg-emerald-500/90 active:scale-90"
                          >
                            <Copy className="size-3.5" aria-hidden />
                          </button>
                        </div>
                        <button
                          type="button"
                          disabled={invitePending}
                          onClick={() => onInvite(true)}
                          className="mt-2 flex w-full items-center justify-center gap-1.5 rounded-lg py-1.5 text-[11px] font-semibold text-zinc-500 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-700 active:scale-[0.98] disabled:opacity-50 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-200"
                        >
                          {invitePending ? (
                            <LoaderCircle className="size-3 animate-spin" aria-hidden />
                          ) : (
                            <RotateCcw className="size-3" aria-hidden />
                          )}
                          Reset link (old links stop working)
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        disabled={invitePending}
                        onClick={() => onInvite(false)}
                        className="mt-2 flex h-9 w-full items-center justify-center gap-1.5 rounded-xl bg-emerald-500 text-sm font-semibold text-white outline-none transition-all hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-50"
                      >
                        {invitePending ? (
                          <LoaderCircle className="size-4 animate-spin" aria-hidden />
                        ) : (
                          <Link2 className="size-4" aria-hidden />
                        )}
                        Create invite link
                      </button>
                    )}
                  </div>
                ) : null}
                {isAdmin ? (
                  <Button
                    variant="outline"
                    onClick={() => {
                      setPickedIds([])
                      setPickerOpen(true)
                    }}
                    className="h-10 justify-start gap-2 rounded-xl border-emerald-500/40 text-sm font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 dark:text-emerald-400"
                  >
                    <UserPlus className="size-4" aria-hidden />
                    Add members
                  </Button>
                ) : (
                  <p className="flex items-center justify-center gap-1.5 rounded-xl bg-zinc-50 px-3 py-2.5 text-[11px] font-medium text-zinc-400 dark:bg-zinc-800/60 dark:text-zinc-500">
                    <Lock className="size-3" aria-hidden />
                    Only admins can rename or add members
                  </p>
                )}
                <Button
                  variant="outline"
                  disabled={leavePending}
                  onClick={() => setConfirmingLeave(true)}
                  className="h-10 justify-start gap-2 rounded-xl border-destructive/40 text-sm font-medium text-destructive hover:bg-destructive/10 hover:text-destructive"
                >
                  <LogOut className="size-4" aria-hidden />
                  Leave group
                </Button>
              </div>
            ) : null}
          </>
        )}
      </DialogContent>
    </Dialog>

    <AlertDialog open={pendingRemoval !== null} onOpenChange={(o) => (!o ? setPendingRemoval(null) : null)}>
      <AlertDialogContent className="max-w-[320px] rounded-2xl bg-white dark:bg-zinc-900 sm:left-1/2 sm:translate-x-[-50%]">
        <AlertDialogHeader>
          <AlertDialogTitle className="tracking-tight">Remove {pendingRemovalTarget?.name ?? 'member'}?</AlertDialogTitle>
          <AlertDialogDescription className="text-[13px] leading-relaxed">
            They lose access to this group immediately. Someone with an admin role can add them back later.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter className="gap-2">
          <AlertDialogCancel className="rounded-xl">Cancel</AlertDialogCancel>
          <AlertDialogAction
            disabled={setRolePending}
            onClick={() => {
              if (pendingRemoval !== null) onRemoveMember(pendingRemoval)
              setPendingRemoval(null)
            }}
            className="rounded-xl bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/40"
          >
            Remove
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>

    <AlertDialog open={confirmingLeave} onOpenChange={setConfirmingLeave}>
      <AlertDialogContent className="max-w-[320px] rounded-2xl bg-white dark:bg-zinc-900 sm:left-1/2 sm:translate-x-[-50%]">
        <AlertDialogHeader>
          <AlertDialogTitle className="tracking-tight">Leave “{title}”?</AlertDialogTitle>
          <AlertDialogDescription className="text-[13px] leading-relaxed">
            You won&apos;t receive new messages from this group. You can always be added back later.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter className="gap-2">
          <AlertDialogCancel className="rounded-xl">Stay</AlertDialogCancel>
          <AlertDialogAction
            disabled={leavePending}
            onClick={() => {
              setConfirmingLeave(false)
              onLeave()
            }}
            className="rounded-xl bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/40"
          >
            Leave group
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
    </>
  )
}

// ── poll builder sheet ───────────────────────────────────────

const POLL_OPTIONS_MAX = 6

function PollBuilderSheet({
  open,
  onOpenChange,
  submitting,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  submitting: boolean
  onSubmit: (question: string, options: string[]) => void
}) {
  const [question, setQuestion] = useState('')
  const [options, setOptions] = useState<string[]>(['', ''])

  const reset = () => {
    setQuestion('')
    setOptions(['', ''])
  }
  const trimmedOptions = options.map((o) => o.trim()).filter((o) => o.length > 0)
  const valid = question.trim().length > 0 && trimmedOptions.length >= 2

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          reset()
          onOpenChange(false)
        }
      }}
    >
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-4 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <div className="pb-2">
          <DrawerTitle className="sr-only">Create a poll</DrawerTitle>
          <DrawerDescription className="sr-only">Ask the chat and collect live votes</DrawerDescription>
          <p className="flex items-center justify-center gap-1.5 pb-2 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
            <Vote className="size-4 text-violet-500" aria-hidden />
            Create a live poll
          </p>
          <Input
            autoFocus
            value={question}
            maxLength={140}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="Ask a question…"
            aria-label="Poll question"
            className="h-11 rounded-2xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
          />
          <p className="px-1 pb-1 pt-3 text-[10px] font-bold uppercase tracking-widest text-zinc-400 dark:text-zinc-500">
            Options (2–{POLL_OPTIONS_MAX})
          </p>
          <ul className="pulse-scroll max-h-[30dvh] space-y-1.5 overflow-y-auto pr-0.5">
            {options.map((opt, i) => (
              <li key={i} className="flex items-center gap-1.5">
                <Input
                  value={opt}
                  maxLength={80}
                  onChange={(e) =>
                    setOptions((prev) => prev.map((v, idx) => (idx === i ? e.target.value : v)))
                  }
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      if (i === options.length - 1 && options.length < POLL_OPTIONS_MAX) {
                        setOptions((prev) => [...prev, ''])
                      }
                    }
                  }}
                  placeholder={`Option ${i + 1}`}
                  aria-label={`Poll option ${i + 1}`}
                  className="h-10 flex-1 rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
                {options.length > 2 ? (
                  <button
                    type="button"
                    aria-label={`Remove option ${i + 1}`}
                    onClick={() => setOptions((prev) => prev.filter((_, idx) => idx !== i))}
                    className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90"
                  >
                    <X className="size-4" aria-hidden />
                  </button>
                ) : null}
              </li>
            ))}
          </ul>
          {options.length < POLL_OPTIONS_MAX ? (
            <button
              type="button"
              onClick={() => setOptions((prev) => [...prev, ''])}
              className="mt-1.5 flex h-8 w-full items-center justify-center gap-1 rounded-xl border border-dashed border-emerald-400/60 text-xs font-semibold text-emerald-600 outline-none transition-colors hover:bg-emerald-500/5 active:scale-[0.99] dark:text-emerald-400"
            >
              <Plus className="size-3.5" aria-hidden />
              Add option
            </button>
          ) : null}
          <div className="mt-3 flex gap-2">
            <Button
              variant="outline"
              onClick={() => {
                reset()
                onOpenChange(false)
              }}
              className="h-11 flex-1 rounded-2xl text-sm font-medium"
            >
              Cancel
            </Button>
            <Button
              disabled={!valid || submitting}
              onClick={() => {
                onSubmit(question.trim(), trimmedOptions)
                reset()
              }}
              className="h-11 flex-[1.4] gap-1.5 rounded-2xl bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90"
            >
              {submitting ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : <Vote className="size-4" aria-hidden />}
              Post poll
            </Button>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}

// ── schedule sheet ───────────────────────────────────────────

function ScheduleSheet({
  draft,
  onDraftChange,
  open,
  onOpenChange,
  chatTitle,
  pendingCount,
  sending,
  onShowPending,
  onSubmit,
}: {
  draft: string | null
  onDraftChange: (value: string | null) => void
  open: boolean
  onOpenChange: (open: boolean) => void
  chatTitle: string
  pendingCount: number
  sending: boolean
  onShowPending: () => void
  onSubmit: (whenIso: string) => void
}) {
  const [whenLocal, setWhenLocal] = useState('')

  /** local datetime-local value → ISO if it satisfies the 30s..30d window */
  const computedIso = (): string | null => {
    if (whenLocal.length === 0) return null
    const ms = Date.parse(whenLocal)
    if (Number.isNaN(ms)) return null
    const now = Date.now()
    if (ms < now + 30_000 || ms > now + 30 * 24 * 3600 * 1000) return null
    return new Date(ms).toISOString()
  }
  const iso = computedIso()

  const preset = (msAhead: number): void => {
    const target = new Date(Date.now() + msAhead)
    // snap to next clean five-minute mark for hour-scale presets
    if (msAhead >= 3600000) {
      target.setSeconds(0, 0)
      target.setMinutes(Math.round(target.getMinutes() / 5) * 5 % 60)
    }
    const pad = (n: number) => String(n).padStart(2, '0')
    setWhenLocal(
      `${target.getFullYear()}-${pad(target.getMonth() + 1)}-${pad(target.getDate())}T${pad(target.getHours())}:${pad(target.getMinutes())}`,
    )
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-4 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <div className="pb-2">
          <DrawerTitle className="sr-only">Schedule message</DrawerTitle>
          <DrawerDescription className="sr-only">Send this message automatically later</DrawerDescription>
          <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
            <CalendarClock className="size-4 text-amber-500" aria-hidden />
            Schedule for {chatTitle}
          </p>
          <Textarea
            value={draft ?? ''}
            rows={2}
            maxLength={2000}
            onChange={(e) => onDraftChange(e.target.value)}
            placeholder="Message to send…"
            aria-label="Scheduled message text"
            className="pulse-scroll mt-1 resize-none rounded-2xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
          />
          <div className="mt-2 flex flex-wrap gap-1.5">
            {[
              { label: '+1h', ms: 3600_000 },
              { label: 'Tomorrow 9:00', ms: (() => { const d = new Date(); d.setDate(d.getDate() + 1); d.setHours(9, 0, 0, 0); return d.getTime() - Date.now() })() },
              { label: '+7d', ms: 7 * 24 * 3600_000 },
            ].map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => preset(p.ms)}
                className="rounded-full bg-zinc-100 px-3 py-1 text-[11px] font-semibold text-zinc-600 outline-none transition-colors hover:bg-emerald-500/15 hover:text-emerald-700 active:scale-95 dark:bg-zinc-800 dark:text-zinc-300 dark:hover:text-emerald-400"
              >
                {p.label}
              </button>
            ))}
          </div>
          <label className="mt-2 block px-1 text-[10px] font-bold uppercase tracking-widest text-zinc-400 dark:text-zinc-500" htmlFor="schedule-at">
            Send at
          </label>
          <input
            id="schedule-at"
            type="datetime-local"
            value={whenLocal}
            onChange={(e) => setWhenLocal(e.target.value)}
            className="mt-1 h-11 w-full rounded-2xl border border-zinc-200 bg-zinc-50 px-3 font-mono text-sm outline-none transition-colors focus:border-emerald-400 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100"
          />
          {iso ? (
            <p className="mt-1.5 flex items-center gap-1 px-1 text-[11px] font-medium text-emerald-600 dark:text-emerald-400">
              <CheckCheck className="size-3.5" aria-hidden />
              Sends itself {formatListStamp(iso)} · {formatTime(iso)}
            </p>
          ) : whenLocal.length > 0 ? (
            <p className="mt-1.5 px-1 text-[11px] font-medium text-amber-600 dark:text-amber-400">
              Pick a moment between 30 seconds and 30 days from now.
            </p>
          ) : null}
          <div className="mt-3 flex gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)} className="h-11 flex-1 rounded-2xl text-sm font-medium">
              Cancel
            </Button>
            <Button
              disabled={!iso || !draft || draft.trim().length === 0 || sending}
              onClick={() => iso && onSubmit(iso)}
              className="h-11 flex-[1.5] gap-1.5 rounded-2xl bg-amber-500 text-sm font-bold text-white shadow-md shadow-amber-600/20 hover:bg-amber-500/90"
            >
              {sending ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : <CalendarClock className="size-4" aria-hidden />}
              Schedule send
            </Button>
          </div>
          {pendingCount > 0 ? (
            <button
              type="button"
              onClick={onShowPending}
              className="mt-2 w-full text-center text-[11px] font-semibold text-zinc-400 underline-offset-2 outline-none hover:text-zinc-600 hover:underline dark:hover:text-zinc-200"
            >
              Manage {pendingCount} pending scheduled {pendingCount === 1 ? 'message' : 'messages'}
            </button>
          ) : null}
        </div>
      </DrawerContent>
    </Drawer>
  )
}

// ── scheduled sends manager ─────────────────────────────────

function ScheduledListDrawer({
  open,
  onOpenChange,
  items,
  loading,
  onCancel,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  items: ScheduledItem[]
  loading: boolean
  onCancel: (id: string) => void
}) {
  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <DrawerTitle className="sr-only">Pending scheduled messages</DrawerTitle>
        <DrawerDescription className="sr-only">Delayed sends still waiting to fire</DrawerDescription>
        <div className="pb-2">
          <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
            <CalendarClock className="size-4 text-amber-500" aria-hidden />
            {items.length === 0 ? 'Nothing scheduled' : `${items.length} scheduled ${items.length === 1 ? 'message' : 'messages'}`}
          </p>
          {loading ? (
            <div className="space-y-2 py-3" role="status" aria-label="Loading scheduled messages">
              <Skeleton className="h-14 w-full rounded-2xl" />
              <Skeleton className="h-14 w-full rounded-2xl" />
            </div>
          ) : items.length === 0 ? (
            <p className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
              Draft a message and choose “Schedule message” — it sends itself later.
            </p>
          ) : (
            <ul className="pulse-scroll max-h-[44dvh] space-y-2 overflow-y-auto py-1">
              {items.map((item) => (
                <li key={item.id} className="rounded-2xl border border-zinc-200 bg-zinc-50/70 p-2.5 dark:border-zinc-700 dark:bg-zinc-800/60">
                  <div className="flex items-center gap-2">
                    <CalendarClock className="size-3.5 shrink-0 text-amber-500" aria-hidden />
                    <span className="text-[11px] font-bold uppercase tracking-wide text-amber-600 dark:text-amber-400">
                      {formatListStamp(item.scheduledAt)} · {formatTime(item.scheduledAt)}
                    </span>
                    <button
                      type="button"
                      onClick={() => onCancel(item.id)}
                      className="ml-auto rounded-full p-1 text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90"
                      aria-label="Cancel this scheduled message"
                    >
                      <Trash2 className="size-3.5" aria-hidden />
                    </button>
                  </div>
                  <p className="mt-1 line-clamp-3 text-[13px] leading-snug text-zinc-600 dark:text-zinc-300">
                    {item.content.replace(/\s+/g, ' ').trim()}
                  </p>
                </li>
              ))}
            </ul>
          )}
          <button
            type="button"
            onClick={() => onOpenChange(false)}
            className="mt-2 flex h-11 w-full items-center justify-center rounded-2xl bg-zinc-100 text-sm font-semibold text-zinc-600 outline-none transition-transform hover:bg-zinc-200 active:scale-[0.98] dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-zinc-700"
          >
            Close
          </button>
        </div>
      </DrawerContent>
    </Drawer>
  )
}

// ── thread sheet (Slack/Zulip-style side discussion) ─────────

function ThreadSheet({
  root,
  onClose,
  myId,
  sending,
  text,
  onTextChange,
  onSend,
  onOpenProfile,
}: {
  root: ChatMessage | null
  onClose: () => void
  myId: string
  sending: boolean
  /** lifted draft so hot reloads / polls never eat the composer text */
  text: string
  onTextChange: (value: string) => void
  onSend: () => void
  /** sender avatar tap → other-user profile sheet (R26-b) */
  onOpenProfile?: (sender: MessageAuthor) => void
}) {
  const listRef = useRef<HTMLDivElement>(null)

  const threadQuery = useQuery({
    queryKey: ['thread', root?.id ?? '-'],
    enabled: root !== null,
    staleTime: 15_000,
    queryFn: async (): Promise<{ parent: ChatMessage; replies: ChatMessage[] }> => {
      return apiJson(`/api/messages/${encodeURIComponent(root!.id)}/thread?userId=${encodeURIComponent(myId)}`)
    },
  })

  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [threadQuery.data?.replies.length])

  const submit = () => {
    const content = text.trim()
    if (content.length === 0 || sending) return
    onSend()
  }

  const isOpen = root !== null
  return (
    <Drawer open={isOpen} onOpenChange={(next) => !next && onClose()}>
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <DrawerTitle className="sr-only">Thread</DrawerTitle>
        <DrawerDescription className="sr-only">Replies kept tidy under one message</DrawerDescription>
        <div className="pb-2">
          <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
            <MessageSquare className="size-4 text-violet-500" aria-hidden />
            Thread
            {(threadQuery.data?.replies.length ?? 0) > 0 ? (
              <span className="rounded-full bg-violet-500/10 px-1.5 text-[10px] font-bold text-violet-600 dark:text-violet-300">
                {threadQuery.data?.replies.length}
              </span>
            ) : null}
          </p>

          {root ? (
            <div className="mb-2 rounded-2xl border border-zinc-200 bg-zinc-50/70 p-2.5 dark:border-zinc-700 dark:bg-zinc-800/60">
              <div className="flex items-center gap-2">
                {onOpenProfile ? (
                  <button
                    type="button"
                    aria-label={`View ${root.sender.name}'s profile`}
                    onClick={() => onOpenProfile(root.sender)}
                    className="shrink-0 rounded-full outline-none transition-transform duration-150 active:scale-90"
                  >
                    <UserAvatar name={root.sender.name} color={root.sender.color} avatar={root.sender.avatar} size={22} />
                  </button>
                ) : (
                  <UserAvatar name={root.sender.name} color={root.sender.color} avatar={root.sender.avatar} size={22} />
                )}
                <span className="truncate text-xs font-bold text-emerald-700 dark:text-emerald-400">
                  {root.sender.id === myId ? 'You' : root.sender.name}
                </span>
                <span className="ml-auto shrink-0 text-[10px] text-zinc-400">{formatListStamp(root.createdAt)}</span>
              </div>
              <p className="mt-1 line-clamp-4 whitespace-pre-wrap break-words text-[13px] leading-snug text-zinc-700 dark:text-zinc-200">
                {root.content.replace(/\s+/g, ' ').trim() || (root.imagePath ? 'Photo' : root.audioPath ? 'Voice note' : '')}
              </p>
            </div>
          ) : null}

          <div ref={listRef} className="pulse-scroll min-h-[120px] max-h-[38dvh] space-y-2 overflow-y-auto py-1">
            {threadQuery.isPending && root ? (
              <div className="space-y-2" role="status" aria-label="Loading thread replies">
                <Skeleton className="h-12 w-3/4 rounded-2xl" />
                <Skeleton className="ml-auto h-12 w-2/3 rounded-2xl" />
              </div>
            ) : (threadQuery.data?.replies.length ?? 0) === 0 ? (
              <p className="py-4 text-center text-xs text-zinc-400 dark:text-zinc-500">
                No replies yet — start the discussion.
              </p>
            ) : (
              (threadQuery.data?.replies ?? []).map((m) => {
                const mine = m.senderId === myId
                return m.deletedAt ? (
                  <p key={m.id} className="pl-1 text-[11px] italic text-zinc-400">reply was deleted</p>
                ) : (
                  <div key={m.id} className={cn('flex items-start gap-2', mine && 'flex-row-reverse')}>
                    {onOpenProfile ? (
                      <button
                        type="button"
                        aria-label={`View ${m.sender.name}'s profile`}
                        onClick={() => onOpenProfile(m.sender)}
                        className="shrink-0 rounded-full outline-none transition-transform duration-150 active:scale-90"
                      >
                        <UserAvatar name={m.sender.name} color={m.sender.color} avatar={m.sender.avatar} size={26} />
                      </button>
                    ) : (
                      <UserAvatar name={m.sender.name} color={m.sender.color} avatar={m.sender.avatar} size={26} />
                    )}
                    <div className={cn('max-w-[76%]', mine && 'text-right')}>
                      <p className="text-[10px] font-bold text-emerald-700 dark:text-emerald-400">
                        {mine ? 'You' : m.sender.name}
                        <span className="ml-1.5 font-normal text-zinc-400">{formatTime(m.createdAt)}</span>
                      </p>
                      <div
                        className={cn(
                          'mt-0.5 inline-block rounded-2xl px-3 py-1.5 text-left',
                          mine
                            ? 'bg-emerald-500 text-white'
                            : 'border border-zinc-100 bg-white text-zinc-900 shadow-sm dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100',
                        )}
                      >
                        <BubbleText content={m.content} mine={mine} memberNames={[m.sender.name]} />
                      </div>
                    </div>
                  </div>
                )
              })
            )}
          </div>

          <div className="mt-2 flex items-end gap-1.5">
            <textarea
              value={text}
              rows={1}
              aria-label="Reply in thread"
              placeholder="Reply in thread…"
              maxLength={2000}
              onChange={(e) => onTextChange(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  submit()
                }
              }}
              className="pulse-scroll max-h-[96px] min-h-[42px] flex-1 resize-none rounded-2xl border border-zinc-200 bg-zinc-50 px-3.5 py-2.5 text-sm outline-none transition-colors focus:border-emerald-400 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100"
            />
            <button
              type="button"
              aria-label="Send thread reply"
              disabled={text.trim().length === 0 || sending}
              onClick={submit}
              className="flex size-11 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-90 disabled:opacity-40"
            >
              {sending ? <LoaderCircle className="size-5 animate-spin" aria-hidden /> : <SendHorizontal className="size-5" aria-hidden />}
            </button>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}

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
  type ReactNode,
} from 'react'
import { AnimatePresence, motion, useMotionValue, useTransform } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import {
  ArrowDown,
  BellOff,
  CalendarClock,
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
  Forward,
  Globe,
  HelpCircle,
  ImagePlus,
  Info,
  Link2,
  LoaderCircle,
  Lock,
  LogOut,
  Megaphone,
  MessageSquare,
  Mic,
  Pause,
  Pencil,
  Pin,
  PinOff,
  Play,
  Plus,
  Reply,
  RotateCcw,
  Search,
  SearchX,
  SendHorizontal,
  Smile,
  Sparkles,
  Star,
  Timer,
  Trash2,
  UserPlus,
  UserRoundMinus,
  Vote,
  VolumeX,
  X,
  Zap,
} from 'lucide-react'
import { toast } from 'sonner'
import type {
  AppUser,
  ChatMessage,
  ConversationDetail,
  SavedItem,
  ScheduledItem,
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
  otherMemberOf,
  REACTION_CHOICES,
  EMOJI_PICKER_CHOICES,
  splitUrlSegments,
  uid,
} from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
import { pulseOutboxStore, outboxCount } from '@/lib/pulse-outbox'
import { ForwardSheet } from '@/components/chat/forward-sheet'
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
  { cmd: '/help', args: '', help: 'Show every command' },
] as const

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

interface SlashOutcome {
  kind: 'send'
  content: string
}

/** Transform a leading slash command into real message content (or a UI action code). */
function applySlash(rawInput: string): SlashOutcome | { kind: 'poll' } | { kind: 'schedule' } | { kind: 'help' } | { kind: 'error'; message: string } {
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
        return { kind: 'send', content: `🎲 Rolled **1d6**: *${roll?.total ?? '?'}*` }
      }
      const roll = rollDice(arg)
      if (!roll) return { kind: 'error', message: 'Usage: /roll AdM — e.g. /roll 2d6' }
      const parts = roll.rolls.join(' + ')
      return { kind: 'send', content: `🎲 Rolled **${arg.toLowerCase()}**: ${parts} = *${roll.total}*` }
    }
    case 'poll':
      return { kind: 'poll' }
    case 'schedule':
      return { kind: 'schedule' }
    case 'help':
      return { kind: 'help' }
    default:
      return {
        kind: 'error',
        message: `Unknown command "/${word}" — try /help`,
      }
  }
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
  conversationId,
  unreadAnchorMs = null,
  initialJumpMessageId = null,
  onClose,
}: {
  me: AppUser
  conversationId: string
  /** pre-open read watermark frozen by the chats list at tap time (unread divider) */
  unreadAnchorMs?: number | null
  /** global-search hit — jump + flash this message once history renders */
  initialJumpMessageId?: string | null
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const realtime = usePulseRealtime()
  const { resolvedTheme } = useTheme()
  const themeMounted = useMounted()

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

  // restore persisted draft once per opened conversation
  const [input, setInput] = useState(() => pulseDraftsStore.getState().drafts[conversationId] ?? '')
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  /** header menu → inline mute preset choices */
  const [muteChoicesOpen, setMuteChoicesOpen] = useState(false)
  const [selected, setSelected] = useState<ChatMessage | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [showJump, setShowJump] = useState(false)
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)
  /** message being edited (Telegram-style composer edit mode) */
  const [editing, setEditing] = useState<ChatMessage | null>(null)
  /** pinned-messages sheet */
  const [pinnedOpen, setPinnedOpen] = useState(false)
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
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchDraft, setSearchDraft] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** message currently flashing (search hit / quoted-reply jump) */
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

  const viewportRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const nearBottomRef = useRef(true)

  const applyHasMore = useCallback((next: boolean) => {
    hasMoreHistoryRef.current = next
    setHasMoreHistory(next)
  }, [])
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
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

  const messages = useQuery({
    queryKey: ['messages', conversationId],
    queryFn: async (): Promise<ChatMessage[]> => {
      const res = await apiJson<MessagesResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=${MESSAGES_PAGE_SIZE}`,
      )
      // Merge with whatever is cached (older pages loaded via "Load older")
      // so a background refetch never amputates already-loaded history.
      const previous = queryClient.getQueryData<ChatMessage[]>(['messages', conversationId])
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
      const res = await apiJson<MessagesResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=${OLDER_PAGE_SIZE}&before=${encodeURIComponent(oldest.createdAt)}`,
      )
      // Arm the anchor only now — the very next render (data applied) restores it.
      scrollRestoreRef.current = { prevHeight, prevTop }
      let cachedCount = res.messages.length
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
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
  }, [messages.data, loadingOlder, conversationId, queryClient, applyHasMore])

  // scroll anchor: after prepending, keep the viewport pinned to the same content
  useLayoutEffect(() => {
    const pending = scrollRestoreRef.current
    const el = viewportRef.current
    if (!pending || !el) return
    scrollRestoreRef.current = null
    el.scrollTop = el.scrollHeight - pending.prevHeight + pending.prevTop
  })

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

  // ── search overlay plumbing ────────────────────────────────

  /** Debounced commit of the search draft (same pattern as drafts). */
  const handleSearchChange = (value: string) => {
    setSearchDraft(value)
    if (searchTimerRef.current !== null) clearTimeout(searchTimerRef.current)
    searchTimerRef.current = setTimeout(() => {
      searchTimerRef.current = null
      setSearchQuery(value.trim())
    }, 220)
  }

  const closeSearch = useCallback(() => {
    if (searchTimerRef.current !== null) {
      clearTimeout(searchTimerRef.current)
      searchTimerRef.current = null
    }
    setSearchOpen(false)
  }, [])

  const searchResults = useQuery({
    queryKey: ['message-search', conversationId, searchQuery],
    enabled: searchOpen && searchQuery.length > 0,
    staleTime: 20_000,
    queryFn: async (): Promise<{ items: ChatMessage[]; total: number }> => {
      const res = await apiJson<SearchResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=100&q=${encodeURIComponent(searchQuery)}`,
      )
      return { items: res.messages, total: res.total ?? res.messages.length }
    },
  })

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

  // unmount safety: clear search/highlight timers
  useEffect(
    () => () => {
      if (searchTimerRef.current !== null) clearTimeout(searchTimerRef.current)
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

  const recipients = useMemo(
    () => (detailData ? detailData.members.filter((m) => m.id !== me.id).map((m) => m.id) : []),
    [detailData, me.id],
  )

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
      .map((m) => ({ id: m.id, name: m.name, color: m.color }))
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
    if (nearBottomRef.current) setShowJump(false)
  }, [])

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
    }: {
      clientId: string
      content: string
      replyToId?: string
      imagePath?: string
      audioPath?: string
      durationMs?: number
      parentId?: string
      viewOnce?: boolean
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
          }),
        },
      )
      return { res, clientId }
    },
    onMutate: async ({ clientId, content, replyToId, imagePath, audioPath, durationMs, parentId, viewOnce }) => {
      const parentSnapshot = replyToId && replyTo && replyTo.id === replyToId
        ? {
            id: replyTo.id,
            content: replyTo.content,
            senderName: replyTo.sender.name,
            deleted: replyTo.deletedAt !== null,
          }
        : null
      const temp: ChatMessage = {
        id: `temp-${clientId}`,
        conversationId,
        senderId: me.id,
        content,
        deletedAt: null,
        createdAt: new Date().toISOString(),
        sender: { id: me.id, name: me.name, color: me.color },
        reactions: [],
        replyTo: parentSnapshot,
        imagePath: imagePath ?? null,
        audioPath: audioPath ?? null,
        durationMs: durationMs ?? null,
        editedAt: null,
        pinnedAt: null,
        pinnedBy: null,
        parentId: parentId ?? null,
        viewOnce: viewOnce === true,
        viewedAt: null,
        viewedBy: null,
        expiresAt: null,
        linkUrl: null,
        linkPreview: null,
        poll: null,
        translations: [],
      }
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? [...old, temp] : [temp],
      )
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
      setReplyTo(null)
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) => {
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? old.filter((m) => m.id !== `temp-${clientId}`) : old,
      )
      toast.error('Message failed to send')
    },
  })

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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old
          ? old.map((m) => (m.id === messageId ? { ...m, deletedAt: new Date().toISOString() } : m))
          : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      const real = data.message
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? old.map((m) => (m.id === real.id ? real : m)) : old,
      )
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Message deleted')
    },
    onError: (_error, _messageId, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['messages', conversationId], context.previous)
      }
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old?.map((m) =>
          m.id === messageId
            ? { ...m, pinnedAt: m.pinnedAt ? null : now, pinnedBy: m.pinnedAt ? null : me.id }
            : m,
        ),
      )
    },
    onSuccess: ({ message: real }) => {
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
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
      queryClient.setQueryData<ChatMessage[]>(['messages', conversationId], (old) =>
        old ? old.map((m) => (m.id === fresh.id ? fresh : m)) : old,
      )
    },
    [queryClient, conversationId],
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
      toast.success(data.saved ? 'Saved to your library ⭐' : 'Removed from saved')
      haptic(10)
    },
    onError: () => toast.error('Could not update saved state'),
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
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`
  }, [])

  const stopTyping = useCallback(() => {
    if (recipients.length > 0) {
      realtime.cancelTyping(conversationId, { viewerId: me.id, recipients })
    }
  }, [realtime, conversationId, me.id, recipients])

  useEffect(() => () => stopTyping(), [stopTyping])

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
      transformed = outcome.content.trim()
      if (transformed.length === 0) return
    }

    const content = transformed.length > 0 ? transformed : input.trim()
    if (content.length === 0) return

    if (sendMessage.isPending) return
    stopTyping()
    setInput('')
    pulseDraftsStore.getState().clearDraft(conversationId)
    requestAnimationFrame(autosize)

    const clientId = uid()
    const replyTarget = replyTo && !replyTo.deletedAt ? replyTo : null

    // Offline → hold in the persisted outbox; the realtime provider
    // flushes it (FIFO) as soon as connectivity returns.
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      const queuedTemp: ChatMessage = {
        id: `temp-${clientId}`,
        conversationId,
        senderId: me.id,
        content,
        deletedAt: null,
        createdAt: new Date().toISOString(),
        sender: { id: me.id, name: me.name, color: me.color },
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
        sender: { id: me.id, name: me.name, color: me.color },
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
    })
  }, [input, editing, editMessage, sendMessage, stopTyping, autosize, replyTo, conversationId, me, queryClient])

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

  const handleInputChange = (value: string) => {
    setInput(value)
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
  }, [sendMessage, replyTo, sendingVoice, teardownRecorder])

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

  const startLongPress = useCallback(
    (message: ChatMessage) => {
      clearLongPress()
      longPressRef.current = setTimeout(() => {
        setSelected(message)
        longPressRef.current = null
      }, 450)
    },
    [clearLongPress],
  )

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
      ? `${detailData?.members.length ?? 0} members · ${onlineOthers} online${isBroadcast ? ' · 📣 announcements' : ''}${ttlSeconds > 0 ? ' · ⏱ disappearing' : ''}`
      : !other
        ? ''
        : dmStatus.length > 0
          ? realtime.onlineIds.has(other.id)
            ? `${dmStatus} · online`
            : `${dmStatus} · offline`
          : realtime.onlineIds.has(other.id)
            ? 'online'
            : 'offline'

  const dotColor = themeMounted && resolvedTheme === 'dark' ? 'rgba(255,255,255,0.055)' : 'rgba(0,0,0,0.05)'
  // layered wallpaper: soft emerald glows top/bottom over the dot grid
  const glowTop = themeMounted && resolvedTheme === 'dark' ? 'rgba(16,185,129,0.055)' : 'rgba(16,185,129,0.05)'
  const glowBottom = themeMounted && resolvedTheme === 'dark' ? 'rgba(20,184,166,0.04)' : 'rgba(20,184,166,0.035)'

  return (
    <motion.div
      initial={{ y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={{ type: 'spring', stiffness: 320, damping: 34 }}
      className="absolute inset-0 z-40 flex flex-col bg-white dark:bg-zinc-900"
      role="dialog"
      aria-label={`Conversation with ${headerTitle}`}
    >
      {/* header */}
      <header className="relative z-20 flex min-h-14 shrink-0 items-center gap-1.5 border-b border-zinc-200 bg-white px-2 pt-[env(safe-area-inset-top)] dark:border-zinc-800 dark:bg-zinc-900">
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
          <GroupAvatar title={displayName} id={conversationId} size={36} />
        ) : (
          <UserAvatar
            name={other?.name ?? displayName}
            color={other?.color}
            size={36}
            showPresence
            online={other ? realtime.onlineIds.has(other.id) : false}
          />
        )}
        <button
          type="button"
          onClick={() => setInfoOpen(true)}
          aria-label="Show info"
          className="ml-1.5 min-w-0 flex-1 text-left outline-none"
        >
          <p className="flex items-center gap-1 truncate text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
            <span className="truncate">{headerTitle}</span>
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
                    setSearchDraft('')
                    setSearchQuery('')
                    setSearchOpen(true)
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
                  {isGroup ? 'Group info' : 'Contact info'}
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

      {/* pinned banner (Telegram/WhatsApp-style) */}
      {latestPinned ? (
        <button
          type="button"
          onClick={() => setPinnedOpen(true)}
          aria-label={`Open pinned messages — ${pinnedCount} pinned`}
          className="flex shrink-0 items-center gap-2 border-b border-emerald-500/15 bg-white/85 px-3 py-1.5 text-left backdrop-blur transition-colors hover:bg-white dark:border-emerald-400/10 dark:bg-zinc-900/85 dark:hover:bg-zinc-900"
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
        </button>
      ) : null}

      {/* messages */}
      <div
        ref={viewportRef}
        onScroll={handleScroll}
        className="pulse-scroll relative min-h-0 flex-1 overflow-y-auto overscroll-contain bg-zinc-50 px-3 pt-3 pb-2 dark:bg-black/25"
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
          <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
            <div
              aria-hidden
              className="flex size-16 items-center justify-center rounded-3xl bg-gradient-to-br from-emerald-400/15 to-emerald-600/10 text-emerald-500 dark:from-emerald-400/10 dark:to-emerald-600/5"
            >
              <SendHorizontal className="size-7 -rotate-45" />
            </div>
            <div>
              <p className="text-sm font-semibold text-zinc-600 dark:text-zinc-300">No messages yet</p>
              <p className="mt-1 text-xs text-zinc-400 dark:text-zinc-500">
                Say hello — your words travel in real time.
              </p>
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
                  <span className="rounded-full bg-white/85 px-3 py-1 text-[11px] font-medium text-zinc-600 shadow-sm ring-1 ring-black/5 backdrop-blur-md dark:bg-zinc-800/85 dark:text-zinc-300 dark:ring-white/10">
                    {item.label}
                  </span>
                </div>
              ) : item.kind === 'unread' ? (
                <div key={item.key} className="my-3 flex items-center gap-2 px-1" role="separator" aria-label="Unread messages">
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                  <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold tracking-widest text-emerald-600 dark:text-emerald-400">
                    UNREAD
                  </span>
                  <span className="h-px flex-1 bg-emerald-400/50 dark:bg-emerald-500/40" />
                </div>
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
                  onPress={setSelected}
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
                  highlighted={highlight !== null && highlight.id === item.message.id}
                />
              ),
            )}

            <AnimatePresence>
              {typers.length > 0 ? (
                <motion.div
                  key="typing-bubble"
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: 4 }}
                  transition={{ duration: 0.15 }}
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
                          size={28}
                        />
                      )
                    })()
                  ) : other ? (
                    <UserAvatar name={other.name} color={other.color} size={28} />
                  ) : null}
                  <div className="rounded-2xl rounded-bl-md border border-zinc-100 bg-white px-3 py-2.5 shadow-sm dark:border-zinc-700 dark:bg-zinc-800">
                    <TypingDots />
                  </div>
                </motion.div>
              ) : null}
            </AnimatePresence>
          </div>
        )}

        <AnimatePresence>
          {showJump ? (
            <motion.button
              type="button"
              aria-label="Jump to newest messages"
              initial={{ opacity: 0, y: 10, scale: 0.85 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.9 }}
              transition={{ type: 'spring', stiffness: 420, damping: 22 }}
              onClick={() => {
                setShowJump(false)
                haptic(8)
                scrollToBottom(true)
              }}
              className="sticky bottom-1 z-10 ml-auto mr-1 mt-2 flex items-center gap-1.5 rounded-full bg-emerald-500 py-2 pr-3.5 pl-3 text-xs font-semibold text-white shadow-lg shadow-emerald-600/30 active:scale-95"
            >
              New messages
              <ArrowDown className="size-3.5" aria-hidden />
            </motion.button>
          ) : null}
        </AnimatePresence>
      </div>

      {/* composer */}
      <div className="shrink-0 border-t border-zinc-200 bg-white p-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] dark:border-zinc-800 dark:bg-zinc-900">
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

        {broadcastLocked ? (
          <div className="flex items-center justify-center gap-2 rounded-2xl bg-zinc-100 px-3 py-3 text-xs font-semibold text-zinc-500 ring-1 ring-inset ring-zinc-200 dark:bg-zinc-800 dark:text-zinc-400 dark:ring-zinc-700">
            <Megaphone className="size-4 text-emerald-500" aria-hidden />
            Announcement mode — only admins can send here
          </div>
        ) : null}

        <div className={cn('relative flex items-end gap-2', broadcastLocked && 'pointer-events-none select-none opacity-40')}>
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
                  <UserAvatar name={m.name} color={m.color} size={26} />
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
              <button
                type="button"
                aria-label="Cancel recording"
                onClick={() => finishRecording(false)}
                className="flex size-11 shrink-0 items-center justify-center rounded-full bg-rose-100 text-rose-600 outline-none transition-transform hover:bg-rose-200 active:scale-90 dark:bg-rose-500/15 dark:text-rose-400 dark:hover:bg-rose-500/25"
              >
                <X className="size-5" aria-hidden />
              </button>
              <div
                role="status"
                aria-label="Recording voice note"
                className="flex h-11 flex-1 items-center gap-2.5 rounded-full border border-rose-200 bg-rose-50 px-4 dark:border-rose-500/30 dark:bg-rose-500/10"
              >
                <span className="relative flex size-2.5 shrink-0" aria-hidden>
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-rose-400 opacity-75" />
                  <span className="relative inline-flex size-2.5 rounded-full bg-rose-500" />
                </span>
                <span className="text-sm font-semibold tabular-nums text-rose-600 dark:text-rose-400">
                  {formatVoicems(recordMs)}
                </span>
                <span className="ml-auto truncate text-xs text-zinc-400 dark:text-zinc-500">
                  Recording voice note…
                </span>
              </div>
              <button
                type="button"
                aria-label="Stop and send voice note"
                disabled={sendingVoice}
                onClick={() => finishRecording(true)}
                className="flex size-11 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-90 disabled:opacity-60"
              >
                {sendingVoice ? (
                  <LoaderCircle className="size-5 animate-spin" aria-hidden />
                ) : (
                  <SendHorizontal className="size-5" aria-hidden />
                )}
              </button>
            </>
          ) : (
            <>
              {/* Telegram/Discord attach menu: photos · polls · scheduled · quick phrases */}
              <Popover>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    aria-label="Add attachment"
                    className="flex size-11 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-emerald-600 active:scale-90 dark:hover:bg-zinc-800"
                  >
                    <Plus className="size-6" aria-hidden />
                  </button>
                </PopoverTrigger>
                <PopoverContent
                  side="top"
                  align="start"
                  sideOffset={10}
                  className="w-56 rounded-2xl p-1.5 dark:bg-zinc-800"
                >
                  <div className="flex flex-col">
                    <button
                      type="button"
                      role="menuitem"
                      disabled={sendingImage || broadcastLocked}
                      onClick={() => fileInputRef.current?.click()}
                      className="flex items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-200 dark:hover:bg-zinc-700"
                    >
                      {sendingImage ? (
                        <LoaderCircle className="size-4 animate-spin text-emerald-500" aria-hidden />
                      ) : (
                        <ImagePlus className="size-4 text-emerald-500" aria-hidden />
                      )}
                      Photo
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      disabled={broadcastLocked}
                      onClick={() => {
                        setPollBuilderOpen(true)
                        setHelpOpen(false)
                      }}
                      className="flex items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-200 dark:hover:bg-zinc-700"
                    >
                      <Vote className="size-4 text-violet-500" aria-hidden />
                      Create poll
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      disabled={broadcastLocked}
                      onClick={() => {
                        const draft = input.trim()
                        if (draft.length === 0 && scheduleFor === null) {
                          toast.info('Type the message first, then schedule it')
                          return
                        }
                        setScheduleFor(draft)
                      }}
                      className="flex items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-200 dark:hover:bg-zinc-700"
                    >
                      <CalendarClock className="size-4 text-amber-500" aria-hidden />
                      Schedule message
                    </button>
                    {(scheduledQuery.data?.length ?? 0) > 0 ? (
                      <button
                        type="button"
                        role="menuitem"
                        onClick={() => setScheduledListOpen(true)}
                        className="flex items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                      >
                        <Clock className="size-4 text-zinc-400" aria-hidden />
                        Pending sends
                        <span className="ml-auto rounded-full bg-amber-100 px-1.5 text-[10px] font-bold text-amber-600 dark:bg-amber-500/20 dark:text-amber-300">
                          {scheduledQuery.data?.length}
                        </span>
                      </button>
                    ) : null}
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => setHelpOpen(true)}
                      className="flex items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-sm font-medium text-zinc-700 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:text-zinc-200 dark:hover:bg-zinc-700"
                    >
                      <Dices className="size-4 text-teal-500" aria-hidden />
                      Slash commands
                    </button>
                  </div>
                </PopoverContent>
              </Popover>
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
                className="pulse-scroll max-h-[120px] flex-1 resize-none rounded-3xl border border-zinc-200 bg-zinc-50 px-4 py-2.5 text-sm leading-snug text-zinc-900 outline-none transition-colors focus:border-emerald-400 focus:bg-white dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100 dark:focus:border-emerald-500/70"
              />
              {input.trim().length === 0 && !editing ? (
                <button
                  type="button"
                  aria-label="Record voice note"
                  onClick={() => void startRecording()}
                  className="flex size-11 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-zinc-500 outline-none transition-all hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-90 dark:bg-zinc-800 dark:text-zinc-400 dark:hover:text-emerald-400"
                >
                  <Mic className="size-5" aria-hidden />
                </button>
              ) : (
                <button
                  type="button"
                  aria-label={editing ? 'Save edit' : 'Send message'}
                  disabled={input.trim().length === 0 || sendMessage.isPending || editMessage.isPending}
                  onClick={submit}
                  className={cn(
                    'flex size-11 shrink-0 items-center justify-center rounded-full transition-all active:scale-90',
                    input.trim().length > 0
                      ? 'bg-emerald-500 text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90'
                      : 'bg-zinc-200 text-zinc-400 dark:bg-zinc-700 dark:text-zinc-500',
                  )}
                >
                  {editing ? (
                    <Check className="size-5" aria-hidden />
                  ) : (
                    <SendHorizontal className="size-5" aria-hidden />
                  )}
                </button>
              )}
            </>
          )}
        </div>
      </div>

      {/* message actions */}
      <Dialog open={selected !== null} onOpenChange={(open) => !open && setSelected(null)}>
        <DialogContent className="max-w-[300px] gap-3 rounded-2xl p-4 sm:left-1/2 sm:translate-x-[-50%] dark:bg-zinc-900">
          <DialogHeader className="text-left">
            <DialogTitle className="text-sm font-bold tracking-tight">Message options</DialogTitle>
            <DialogDescription className="text-xs leading-relaxed">
              {selected?.deletedAt
                ? 'This message was deleted.'
                : selected?.content.replace(/\s+/g, ' ').slice(0, 140)}
            </DialogDescription>
          </DialogHeader>
          {selected && !selected.deletedAt ? (
            <div className="flex items-center justify-between gap-0.5" role="group" aria-label="React with an emoji">
              {REACTION_CHOICES.map((emoji) => (
                <button
                  key={emoji}
                  type="button"
                  aria-label={`React with ${emoji}`}
                  onClick={() => {
                    handleToggleReaction(selected.id, emoji)
                    setSelected(null)
                  }}
                  className="flex size-10 items-center justify-center rounded-full text-xl outline-none transition-transform hover:scale-125 hover:bg-zinc-100 active:scale-95 dark:hover:bg-zinc-800"
                >
                  {emoji}
                </button>
              ))}
            </div>
          ) : null}
          <div className="flex flex-col gap-1.5">
            <Button
              variant="outline"
              onClick={() => {
                if (!selected) return
                setReplyTo(selected)
                setSelected(null)
                requestAnimationFrame(() => textareaRef.current?.focus())
              }}
              disabled={!!selected?.deletedAt}
              className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
            >
              <Reply className="size-4" aria-hidden />
              Reply
            </Button>
            {selected && selected.parentId === null && !selected.deletedAt ? (
              <Button
                variant="outline"
                onClick={() => {
                  const target = selected
                  setSelected(null)
                  openThread(target)
                }}
                className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
              >
                <MessageSquare className="size-4 text-violet-500" aria-hidden />
                Reply in thread
              </Button>
            ) : null}
            {selected && !selected.deletedAt ? (
              <Button
                variant="outline"
                disabled={toggleSaved.isPending}
                onClick={() => {
                  toggleSaved.mutate(selected.id)
                  setSelected(null)
                }}
                className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
              >
                <Star className={cn('size-4', isSavedIds.has(selected.id) ? 'fill-amber-400 text-amber-500' : 'text-amber-500')} aria-hidden />
                {isSavedIds.has(selected.id) ? 'Unsave' : 'Save message'}
              </Button>
            ) : null}
            <Button
              variant="outline"
              onClick={copySelected}
              disabled={!!selected?.deletedAt}
              className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
            >
              <Copy className="size-4" aria-hidden />
              Copy text
            </Button>
            {selected && selected.senderId === me.id && !selected.deletedAt && selected.audioPath === null ? (
              <Button
                variant="outline"
                onClick={() => startEdit(selected)}
                className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
              >
                <Pencil className="size-4 text-amber-500" aria-hidden />
                Edit message
              </Button>
            ) : null}
            {selected && !selected.deletedAt ? (
              <Button
                variant="outline"
                disabled={pinMessage.isPending}
                onClick={() => {
                  const targetId = selected.id
                  setSelected(null)
                  pinMessage.mutate(targetId)
                }}
                className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
              >
                {selected.pinnedAt ? (
                  <PinOff className="size-4 text-zinc-400" aria-hidden />
                ) : (
                  <Pin className="size-4 text-emerald-500" aria-hidden />
                )}
                {selected.pinnedAt ? 'Unpin' : 'Pin'}
              </Button>
            ) : null}
            <Button
              variant="outline"
              disabled={!!selected?.deletedAt}
              onClick={() => {
                const target = selected
                setSelected(null)
                startForward(target)
              }}
              className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
            >
              <Forward className="size-4" aria-hidden />
              Forward to chat…
            </Button>
            {selected && selected.senderId === me.id ? (
              <Button
                variant="outline"
                disabled={!!selected?.deletedAt}
                onClick={() => openMessageInfo(selected)}
                className="h-10 justify-start gap-2 rounded-xl text-sm font-medium"
              >
                <Info className="size-4" aria-hidden />
                Message info
              </Button>
            ) : null}
            <Button
              variant="outline"
              disabled={!canDeleteSelected}
              onClick={() => setConfirmingDelete(true)}
              className="h-10 justify-start gap-2 rounded-xl border-destructive/40 text-sm font-medium text-destructive hover:bg-destructive/10 hover:text-destructive"
            >
              <Trash2 className="size-4" aria-hidden />
              Delete for everyone
            </Button>
          </div>
        </DialogContent>
      </Dialog>

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

      {/* pinned messages sheet */}
      <Drawer open={pinnedOpen} onOpenChange={setPinnedOpen}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          <DrawerTitle className="sr-only">Pinned messages</DrawerTitle>
          <DrawerDescription className="sr-only">Messages pinned in this chat</DrawerDescription>
          <div className="pb-2">
            <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
              <Pin className="size-4 rotate-45 text-emerald-500" aria-hidden />
              {pinnedCount === 1 ? '1 pinned message' : `${pinnedCount} pinned messages`}
            </p>
            {pinnedQuery.isPending ? (
              <div className="space-y-2 py-2" role="status" aria-label="Loading pinned messages">
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-full rounded-2xl" />
              </div>
            ) : pinnedList.length === 0 ? (
              <p className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
                Nothing pinned yet — long-press a message and choose Pin.
              </p>
            ) : (
              <ul className="pulse-scroll max-h-[52dvh] space-y-2 overflow-y-auto py-1">
                {pinnedList.map((m) => (
                  <li
                    key={m.id}
                    className="rounded-2xl border border-zinc-200 bg-zinc-50/60 p-2.5 dark:border-zinc-700 dark:bg-zinc-800/60"
                  >
                    <div className="flex items-center gap-2">
                      <UserAvatar name={m.sender.name} color={m.sender.color} size={24} />
                      <span className="truncate text-xs font-semibold text-zinc-700 dark:text-zinc-200">
                        {m.sender.id === me.id ? 'You' : m.sender.name}
                      </span>
                      <span className="ml-auto shrink-0 text-[10px] text-zinc-400 dark:text-zinc-500">
                        {formatListStamp(m.createdAt)}
                      </span>
                    </div>
                    <p className="mt-1 line-clamp-3 text-[13px] leading-snug text-zinc-600 dark:text-zinc-300">
                      {m.content.replace(/\s+/g, ' ').trim() || '📷 Photo'}
                    </p>
                    <div className="mt-1.5 flex gap-1.5">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          setPinnedOpen(false)
                          void jumpToMessage(m.id)
                        }}
                        className="h-7 gap-1 rounded-full px-3 text-[11px] font-semibold"
                      >
                        <ArrowDown className="size-3" aria-hidden />
                        Jump
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={pinMessage.isPending}
                        onClick={() => pinMessage.mutate(m.id)}
                        className="h-7 gap-1 rounded-full px-3 text-[11px] font-semibold text-zinc-500 hover:text-destructive"
                      >
                        <PinOff className="size-3" aria-hidden />
                        Unpin
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </DrawerContent>
      </Drawer>

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
                    <li key={id} className="flex items-center gap-3 rounded-xl px-2 py-2">
                      <UserAvatar
                        name={member?.name ?? 'Unknown'}
                        color={member?.color ?? 'emerald'}
                        size={34}
                      />
                      <span className="flex-1 truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                        {member?.name ?? 'Unknown'}
                        {id === me.id ? <span className="ml-1 text-xs text-zinc-400">(you)</span> : null}
                      </span>
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
                      <li
                        key={member.id}
                        className="flex items-center gap-3 rounded-xl px-2 py-2.5 transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800/60"
                      >
                        <span className="relative">
                          <UserAvatar name={member.name} color={member.color} size={36} />
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

      {/* full-screen message-search overlay */}
      <AnimatePresence>
        {searchOpen ? (
          <motion.div
            key="search-overlay"
            initial={{ y: '100%' }}
            animate={{ y: 0 }}
            exit={{ y: '100%' }}
            transition={{ type: 'spring', stiffness: 340, damping: 34 }}
            role="dialog"
            aria-label={`Search messages in ${headerTitle}`}
            className="absolute inset-0 z-50 flex flex-col bg-white dark:bg-zinc-900"
          >
            <div className="flex min-h-14 shrink-0 items-center gap-1.5 border-b border-zinc-200 bg-white px-2 pt-[env(safe-area-inset-top)] dark:border-zinc-800 dark:bg-zinc-900">
              <Button
                variant="ghost"
                size="icon"
                aria-label="Back to conversation"
                onClick={closeSearch}
                className="size-10 shrink-0 rounded-full text-zinc-600 hover:bg-transparent hover:text-zinc-900 active:scale-95 dark:text-zinc-300 dark:hover:text-white"
              >
                <ChevronLeft className="size-6" aria-hidden />
              </Button>
              <div className="relative min-w-0 flex-1">
                <Search
                  className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400"
                  aria-hidden
                />
                <Input
                  autoFocus
                  value={searchDraft}
                  onChange={(e) => handleSearchChange(e.target.value)}
                  placeholder={`Search in ${headerTitle}`}
                  aria-label="Search messages"
                  autoComplete="off"
                  className="h-10 rounded-xl border-zinc-200 bg-zinc-50 pr-9 pl-9 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
                {searchDraft.length > 0 ? (
                  <button
                    type="button"
                    aria-label="Clear search"
                    onClick={() => handleSearchChange('')}
                    className="absolute top-1/2 right-2 -translate-y-1/2 rounded-full p-1 text-zinc-400 outline-none transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
                  >
                    <X className="size-4" aria-hidden />
                  </button>
                ) : null}
              </div>
            </div>

            <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain bg-zinc-50 px-3 pt-4 pb-4 dark:bg-black/25">
              {searchQuery.length === 0 ? (
                <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
                  <div
                    aria-hidden
                    className="flex size-16 items-center justify-center rounded-3xl bg-gradient-to-br from-emerald-400/15 to-emerald-600/10 text-emerald-500 dark:from-emerald-400/10 dark:to-emerald-600/5"
                  >
                    <Search className="size-7" aria-hidden />
                  </div>
                  <div>
                    <p className="text-sm font-semibold text-zinc-600 dark:text-zinc-300">
                      Search this conversation
                    </p>
                    <p className="mt-1 max-w-[220px] text-xs text-zinc-400 dark:text-zinc-500">
                      Find any message by its text — jump straight back to it.
                    </p>
                  </div>
                </div>
              ) : searchResults.isPending ? (
                <div role="status" aria-label="Searching messages" className="flex justify-center py-10">
                  <LoaderCircle className="size-5 animate-spin text-zinc-400" aria-hidden />
                </div>
              ) : !searchResults.data || searchResults.data.items.length === 0 ? (
                <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
                  <div
                    aria-hidden
                    className="flex size-14 items-center justify-center rounded-2xl bg-zinc-200/60 text-zinc-400 dark:bg-zinc-800"
                  >
                    <SearchX className="size-6" aria-hidden />
                  </div>
                  <p className="text-sm font-semibold text-zinc-500 dark:text-zinc-400">
                    No matches for “{searchQuery}”
                  </p>
                  <p className="text-xs text-zinc-400 dark:text-zinc-500">
                    Try a shorter or different phrase.
                  </p>
                </div>
              ) : (
                <>
                  <div className="mb-2.5 flex justify-center">
                    <span className="rounded-full bg-zinc-200/70 px-3 py-1 text-[11px] font-medium text-zinc-600 shadow-sm ring-1 ring-black/5 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-white/5">
                      {(() => {
                        const n = searchResults.data.total
                        return `${n === 1 ? '1 match' : `${n} matches`} for “${searchQuery}”`
                      })()}
                    </span>
                  </div>
                  {[...searchResults.data.items].reverse().map((m, idx) => (
                    <motion.button
                      key={m.id}
                      type="button"
                      initial={{ opacity: 0, y: 6 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ duration: 0.16, delay: Math.min(idx * 0.02, 0.24) }}
                      onClick={() => {
                        closeSearch()
                        void jumpToMessage(m.id)
                      }}
                      className="mb-1.5 flex w-full items-start gap-2.5 rounded-xl border border-zinc-200 bg-white p-2.5 text-left shadow-sm outline-none transition-colors hover:border-emerald-300 active:scale-[0.99] dark:border-zinc-700 dark:bg-zinc-800"
                    >
                      <UserAvatar name={m.sender.name} color={m.sender.color} size={30} />
                      <span className="min-w-0 flex-1">
                        <span className="flex items-baseline justify-between gap-2">
                          <span className="truncate text-xs font-bold text-emerald-700 dark:text-emerald-400">
                            {m.sender.id === me.id ? 'You' : m.sender.name}
                          </span>
                          <span className="shrink-0 text-[10px] tabular-nums text-zinc-400 dark:text-zinc-500">
                            {(() => {
                              const stamp = formatListStamp(m.createdAt)
                              const time = formatTime(m.createdAt)
                              return stamp === time ? time : `${stamp} · ${time}`
                            })()}
                          </span>
                        </span>
                        <span className="mt-0.5 line-clamp-2 block text-[13px] leading-snug break-words text-zinc-600 dark:text-zinc-300">
                          {m.imagePath ? '📷 ' : ''}
                          {m.audioPath ? '🎤 ' : ''}
                          <MatchedText
                            content={
                              m.content.replace(/\s+/g, ' ').trim() ||
                              (m.imagePath ? 'Photo' : 'Voice message')
                            }
                            query={searchQuery}
                          />
                        </span>
                      </span>
                    </motion.button>
                  ))}
                </>
              )}
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* poll builder sheet */}
      <PollBuilderSheet
        open={pollBuilderOpen}
        onOpenChange={setPollBuilderOpen}
        submitting={createPoll.isPending}
        onSubmit={(question, options) => createPoll.mutate({ question, options })}
      />

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
      />

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
      />
    </motion.div>
  )
}

// ── pieces ───────────────────────────────────────────────────

function TypingDots() {
  return (
    <span className="inline-flex items-center gap-1 py-0.5" aria-hidden>
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          animate={{ y: [0, -3, 0], opacity: [0.5, 1, 0.5] }}
          transition={{ repeat: Infinity, duration: 0.9, delay: i * 0.15, ease: 'easeInOut' }}
          className="size-1.5 rounded-full bg-zinc-400 dark:bg-zinc-500"
        />
      ))}
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
  readBy: { members: Array<{ id: string; name: string; color: string }>; all: boolean } | null
  /** search/reply jump flash — ring-pulse this bubble briefly */
  highlighted: boolean
  /** live Slack/Zulip reply count for THIS thread root (0 = none) */
  threadCount: number
  onPress: (message: ChatMessage) => void
  onStartLongPress: (message: ChatMessage) => void
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
}

/** Renders text with the first case-insensitive occurrence of `query` highlighted. */
function MatchedText({ content, query }: { content: string; query: string }) {
  const idx = query.length > 0 ? content.toLowerCase().indexOf(query.toLowerCase()) : -1
  if (idx < 0) return <>{content}</>
  return (
    <>
      {content.slice(0, idx)}
      <mark className="rounded bg-emerald-500/20 px-0.5 font-semibold text-emerald-700 dark:text-emerald-300">
        {content.slice(idx, idx + query.length)}
      </mark>
      {content.slice(idx + query.length)}
    </>
  )
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
                  <span className={cn('flex size-4 shrink-0 items-center justify-center rounded-full border text-[9px] font-bold', picked ? (mine ? 'border-white bg-white text-emerald-600' : 'border-emerald-500 bg-emerald-500 text-white') : mine ? 'border-white/50 text-transparent' : 'border-zinc-400 text-transparent dark:border-zinc-500')}>
                    ✓
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
  const hintOpacity = useTransform(towardX, [10, 34], [0, 1])
  const hintScale = useTransform(towardX, [10, 52], [0.6, 1.05])

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
        head ? 'mt-2.5' : 'mt-0.5',
      )}
    >
      {!mine && isGroup ? (
        head ? (
          <div className="mr-1.5 flex shrink-0 items-end pb-5">
            <UserAvatar name={message.sender.name} color={message.sender.color} size={28} />
          </div>
        ) : (
          <span className="mr-1.5 block w-7 shrink-0" aria-hidden />
        )
      ) : null}

      <div className={cn('flex max-w-[78%] flex-col', mine ? 'items-end' : 'items-start')}>
        {!mine && isGroup && head && !deleted ? (
          <span className="mb-0.5 ml-1 text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">
            {message.sender.name}
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
          drag="x"
          dragConstraints={{ left: -56, right: 56 }}
          dragElastic={0.16}
          dragDirectionLock
          dragSnapToOrigin
          style={{ x: bubbleX }}
          onDragStart={() => {
            dragMovedRef.current = false
            onEndLongPress()
          }}
          onDragEnd={(_e, info) => {
            const toward = mine ? -info.offset.x : info.offset.x
            if (toward > 52) {
              dragMovedRef.current = true
              beginReply()
            }
          }}
          onClick={() => {
            if (dragMovedRef.current) {
              dragMovedRef.current = false
              return
            }
            if (interactive && !isImage) onPress(message)
          }}
          onPointerDown={() => interactive && onStartLongPress(message)}
          onPointerUp={onEndLongPress}
          onPointerLeave={onEndLongPress}
          onDoubleClick={() => {
            if (interactive) {
              onToggleReaction(message.id, '❤️')
            }
          }}
          role={interactive && !isImage ? 'button' : undefined}
          tabIndex={interactive && !isImage ? 0 : undefined}
          onKeyDown={(event) => {
            if (interactive && !isImage && event.key === 'Enter') onPress(message)
          }}
          className={cn(
            'relative select-none',
            highlighted && !deleted && 'animate-[pulse-message-flash_1.5s_ease-out_1]',
            jumbo
              ? 'px-1 py-0.5'
              : isPoll || (isImage && !viewBurned)
                ? 'rounded-2xl p-1 shadow-sm'
                : isVoice
                  ? 'rounded-2xl px-2.5 py-2 shadow-sm'
                  : 'rounded-2xl px-3 py-2 shadow-sm',
            deleted &&
              cn(
                'border border-dashed italic',
                'rounded-2xl border-zinc-300 bg-transparent text-zinc-400 dark:border-zinc-600 dark:text-zinc-500',
                mine ? 'rounded-br-md opacity-80' : 'rounded-bl-md',
              ),
            !deleted && !jumbo && (mine
              ? cn(
                  'rounded-2xl rounded-br-md bg-emerald-500 text-white',
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
                  onClick={() => {
                    if (!chipFiredRef.current) onToggleReaction(message.id, group.emoji)
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
                    <span
                      className={cn(
                        'font-semibold',
                        iReacted
                          ? 'text-emerald-700 dark:text-emerald-300'
                          : 'text-zinc-500 dark:text-zinc-300',
                      )}
                    >
                      {group.count}
                    </span>
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
                    <UserAvatar name={member.name} color={member.color} size={14} />
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
    prev.onClosePoll === next.onClosePoll
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
                  <UserAvatar
                    name={o?.name ?? title}
                    color={o?.color}
                    size={44}
                    showPresence
                    online={o ? onlineIds.has(o.id) : false}
                  />
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
                    <UserAvatar name={member.name} color={member.color} size={38} showPresence online={online} />
                    <div className="min-w-0 flex-1">
                      <div className="flex min-w-0 items-center gap-1.5">
                        <p className="min-w-0 truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                          {member.name}
                          {isMe ? <span className="ml-1 text-xs font-normal text-zinc-400">(you)</span> : null}
                        </p>
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
}: {
  root: ChatMessage | null
  onClose: () => void
  myId: string
  sending: boolean
  /** lifted draft so hot reloads / polls never eat the composer text */
  text: string
  onTextChange: (value: string) => void
  onSend: () => void
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
                <UserAvatar name={root.sender.name} color={root.sender.color} size={22} />
                <span className="truncate text-xs font-bold text-emerald-700 dark:text-emerald-400">
                  {root.sender.id === myId ? 'You' : root.sender.name}
                </span>
                <span className="ml-auto shrink-0 text-[10px] text-zinc-400">{formatListStamp(root.createdAt)}</span>
              </div>
              <p className="mt-1 line-clamp-4 whitespace-pre-wrap break-words text-[13px] leading-snug text-zinc-700 dark:text-zinc-200">
                {root.content.replace(/\s+/g, ' ').trim() || (root.imagePath ? '📷 Photo' : root.audioPath ? '🎤 Voice note' : '')}
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
                    <UserAvatar name={m.sender.name} color={m.sender.color} size={26} />
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

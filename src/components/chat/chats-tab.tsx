// ─────────────────────────────────────────────────────────────
// Pulse Chat — Chats tab: conversation list, search, empty state.
// R27-e casual sweep: mute-duration strip + clear chat + .txt export
// through the compact glass option menu (chats-actions.tsx); rows live
// in chats-row.tsx; #/chats/archived is a REAL hash sub-page
// (chats-archived-page.tsx) opened from the glass pill row below.
// R34-a: Telegram multi-select (long-press → check rows → floating
// Archive / Mute-8h / Mark-read bar) + #/calls sub-page entry.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Image from 'next/image'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useStore } from 'zustand'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArrowRight, AtSign, BellOff, CheckCheck, ChevronRight, FolderPlus, LoaderCircle, NotebookPen, Phone, Plus, Radio, Search, SquarePen, Users, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary, FolderSummary, SearchResultMessage } from '@/lib/types'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import {
  apiJson,
  conversationDisplayName,
  conversationPreview,
  conversationPreviewPrefix,
  formatListStamp,
  otherMemberOf,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import type { ChatsListFilter } from '@/lib/pulse-settings'
import { pulseSettingsStore } from '@/lib/pulse-settings'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useHashNav } from '@/lib/hash-router'
import { UserAvatar } from '@/components/chat/user-avatar'
import { ThemeToggleButton } from '@/components/chat/theme-toggle'
import {
  StoriesSheet,
  storiesQueryKey,
} from '@/components/chat/stories-sheet'
import type { StoryGroup, StoriesResponse } from '@/components/chat/stories-sheet'
import { StoryComposerSheet } from '@/components/chat/story-composer-sheet'
import { FoldersSheet } from '@/components/chat/folders-sheet'
import { ConversationRow, type ConversationRowData } from '@/components/chat/chats-row'
import { RowSkeleton } from '@/components/chat/chats-skeleton'
import {
  ChatOptionsSheet,
  downloadTranscript,
  fetchFullHistory,
} from '@/components/chat/chats-actions'
import { ChatsArchivedPage } from '@/components/chat/chats-archived-page'
import { ChannelsPage } from '@/components/chat/channels-page'
import { CallsPage } from '@/components/chat/calls-page'
import {
  MentionsPage,
  fetchMentions,
  type MentionsResponse,
} from '@/components/chat/mentions-page'

interface ConversationsResponse {
  conversations: ConversationSummary[]
}

interface SearchResponse {
  messages: SearchResultMessage[]
  total: number
}

/**
 * Snippet with the first match highlighted — clips a ≤64-char window
 * around the hit so long messages stay one tidy line.
 */
function SearchSnippet({ content, query }: { content: string; query: string }) {
  const lower = content.toLowerCase()
  const q = query.toLowerCase()
  const idx = q.length > 0 ? lower.indexOf(q) : -1
  let from = 0
  let clippedHead = false
  if (idx > 28) {
    from = idx - 24
    clippedHead = true
  }
  const end = Math.min(content.length, idx + q.length + 28)
  const clippedTail = end < content.length
  const body = content.slice(from, end)
  const localIdx = idx - from
  return (
    <p className="truncate text-[13px] text-zinc-500 dark:text-zinc-400">
      {clippedHead ? <span className="text-zinc-300 dark:text-zinc-600">…</span> : null}
      {idx >= 0 ? (
        <>
          {body.slice(0, localIdx)}
          <mark className="rounded bg-emerald-500/20 px-0.5 font-semibold text-emerald-700 dark:bg-emerald-500/25 dark:text-emerald-300">
            {body.slice(localIdx, localIdx + q.length)}
          </mark>
          {body.slice(localIdx + q.length)}
        </>
      ) : (
        body
      )}
      {clippedTail ? <span className="text-zinc-300 dark:text-zinc-600">…</span> : null}
    </p>
  )
}

/** One server-side message hit — sender avatar, chat title, highlighted snippet. */
const SearchMessageRow = memo(function SearchMessageRow({
  hit,
  query,
  onPress,
}: {
  hit: SearchResultMessage
  query: string
  onPress: (hit: SearchResultMessage) => void
}) {
  const deleted = hit.deletedAt !== null
  // R41 — document hits: when the caption is empty or is not itself the match,
  // show "Document — <fileName>" so the row explains why it matched.
  const isFileHit = hit.filePath !== null
  const captionMatches = hit.content.length > 0 && hit.content.toLowerCase().includes(query.toLowerCase())
  const fileSnippet =
    hit.fileName !== null ? `Document — ${hit.fileName}` : hit.content.length > 0 ? hit.content : 'Document'
  return (
    <motion.button
      type="button"
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.16 }}
      whileTap={{ scale: 0.975 }}
      onClick={() => onPress(hit)}
      className="flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 text-left outline-none transition-colors active:bg-zinc-100 dark:active:bg-zinc-800"
    >
      <span className="relative shrink-0">
        <UserAvatar name={hit.sender.name} color={hit.sender.color} size={40} />
        {hit.isGroup ? (
          <span className="absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full bg-zinc-200 ring-2 ring-white dark:bg-zinc-700 dark:ring-zinc-900">
            <Users className="size-2.5 text-zinc-500 dark:text-zinc-300" aria-hidden />
          </span>
        ) : null}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-baseline justify-between gap-2">
          <span className="min-w-0 truncate text-[13px] font-semibold text-zinc-800 dark:text-zinc-100">
            {hit.conversationName}
          </span>
          <span className="shrink-0 text-[11px] text-zinc-400 dark:text-zinc-500">
            {formatListStamp(hit.createdAt)}
          </span>
        </span>
        {deleted ? (
          <p className="truncate text-[13px] italic text-zinc-400 dark:text-zinc-500">Deleted message</p>
        ) : hit.imagePath && hit.content.length === 0 ? (
          <p className="truncate text-[13px] text-zinc-500 dark:text-zinc-400">📷 Photo</p>
        ) : isFileHit && !captionMatches ? (
          <SearchSnippet content={fileSnippet} query={query} />
        ) : (
          <SearchSnippet content={hit.content} query={query} />
        )}
      </span>
    </motion.button>
  )
})

/** Tiny uppercase section header with an emerald count chip. */
function SearchSection({ label, count }: { label: string; count: number }) {
  return (
    <div className="flex items-center gap-2 px-3 pb-0.5 pt-3">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-zinc-400 dark:text-zinc-500">
        {label}
      </span>
      <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-emerald-500/15 px-1.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
        {count > 99 ? '99+' : count}
      </span>
      <span aria-hidden className="h-px flex-1 bg-zinc-100 dark:bg-zinc-800" />
    </div>
  )
}

// ── 24h status stories ──────────────────────────────────────

const STORY_RING_SIZE = 56

/**
 * One avatar cell in the Status row. `ring`: 'unseen' → animated
 * emerald→teal conic ring · 'seen' → static zinc ring · 'none' → plain
 * (used by "My status" when no story is live; renders the "+" badge).
 */
const StoryRingCell = memo(function StoryRingCell({
  name,
  color,
  ring,
  plus = false,
  label,
  onPress,
  index,
}: {
  name: string
  color: string
  ring: 'unseen' | 'seen' | 'none'
  plus?: boolean
  label: string
  onPress: () => void
  index: number
}) {
  const reducedMotion = useReducedMotion()
  const inner = STORY_RING_SIZE - 5
  return (
    <motion.button
      type="button"
      initial={reducedMotion ? false : 'hidden'}
      animate="shown"
      whileTap={reducedMotion ? undefined : 'tap'}
      variants={{
        hidden: {
          opacity: 0,
          y: 10,
          transition: { duration: 0.28, ease: ease.out, delay: stagger(index, 0.03, 10) },
        },
        shown: { opacity: 1, y: 0, scale: 1, transition: spring.bouncy },
        tap: { opacity: 1, y: 0, scale: 0.92, transition: spring.bouncy },
      }}
      onClick={onPress}
      aria-label={ring === 'unseen' ? `${label} — new status` : label}
      className="flex w-16 shrink-0 snap-start flex-col items-center gap-1 rounded-2xl pb-1 pt-0.5 outline-none"
    >
      <span className="relative block" style={{ width: STORY_RING_SIZE, height: STORY_RING_SIZE }}>
        {ring === 'unseen' ? (
          <span
            aria-hidden
            className="pulse-story-spin absolute inset-0 rounded-full [background:conic-gradient(from_0deg,#34d399,#14b8a6,#6ee7b7,#10b981,#34d399)]"
          />
        ) : (
          <span
            aria-hidden
            className={cn(
              'absolute inset-0 rounded-full',
              ring === 'seen' ? 'bg-zinc-300 dark:bg-zinc-600' : 'bg-zinc-200 dark:bg-zinc-700',
            )}
          />
        )}
        <span className="absolute inset-[2.5px] overflow-hidden rounded-full bg-white dark:bg-zinc-900">
          <UserAvatar name={name} color={color} size={inner} />
        </span>
        {plus ? (
          <span className="absolute -right-0.5 -bottom-0.5 flex size-5 items-center justify-center rounded-full bg-emerald-500 text-white ring-2 ring-white dark:ring-zinc-900">
            <Plus className="size-3" strokeWidth={3} aria-hidden />
          </span>
        ) : null}
      </span>
      <span className="w-full truncate text-center text-[11px] font-medium leading-tight text-zinc-600 dark:text-zinc-400">
        {label}
      </span>
    </motion.button>
  )
})

export function ChatsTab({
  me,
  onOpenConversation,
  onOpenContacts,
  onRequestNewChat,
  onGoProfile,
}: {
  me: AppUser
  onOpenConversation: (conversationId: string, unreadAnchorMs: number | null, jumpMessageId?: string) => void
  onOpenContacts: () => void
  onRequestNewChat: () => void
  onGoProfile: () => void
}) {
  const realtime = usePulseRealtime()
  const typersIn = realtime.typersIn
  const onlineIds = realtime.onlineIds

  // #/chats/archived + #/chats/channels sub-pages — same internal hash
  // pattern the settings tree uses: open = push, back = pop to '/'.
  const { path, navigate, back } = useHashNav()
  const archivedPageOpen = path === '/chats/archived'
  const openArchivedPage = useCallback(() => {
    haptic(6)
    navigate('/chats/archived')
  }, [navigate])
  const channelsPageOpen = path === '/chats/channels'
  const openChannelsPage = useCallback(() => {
    haptic(6)
    navigate('/chats/channels')
  }, [navigate])
  // R34-a — #/calls: WhatsApp 'Calls' paradigm, same hash sub-page anatomy
  const callsPageOpen = path === '/calls'
  const openCallsPage = useCallback(() => {
    haptic(6)
    navigate('/calls')
  }, [navigate])
  // R35-b — #/mentions: Discord mobile 'Mentions' tab, same hash sub-page anatomy
  const mentionsPageOpen = path === '/mentions'
  const openMentionsPage = useCallback(() => {
    haptic(6)
    navigate('/mentions')
  }, [navigate])

  // Real mention count for the entry pill — same query key the sub-page uses,
  // so the cache is warm the moment the page opens.
  const mentions = useQuery({
    queryKey: ['mentions', me.id],
    queryFn: () => fetchMentions(me.id),
    refetchInterval: 30_000,
    staleTime: 15_000,
  })
  const mentionCount = mentions.data?.items.length ?? 0

  const [searching, setSearching] = useState(false)
  const [searchFocused, setSearchFocused] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  /** debounced mirror of searchQuery feeding the server message search */
  const [deferredQuery, setDeferredQuery] = useState('')
  const deferredTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    refetchInterval: 6_000,
  })

  /**
   * Entrance stagger plays ONLY on the tab's first list render — the flag flips
   * right after the first commit that has rows, so refetches/edits never re-animate.
   * (setState is deferred off the effect body to keep the commit clean.)
   */
  const [entranceOn, setEntranceOn] = useState(true)
  useEffect(() => {
    if ((conversations.data ?? []).length > 0) {
      const t = setTimeout(() => setEntranceOn(false), 0)
      return () => clearTimeout(t)
    }
  })

  // live drafts → "Draft: …" previews in the list (zustand external store)
  const allDrafts = useStore(pulseDraftsStore, (s) => s.drafts)

  const rows = useMemo<Array<{ conv: ConversationSummary; props: ConversationRowData }>>(() => {
    // R24-a: Note-to-Self chats render via their dedicated card below —
    // never as a "DM with myself" row in the regular list.
    return (conversations.data ?? []).filter((conv) => !conv.isSelf).map((conv) => {
      const previewInfo = conversationPreview(conv, me.id)
      const other = conv.isGroup ? null : otherMemberOf(conv, me.id)
      const groupName =
        conv.name?.trim() || conv.members.filter((m) => m.id !== me.id).map((m) => m.name).join(', ') || 'Group'
      const displayName = conversationDisplayName(conv, me.id)
      return {
        conv,
        props: {
          id: conv.id,
          isGroup: conv.isGroup,
          name: displayName,
          time: conv.lastMessage
            ? formatListStamp(conv.lastMessage.createdAt)
            : formatListStamp(conv.updatedAt),
          preview: previewInfo.text,
          previewPrefix: conversationPreviewPrefix(previewInfo, conv.isGroup),
          previewDeleted: previewInfo.deleted,
          draft: allDrafts[conv.id] ?? null,
          unreadCount: conv.unreadCount,
          manualUnread: conv.myManualUnread, // R44: mark-as-unread dot
          dmName: other?.name ?? null,
          dmColor: other?.color ?? 'emerald',
          groupTitle: groupName,
          online: !conv.isGroup && other !== null && onlineIds.has(other.id),
          pinned: conv.pinnedAt !== null,
          muted: conv.mutedUntil !== null && Date.parse(conv.mutedUntil) > Date.now(),
          typing: typersIn(conv.id, me.id).length > 0,
          streakCount: conv.myStreak?.count ?? 0, // R31-a: live-streak chip
          // R33-b: at-risk nudge + channel/group photo straight from the summary
          streakAtRisk: conv.deadStreak ?? null,
          // R37: honest end-state chip (never beside a live/at-risk streak)
          streakLost: conv.lostStreak ?? null,
          photo: conv.photo ?? null,
          archived: conv.archivedAt !== null,
        },
      }
    })
  }, [conversations.data, me.id, onlineIds, typersIn, allDrafts])

  const filteredRows = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return rows
    return rows.filter(({ conv, props }) => {
      if (props.name.toLowerCase().includes(q)) return true
      if (props.draft?.toLowerCase().includes(q)) return true
      if (conv.lastMessage && !conv.lastMessage.deletedAt && conv.lastMessage.content.toLowerCase().includes(q)) return true
      return false
    })
  }, [rows, searchQuery])

  /** main-list rows exclude the viewer's archived chats */
  const activeRows = useMemo(() => rows.filter(({ conv }) => conv.archivedAt === null), [rows])
  const archivedRows = useMemo(() => rows.filter(({ conv }) => conv.archivedAt !== null), [rows])

  // Telegram-style folder filter (persisted preference)
  const listFilter = useStore(pulseSettingsStore, (s) => s.listFilter)
  const setListFilter = useStore(pulseSettingsStore, (s) => s.setListFilter)
  const unreadTotal = useMemo(
    () => activeRows.reduce((sum, { conv }) => sum + conv.unreadCount, 0),
    [activeRows],
  )
  const folderFiltered = useMemo(() => {
    if (listFilter === 'unread') return activeRows.filter(({ conv }) => conv.unreadCount > 0)
    if (listFilter === 'groups') return activeRows.filter(({ conv }) => conv.isGroup)
    return activeRows
  }, [activeRows, listFilter])
  const archivedUnread = useMemo(
    () => archivedRows.reduce((sum, { conv }) => sum + conv.unreadCount, 0),
    [archivedRows],
  )
  /** R30-c — channels the viewer is subscribed to (participant row = subscription) */
  const subscribedChannelCount = useMemo(
    () => rows.filter(({ conv }) => conv.isGroup && conv.broadcastMode).length,
    [rows],
  )

  /** Freeze "where was I" from the list summary AT TAP TIME (pre-read watermark). */
  const handlePress = useCallback(
    (conv: ConversationSummary) => {
      const mine = conv.members.find((m) => m.id === me.id) as
        | (AppUser & { lastReadAt?: string })
        | undefined
      const wm = mine?.lastReadAt ? Date.parse(mine.lastReadAt) : Number.NaN
      const anchor = conv.unreadCount > 0 && !Number.isNaN(wm) ? wm : null
      onOpenConversation(conv.id, anchor)
    },
    [onOpenConversation, me.id],
  )

  const closeSearch = useCallback(() => {
    if (deferredTimerRef.current !== null) {
      clearTimeout(deferredTimerRef.current)
      deferredTimerRef.current = null
    }
    setSearching(false)
    setSearchFocused(false)
    setSearchQuery('')
    setDeferredQuery('')
  }, [])

  /** typing in the search field: instant local filter + 250ms-debounced server search */
  const handleSearchInput = useCallback((value: string) => {
    setSearchQuery(value)
    if (deferredTimerRef.current !== null) clearTimeout(deferredTimerRef.current)
    const v = value.trim()
    deferredTimerRef.current = setTimeout(() => {
      deferredTimerRef.current = null
      setDeferredQuery(v)
    }, 250)
  }, [])

  const serverSearch = useQuery({
    queryKey: ['global-search', me.id, deferredQuery],
    enabled: searching && deferredQuery.length >= 2,
    staleTime: 15_000,
    queryFn: async (): Promise<SearchResponse> =>
      apiJson<SearchResponse>(
        `/api/search?userId=${encodeURIComponent(me.id)}&q=${encodeURIComponent(deferredQuery)}`,
      ),
  })
  const serverHits = searching && deferredQuery.length >= 2 ? (serverSearch.data?.messages ?? []) : []

  // ── row long-press action sheet (pin/unpin) ───────────────
  const [sheetConv, setSheetConv] = useState<ConversationSummary | null>(null)
  const queryClient = useQueryClient()

  // ── 24h status stories (ring row + viewer/composer sheets) ──
  const storiesQ = useQuery({
    queryKey: storiesQueryKey(me.id),
    queryFn: async (): Promise<StoriesResponse> =>
      apiJson<StoriesResponse>(`/api/stories?requesterId=${encodeURIComponent(me.id)}`),
    staleTime: 15_000,
    refetchInterval: 60_000,
  })
  const storyGroups = useMemo(() => storiesQ.data?.groups ?? [], [storiesQ.data])
  const myStoryGroup = useMemo(() => storyGroups.find((g) => g.mine) ?? null, [storyGroups])
  const otherStoryGroups = useMemo(() => storyGroups.filter((g) => !g.mine), [storyGroups])

  /** open viewer at a specific user's first story (null = closed) */
  const [viewerStart, setViewerStart] = useState<{ userId: string; storyId: string } | null>(null)
  const [composerOpen, setComposerOpen] = useState(false)

  const openMyStatus = useCallback(() => {
    haptic(6)
    if (myStoryGroup && myStoryGroup.stories.length > 0) {
      setViewerStart({ userId: myStoryGroup.user.id, storyId: myStoryGroup.stories[0].id })
    } else {
      setComposerOpen(true)
    }
  }, [myStoryGroup])

  const openStoryGroup = useCallback((group: StoryGroup) => {
    haptic(6)
    setViewerStart({ userId: group.user.id, storyId: group.stories[0].id })
  }, [])

  const togglePin = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      return apiJson<{ ok: boolean; pinned: boolean }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/pin`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id }),
        },
      )
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(data.pinned ? 'Pinned to top' : 'Unpinned')
      setSheetConv(null)
    },
    onError: () => {
      toast.error('Could not update the pin')
    },
  })

  // R44 — mark as unread/read: flips the viewer's manualUnread flag
  // (PATCH /mark-unread); the row shows the dot until the room is opened.
  const toggleMarkUnread = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      const on = !conv.myManualUnread
      return apiJson<{ ok: boolean; manualUnread: boolean }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/mark-unread`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, on }),
        },
      )
    },
    onMutate: async (conv) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<Array<ConversationSummary>>(['conversations', me.id])
      queryClient.setQueryData<Array<ConversationSummary>>(['conversations', me.id], (old) =>
        old
          ? old.map((c) => (c.id === conv.id ? { ...c, myManualUnread: !c.myManualUnread } : c))
          : old,
      )
      return { previous }
    },
    onSuccess: (data) => {
      toast.success(data.manualUnread ? 'Marked as unread' : 'Marked as read')
      setSheetConv(null)
    },
    onError: (_error, _conv, context) => {
      if (context?.previous) {
        queryClient.setQueryData<Array<ConversationSummary>>(['conversations', me.id], context.previous)
      }
      toast.error('Could not update the unread flag')
    },
  })

  /** per-user notification mute — '8h' | '1w' | 'always' | null (PATCH /mute) */
  const toggleMute = useMutation({
    mutationFn: async ({ conv, until }: { conv: ConversationSummary; until: '8h' | '1w' | 'always' | null }) => {
      return apiJson<{ ok: boolean; mutedUntil: string | null }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/mute`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, until }),
        },
      )
    },
    // optimistic — the BellOff chip flips before the round-trip lands
    onMutate: async ({ conv, until }) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const offsetsMs: Record<'8h' | '1w' | 'always', number> = {
          '8h': 8 * 60 * 60 * 1000,
          '1w': 7 * 24 * 60 * 60 * 1000,
          // mirror of the route's 'always' preset (+50y)
          always: 50 * 365 * 24 * 60 * 60 * 1000,
        }
        const mutedUntil = until === null ? null : new Date(Date.now() + offsetsMs[until]).toISOString()
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (c.id === conv.id ? { ...c, mutedUntil } : c)),
        )
      }
      return { previous }
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(
        data.mutedUntil === null
          ? 'Notifications unmuted'
          : data.mutedUntil !== null && Date.parse(data.mutedUntil) - Date.now() > 20 * 365 * 24 * 3600 * 1000
            ? 'Muted — always'
            : `Muted until ${formatListStamp(data.mutedUntil as string)}`,
      )
      setSheetConv(null)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not update the mute')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /** archive / unarchive — optimistic so the row moves instantly */
  const toggleArchive = useMutation({
    mutationFn: async ({ conv, archived }: { conv: ConversationSummary; archived: boolean }) => {
      return apiJson<{ ok: boolean; archived: boolean }>(
        `/api/conversations/${encodeURIComponent(conv.id)}/archive`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id, archived }),
        },
      )
    },
    onMutate: async ({ conv, archived }) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) =>
            c.id === conv.id ? { ...c, archivedAt: archived ? new Date().toISOString() : null } : c,
          ),
        )
      }
      return { previous }
    },
    onSuccess: (data) => {
      haptic(10)
      toast.success(data.archived ? 'Chat archived' : 'Chat unarchived')
      setSheetConv(null)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not update the archive')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /**
   * Clear chat — soft-delete MY OWN messages only (DELETE /api/messages/[id]
   * is sender-gated server-side, so other people's messages honestly stay).
   * Runs sequentially through the room's full real history.
   */
  const clearChat = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      const history = await fetchFullHistory(conv.id)
      const mine = history.filter((m) => m.senderId === me.id && m.deletedAt === null)
      let cleared = 0
      for (const message of mine) {
        try {
          await apiJson(`/api/messages/${encodeURIComponent(message.id)}`, {
            method: 'DELETE',
            body: JSON.stringify({ requesterId: me.id }),
          })
          cleared += 1
        } catch {
          // keep going — clear as many of my own messages as the server allows
        }
      }
      return { cleared, total: mine.length }
    },
    onSuccess: ({ cleared }) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      if (cleared === 0) {
        toast.info('Nothing to clear — none of your messages are left in this chat.')
      } else {
        toast.success(`Cleared ${cleared} ${cleared === 1 ? 'message' : 'messages'}`, {
          description: 'Your messages were deleted for everyone.',
        })
      }
      setSheetConv(null)
    },
    onError: () => {
      toast.error('Could not clear this chat')
    },
  })

  /** Export chat — real paginated history → pulse-<room>-<date>.txt download. */
  const exportChat = useMutation({
    mutationFn: async (conv: ConversationSummary) => {
      const history = await fetchFullHistory(conv.id)
      return downloadTranscript(conv, me.id, history)
    },
    onSuccess: (fileName) => {
      toast.success('Chat exported', { description: `Saved ${fileName}` })
    },
    onError: () => {
      toast.error('Could not export this chat')
    },
  })

  const openSheetFor = useCallback((conv: ConversationSummary) => setSheetConv(conv), [])

  // ── R34-a Telegram-style multi-select ────────────────────────
  // Long-press a row → select mode with that row checked; more taps
  // toggle; a floating glass bar runs Archive / Mute 8h / Mark read
  // over ALL selected rows through the SAME endpoints the row sheet
  // and swipe chips use (PATCH archive · PATCH mute · POST read).
  const [selectMode, setSelectMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set())

  const enterSelect = useCallback((conversationId: string) => {
    haptic(15)
    setSelectMode(true)
    setSelectedIds(new Set([conversationId]))
  }, [])

  const exitSelect = useCallback(() => {
    setSelectMode(false)
    setSelectedIds(new Set())
  }, [])

  const toggleSelect = useCallback((conversationId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(conversationId)) next.delete(conversationId)
      else next.add(conversationId)
      return next
    })
  }, [])

  // empty selection collapses the mode; opening search leaves it too
  useEffect(() => {
    if (selectMode && selectedIds.size === 0) setSelectMode(false)
  }, [selectMode, selectedIds])
  useEffect(() => {
    if (searching && selectMode) exitSelect()
  }, [searching, selectMode, exitSelect])

  /** the selected rows (active list only — select mode lives on the main list) */
  const selectedConvs = useMemo(
    () => activeRows.filter(({ conv }) => selectedIds.has(conv.id)).map(({ conv }) => conv),
    [activeRows, selectedIds],
  )

  /** Archive every selected chat — same PATCH /archive the sheet + swipe use. */
  const batchArchive = useMutation({
    mutationFn: async (convs: ConversationSummary[]) => {
      let archived = 0
      for (const conv of convs) {
        await apiJson<{ ok: boolean; archived: boolean }>(
          `/api/conversations/${encodeURIComponent(conv.id)}/archive`,
          {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: me.id, archived: conv.archivedAt === null }),
          },
        )
        archived += 1
      }
      return { archived }
    },
    onMutate: async (convs) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const ids = new Set(convs.map((c) => c.id))
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (ids.has(c.id) ? { ...c, archivedAt: new Date().toISOString() } : c)),
        )
      }
      return { previous }
    },
    onSuccess: ({ archived }) => {
      haptic(10)
      toast.success(`Archived ${archived} ${archived === 1 ? 'chat' : 'chats'}`)
      exitSelect()
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not archive the selected chats')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /** Mute every selected chat for 8 hours — same PATCH /mute preset the sheet uses. */
  const batchMute8h = useMutation({
    mutationFn: async (convs: ConversationSummary[]) => {
      let muted = 0
      for (const conv of convs) {
        await apiJson<{ ok: boolean; mutedUntil: string | null }>(
          `/api/conversations/${encodeURIComponent(conv.id)}/mute`,
          {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: me.id, until: '8h' }),
          },
        )
        muted += 1
      }
      return { muted }
    },
    onMutate: async (convs) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const ids = new Set(convs.map((c) => c.id))
        const mutedUntil = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString()
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (ids.has(c.id) ? { ...c, mutedUntil } : c)),
        )
      }
      return { previous }
    },
    onSuccess: ({ muted }) => {
      haptic(10)
      toast.success(`Muted ${muted} ${muted === 1 ? 'chat' : 'chats'} for 8 hours`)
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not mute the selected chats')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  /**
   * Mark every selected chat read — POST /read (the same endpoint the room
   * uses on open); optimistically zeroes unreadCount so the pills drop live.
   * Per-chat failures are counted honestly: full success toasts + clears the
   * selection, a partial failure toasts the misses and keeps the mode so the
   * remaining rows can be retried.
   */
  const batchMarkRead = useMutation({
    mutationFn: async (convs: ConversationSummary[]) => {
      let read = 0
      let failed = 0
      for (const conv of convs) {
        try {
          await apiJson<{ ok: boolean }>(
            `/api/conversations/${encodeURIComponent(conv.id)}/read`,
            {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ userId: me.id }),
            },
          )
          read += 1
        } catch {
          failed += 1 // keep going — mark as many as the server allows
        }
      }
      return { read, failed }
    },
    onMutate: async (convs) => {
      await queryClient.cancelQueries({ queryKey: ['conversations', me.id] })
      const previous = queryClient.getQueryData<ConversationSummary[]>(['conversations', me.id])
      if (previous) {
        const ids = new Set(convs.map((c) => c.id))
        queryClient.setQueryData<ConversationSummary[]>(
          ['conversations', me.id],
          previous.map((c) => (ids.has(c.id) ? { ...c, unreadCount: 0 } : c)),
        )
      }
      return { previous }
    },
    onSuccess: ({ read, failed }) => {
      haptic(10)
      if (read > 0) toast.success(`${read} ${read === 1 ? 'chat' : 'chats'} marked as read`)
      if (failed > 0) {
        toast.error(
          `${failed} ${failed === 1 ? 'chat' : 'chats'} could not be marked read — try again`,
        )
      } else {
        exitSelect()
      }
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData<ConversationSummary[]>(['conversations', me.id], context.previous)
      }
      toast.error('Could not mark the selected chats read')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  // ── R24-a Signal-style chat folders + Note to Self ─────────
  const reducedMotion = useReducedMotion()
  const [foldersOpen, setFoldersOpen] = useState(false)
  /** active rail folder (null = All) */
  const [activeFolderId, setActiveFolderId] = useState<string | null>(null)

  const foldersQ = useQuery({
    queryKey: ['folders', me.id],
    queryFn: async (): Promise<FolderSummary[]> => {
      const res = await apiJson<{ folders: FolderSummary[] }>(
        `/api/folders?userId=${encodeURIComponent(me.id)}`,
      )
      return res.folders
    },
    staleTime: 10_000,
  })
  const folders = useMemo(() => foldersQ.data ?? [], [foldersQ.data])

  // folder deleted (or lost) elsewhere → fall back to All
  useEffect(() => {
    if (activeFolderId !== null && !folders.some((f) => f.id === activeFolderId)) {
      setActiveFolderId(null)
    }
  }, [activeFolderId, folders])

  /** per-folder rail badge: its chats present in the active (non-archived) list */
  const folderCounts = useMemo(() => {
    const activeIds = new Set(activeRows.map(({ conv }) => conv.id))
    const counts = new Map<string, number>()
    for (const folder of folders) {
      counts.set(folder.id, folder.conversationIds.reduce((n, id) => (activeIds.has(id) ? n + 1 : n), 0))
    }
    return counts
  }, [folders, activeRows])

  /** rows of the currently active folder (rail filter on top of the Telegram filter) */
  const visibleRows = useMemo(() => {
    if (activeFolderId === null) return folderFiltered
    const ids = new Set(folders.find((f) => f.id === activeFolderId)?.conversationIds ?? [])
    return folderFiltered.filter(({ conv }) => ids.has(conv.id))
  }, [folderFiltered, activeFolderId, folders])

  /** the viewer's private Note to Self chat (null = not created yet) */
  const selfConv = useMemo(
    () => (conversations.data ?? []).find((conv) => conv.isSelf) ?? null,
    [conversations.data],
  )

  const createSelfChat = useMutation({
    mutationFn: async () =>
      apiJson<{ conversation: ConversationSummary }>('/api/conversations/self', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: me.id }),
      }),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      onOpenConversation(res.conversation.id, null)
    },
    onError: () => {
      toast.error('Could not open Note to Self')
    },
  })

  const handleSelfPress = useCallback(() => {
    haptic(6)
    if (selfConv) handlePress(selfConv)
    else createSelfChat.mutate()
  }, [selfConv, handlePress, createSelfChat])

  const data = conversations.data ?? []

  return (
    <div className="absolute inset-0 flex flex-col bg-white/30 dark:bg-zinc-950/20">
      {/* R32: root is translucent now — the ui-root aurora washes show through
          behind every row/pill, which is what makes the glass recipes read.
          (Was opaque bg-white/dark:bg-zinc-900: glass sat on a dead white.) */}
      {/* slow conic shimmer for unseen story rings — CSS, transform-only, honors reduced-motion */}
      <style>{`@keyframes pulse-story-spin{to{transform:rotate(360deg)}}.pulse-story-spin{animation:pulse-story-spin 6s linear infinite;will-change:transform}@media (prefers-reduced-motion:reduce){.pulse-story-spin{animation:none}}`}</style>
      {/* header */}
      <header className="shrink-0 border-b border-zinc-200 pt-[max(0px,env(safe-area-inset-top))] dark:border-zinc-800">
        {searching ? (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="flex items-center gap-2 px-3 py-2.5"
          >
            {/* glass pill — the search icon expands into the full input */}
            <motion.div
              initial={{ opacity: 0, scale: 0.96 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.2, ease: ease.out }}
              className="relative flex h-10 min-w-0 flex-1 items-center gap-2.5 rounded-full bg-zinc-100/80 px-4 ring-1 ring-zinc-200/70 backdrop-blur-xl dark:bg-zinc-900/60 dark:ring-white/10"
            >
              <motion.span
                aria-hidden
                animate={{ opacity: searchFocused ? 1 : 0 }}
                transition={spring.soft}
                className="pointer-events-none absolute inset-0 rounded-full shadow-[0_0_20px_rgba(16,185,129,0.25)] ring-2 ring-emerald-500/50"
              />
              <Search className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              <motion.div
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.2, ease: ease.out }}
                className="min-w-0 flex-1"
              >
                <Input
                  autoFocus
                  value={searchQuery}
                  onChange={(e) => handleSearchInput(e.target.value)}
                  onFocus={() => setSearchFocused(true)}
                  onBlur={() => setSearchFocused(false)}
                  placeholder="Search chats and messages…"
                  aria-label="Search conversations"
                  className="h-full border-0 bg-transparent p-0 text-sm shadow-none focus-visible:ring-0 dark:bg-transparent"
                />
              </motion.div>
              <AnimatePresence initial={false}>
                {searchQuery ? (
                  <motion.button
                    key="clear-search"
                    type="button"
                    initial={{ scale: 0, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    exit={{ scale: 0, opacity: 0 }}
                    transition={spring.bouncy}
                    onClick={() => handleSearchInput('')}
                    aria-label="Clear search"
                    className="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-300/70 text-zinc-600 outline-none dark:bg-zinc-700 dark:text-zinc-300"
                  >
                    <X className="size-3.5" aria-hidden />
                  </motion.button>
                ) : null}
              </AnimatePresence>
            </motion.div>
            <AnimatePresence initial={false} mode="popLayout">
              <motion.span
                key={`close-search-${searchFocused && searchQuery ? 'hot' : 'idle'}`}
                initial={{ opacity: 0, scale: 0.6 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.6 }}
                transition={spring.bouncy}
              >
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="Close search"
                  onClick={closeSearch}
                  className={cn(
                    'size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300',
                    searchFocused && searchQuery && 'text-emerald-600 dark:text-emerald-400',
                  )}
                >
                  <X className="size-5" aria-hidden />
                </Button>
              </motion.span>
            </AnimatePresence>
          </motion.div>
        ) : (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="flex items-center gap-2 px-3 py-2.5"
          >
            <motion.button
              type="button"
              aria-label="Open my profile"
              onClick={onGoProfile}
              whileTap={{ scale: 0.92 }}
              transition={pressSpring}
              className="rounded-full outline-none"
            >
              <UserAvatar name={me.name} color={me.color} size={36} />
            </motion.button>
            <h1 className="mr-auto flex items-center gap-1.5 pl-1 text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
              Pulse
              <span aria-hidden className="inline-block size-1.5 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600" />
            </h1>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Open calls"
              onClick={openCallsPage}
              className="size-10 rounded-full text-zinc-500 hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 dark:hover:text-emerald-400"
            >
              <Phone className="size-[19px]" aria-hidden />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="New chat"
              onClick={onRequestNewChat}
              className="size-10 rounded-full text-zinc-500 hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 dark:hover:text-emerald-400"
            >
              <SquarePen className="size-[19px]" aria-hidden />
            </Button>
            <ThemeToggleButton />
          </motion.div>
        )}
        {!searching ? (
          <motion.div
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="px-3 pb-2.5"
          >
            <motion.button
              type="button"
              aria-label="Start searching"
              onClick={() => setSearching(true)}
              whileTap={{ scale: 0.985 }}
              transition={pressSpring}
              className="flex h-10 w-full items-center gap-2.5 rounded-full bg-zinc-100/80 px-4 text-left outline-none ring-1 ring-zinc-200/70 backdrop-blur-xl transition-colors hover:bg-zinc-200/70 dark:bg-zinc-900/60 dark:ring-white/10 dark:hover:bg-zinc-800/70"
            >
              <Search className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              <span className="text-sm text-zinc-400 dark:text-zinc-500">Search chats and messages</span>
            </motion.button>
          </motion.div>
        ) : null}
      </header>

      {/* Telegram-style folder filter chips */}
      {!searching ? (
        <div className="flex shrink-0 items-center gap-1.5 overflow-x-auto px-3 pb-1.5 pt-1.5" role="tablist" aria-label="Chat filters">
          {([
            { key: 'all', label: 'All' },
            { key: 'unread', label: 'Unread' },
            { key: 'groups', label: 'Groups' },
          ] as Array<{ key: ChatsListFilter; label: string }>).map((f) => {
            const active = listFilter === f.key
            return (
              <motion.button
                key={f.key}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => {
                  haptic(6)
                  setListFilter(f.key)
                }}
                whileTap={pressTap}
                transition={pressSpring}
                className={cn(
                  'relative flex h-7 shrink-0 items-center rounded-full px-3 text-[12px] font-semibold outline-none transition-colors',
                  active
                    ? 'text-white'
                    : 'bg-zinc-100 text-zinc-500 hover:bg-zinc-200/70 dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-zinc-700',
                )}
              >
                {active ? (
                  <motion.span
                    layoutId="chats-filter-pill"
                    transition={spring.snappy}
                    className="absolute inset-0 rounded-full bg-emerald-500 shadow-sm shadow-emerald-600/25"
                  />
                ) : null}
                <span className="relative z-10 flex items-center gap-1">
                  {f.label === 'Unread' && unreadTotal > 0 && !active ? (
                    <span className="flex h-[15px] min-w-[15px] items-center justify-center rounded-full bg-emerald-500/20 px-1 text-[9px] font-bold text-emerald-600 dark:text-emerald-400">
                      {unreadTotal > 99 ? '99+' : unreadTotal}
                    </span>
                  ) : null}
                  {f.label}
                </span>
              </motion.button>
            )
          })}
        </div>
      ) : null}

      {/* 24h status stories row */}
      {!searching ? (
        <div className="shrink-0 border-b border-zinc-100 pb-2 pt-1 dark:border-zinc-800/70">
          <h2 className="sr-only">Status</h2>
          <div className="no-scrollbar flex snap-x snap-mandatory items-start gap-3 overflow-x-auto px-3 pt-1 [mask-image:linear-gradient(to_right,transparent_0,black_12px,black_calc(100%-12px),transparent_100%)]">
            <StoryRingCell
              name={me.name}
              color={me.color}
              ring={myStoryGroup ? 'unseen' : 'none'}
              plus={!myStoryGroup}
              label="My status"
              onPress={openMyStatus}
              index={0}
            />
            {otherStoryGroups.map((group, i) => (
              <StoryRingCell
                key={group.user.id}
                name={group.user.name}
                color={group.user.color}
                ring={group.allSeen ? 'seen' : 'unseen'}
                label={group.user.name}
                onPress={() => openStoryGroup(group)}
                index={i + 1}
              />
            ))}
          </div>
        </div>
      ) : null}

      {/* R24-a Signal-style chat folder rail */}
      {!searching ? (
        <div
          className="no-scrollbar flex shrink-0 items-center gap-1.5 overflow-x-auto px-3 pb-1.5 pt-0.5"
          role="tablist"
          aria-label="Chat folders"
        >
          <motion.button
            type="button"
            role="tab"
            aria-selected={activeFolderId === null}
            aria-label="All chats"
            onClick={() => {
              haptic(6)
              setActiveFolderId(null)
            }}
            whileTap={reducedMotion ? undefined : pressTap}
            transition={pressSpring}
            className={cn(
              'relative flex h-11 shrink-0 items-center rounded-full px-4 text-[13px] font-semibold outline-none transition-colors',
              activeFolderId === null
                ? 'text-white'
                : 'bg-white/70 text-zinc-600 ring-1 ring-zinc-200/70 backdrop-blur-xl hover:bg-zinc-100 dark:bg-zinc-900/60 dark:text-zinc-300 dark:ring-white/10 dark:hover:bg-zinc-800/70',
            )}
          >
            {activeFolderId === null ? (
              <motion.span
                layoutId="folders-rail-pill"
                transition={spring.snappy}
                className="absolute inset-0 rounded-full bg-emerald-500 shadow-sm shadow-emerald-600/25"
              />
            ) : null}
            <span className="relative z-10">All</span>
          </motion.button>
          {folders.map((folder) => {
            const active = activeFolderId === folder.id
            const count = folderCounts.get(folder.id) ?? 0
            return (
              <motion.button
                key={folder.id}
                type="button"
                role="tab"
                aria-selected={active}
                aria-label={`Folder ${folder.name} — ${count} ${count === 1 ? 'chat' : 'chats'}`}
                onClick={() => {
                  haptic(6)
                  setActiveFolderId(active ? null : folder.id)
                }}
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                className={cn(
                  'relative flex h-11 shrink-0 items-center rounded-full px-3.5 text-[13px] font-semibold outline-none transition-colors',
                  active
                    ? 'text-white'
                    : 'bg-white/70 text-zinc-600 ring-1 ring-zinc-200/70 backdrop-blur-xl hover:bg-zinc-100 dark:bg-zinc-900/60 dark:text-zinc-300 dark:ring-white/10 dark:hover:bg-zinc-800/70',
                )}
              >
                {active ? (
                  <motion.span
                    layoutId="folders-rail-pill"
                    transition={spring.snappy}
                    className="absolute inset-0 rounded-full bg-emerald-500 shadow-sm shadow-emerald-600/25"
                  />
                ) : null}
                <span className="relative z-10 flex items-center gap-1.5">
                  <span aria-hidden>{folder.emoji}</span>
                  <span className="max-w-[96px] truncate">{folder.name}</span>
                  {count > 0 ? (
                    <span
                      className={cn(
                        'flex h-[15px] min-w-[15px] items-center justify-center rounded-full px-1 text-[9px] font-bold',
                        active
                          ? 'bg-white/25 text-white'
                          : 'bg-emerald-500/20 text-emerald-600 dark:text-emerald-400',
                      )}
                    >
                      {count > 99 ? '99+' : count}
                    </span>
                  ) : null}
                </span>
              </motion.button>
            )
          })}
          <motion.button
            type="button"
            aria-label="Manage chat folders"
            onClick={() => {
              haptic(6)
              setFoldersOpen(true)
            }}
            whileTap={reducedMotion ? undefined : pressTap}
            transition={pressSpring}
            className="flex size-11 shrink-0 items-center justify-center rounded-full bg-white/70 text-zinc-500 ring-1 ring-zinc-200/70 backdrop-blur-xl outline-none transition-colors hover:bg-zinc-100 hover:text-emerald-600 focus-visible:ring-2 focus-visible:ring-emerald-500/60 dark:bg-zinc-900/60 dark:text-zinc-400 dark:ring-white/10 dark:hover:bg-zinc-800/70 dark:hover:text-emerald-400"
          >
            <FolderPlus className="size-[18px]" aria-hidden />
          </motion.button>
        </div>
      ) : null}

      {/* list */}
      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-4">
        {conversations.isPending ? (
          <div role="status" aria-label="Loading conversations" className="pt-2">
            <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
          </div>
        ) : searching ? (
          <div className="py-1">
            {filteredRows.length > 0 ? (
              <>
                <SearchSection label="Chats" count={filteredRows.length} />
                {filteredRows.map(({ conv, props }, i) => (
                  <ConversationRow
                    key={props.id}
                    {...props}
                    entranceIndex={entranceOn ? i : null}
                    onPress={() => handlePress(conv)}
                    onLongPress={() => openSheetFor(conv)}
                    onOptions={() => openSheetFor(conv)}
                    onPin={() => togglePin.mutate(conv)}
                    onArchive={() => toggleArchive.mutate({ conv, archived: conv.archivedAt === null })}
                  />
                ))}
              </>
            ) : null}

            {deferredQuery.length >= 2 ? (
              serverSearch.isPending ? (
                <div className="flex items-center justify-center gap-2 py-6" role="status" aria-label="Searching messages">
                  <LoaderCircle className="size-4 animate-spin text-emerald-500" aria-hidden />
                  <span className="text-xs font-medium text-zinc-400 dark:text-zinc-500">Searching messages…</span>
                </div>
              ) : serverHits.length > 0 ? (
                <>
                  <SearchSection label="Messages" count={serverSearch.data?.total ?? serverHits.length} />
                  {serverHits.map((hit) => (
                    <SearchMessageRow
                      key={hit.id}
                      hit={hit}
                      query={deferredQuery}
                      onPress={(h) => onOpenConversation(h.conversationId, null, h.id)}
                    />
                  ))}
                </>
              ) : null
            ) : deferredQuery.length > 0 ? (
              <p className="px-4 pt-3 text-center text-xs text-zinc-400 dark:text-zinc-500">
                Keep typing to search inside messages…
              </p>
            ) : null}

            {filteredRows.length === 0 &&
            (deferredQuery.length < 2 || (!serverSearch.isPending && serverHits.length === 0)) ? (
              <div className="flex flex-col items-center justify-center gap-2 px-8 pt-24 text-center">
                <Search className="size-8 text-zinc-300 dark:text-zinc-600" aria-hidden />
                <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">No matches</p>
                <p className="text-xs text-zinc-400 dark:text-zinc-500">
                  Nothing here for “{searchQuery}”.
                </p>
              </div>
            ) : null}
          </div>
        ) : rows.length === 0 ? (
          <EmptyChats onSayHi={onOpenContacts} />
        ) : (
          <div className="py-1">
            {/* R24-a Note to Self — private notebook chat (above regular chats).
                R32: the hero row now wears the full reference glass — deep panel,
                diagonal sheen, specular rim — with a Lucide glyph in a glowing
                tile (emoji retired per the no-emoji rule). */}
            <motion.button
              type="button"
              onClick={handleSelfPress}
              whileTap={reducedMotion ? undefined : { scale: 0.985 }}
              transition={pressSpring}
              aria-label={
                selfConv
                  ? 'Open Note to Self — your private space'
                  : 'Create Note to Self — your private space'
              }
              className="glass-deep glass-sheen mx-2 mb-1 mt-0.5 flex items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none transition-colors hover:border-emerald-500/40"
            >
              <span
                aria-hidden
                className="relative flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-sm shadow-emerald-600/30"
              >
                <NotebookPen className="size-[17px]" aria-hidden />
                <span className="pointer-events-none absolute inset-0 rounded-xl bg-[radial-gradient(80%_60%_at_50%_0%,rgba(255,255,255,0.55),transparent_70%)]" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[14px] font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
                  Note to Self
                </span>
                <span className="block truncate text-[11.5px] text-zinc-500 dark:text-zinc-400">
                  Your private space — notes, links, ideas
                </span>
              </span>
              {createSelfChat.isPending ? (
                <LoaderCircle className="size-4 shrink-0 animate-spin text-emerald-500" aria-hidden />
              ) : (
                <span
                  className={cn(
                    'flex shrink-0 items-center gap-0.5 rounded-full py-1 pl-2 pr-1 text-[11px] font-bold',
                    selfConv
                      ? 'text-emerald-600 dark:text-emerald-400'
                      : 'bg-emerald-500 text-white shadow-sm shadow-emerald-600/30',
                  )}
                >
                  {selfConv ? 'Open' : 'Create'}
                  <ChevronRight className="size-3.5" aria-hidden />
                </span>
              )}
            </motion.button>
            {/* R35-b — Mentions entry: real @mention feed → #/mentions */}
            <motion.button
              type="button"
              whileTap={reducedMotion ? undefined : { scale: 0.985 }}
              transition={pressSpring}
              onClick={openMentionsPage}
              aria-label={`Open mentions — ${mentionCount}`}
              className="glass-pill mx-2 my-1 flex h-11 w-[calc(100%-16px)] items-center gap-2.5 px-3.5 text-left outline-none"
            >
              <AtSign className="size-[18px] shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
              <span className="text-[13px] font-semibold text-zinc-700 dark:text-zinc-200">Mentions</span>
              {mentionCount > 0 ? (
                <span className="flex h-[17px] min-w-[17px] items-center justify-center rounded-full bg-emerald-500 px-1 text-[10px] font-bold text-white">
                  {mentionCount > 99 ? '99+' : mentionCount}
                </span>
              ) : null}
              <span className="ml-auto flex items-center gap-0.5 text-xs text-zinc-400 dark:text-zinc-500">
                {mentionCount === 1 ? '1 mention' : `${mentionCount} mentions`}
                <ChevronRight className="size-3.5" aria-hidden />
              </span>
            </motion.button>
            {/* R30-c — Channels entry: subscribed count → #/chats/channels */}
            <motion.button
              type="button"
              whileTap={reducedMotion ? undefined : { scale: 0.985 }}
              transition={pressSpring}
              onClick={openChannelsPage}
              aria-label={`Open channels — ${subscribedChannelCount} subscribed`}
              className="glass-pill mx-2 my-1 flex h-11 w-[calc(100%-16px)] items-center gap-2.5 px-3.5 text-left outline-none"
            >
              <Radio className="size-[18px] shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
              <span className="text-[13px] font-semibold text-zinc-700 dark:text-zinc-200">Channels</span>
              <span className="ml-auto flex items-center gap-0.5 text-xs text-zinc-400 dark:text-zinc-500">
                {subscribedChannelCount === 1 ? '1 channel' : `${subscribedChannelCount} channels`}
                <ChevronRight className="size-3.5" aria-hidden />
              </span>
            </motion.button>
            {/* R27-e — Archived entry: real count, always reachable → #/chats/archived */}
            <motion.button
              type="button"
              whileTap={reducedMotion ? undefined : { scale: 0.985 }}
              transition={pressSpring}
              onClick={openArchivedPage}
              aria-label={`Open archived chats — ${archivedRows.length}`}
              className="glass-pill mx-2 my-1 flex h-11 w-[calc(100%-16px)] items-center gap-2.5 px-3.5 text-left outline-none"
            >
              <Archive className="size-[18px] shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
              <span className="text-[13px] font-semibold text-zinc-700 dark:text-zinc-200">Archived</span>
              {archivedUnread > 0 ? (
                <span className="flex h-[17px] min-w-[17px] items-center justify-center rounded-full bg-emerald-500 px-1 text-[10px] font-bold text-white">
                  {archivedUnread > 99 ? '99+' : archivedUnread}
                </span>
              ) : null}
              <span className="ml-auto flex items-center gap-0.5 text-xs text-zinc-400 dark:text-zinc-500">
                {archivedRows.length === 1 ? '1 chat' : `${archivedRows.length} chats`}
                <ChevronRight className="size-3.5" aria-hidden />
              </span>
            </motion.button>
            {/* folder switch springs the whole list block (R24-a) */}
            <motion.div
              key={activeFolderId ?? 'all'}
              initial={reducedMotion ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={spring.soft}
              style={{ willChange: 'transform' }}
            >
              {/* R32 rhythm: grouped list like the refs — PINNED block, then
                  ALL CHATS, separated by the tiny uppercase glass labels. */}
              {visibleRows.some(({ props }) => props.pinned) ? (
                <SearchSection label="Pinned" count={visibleRows.filter(({ props }) => props.pinned).length} />
              ) : null}
              {visibleRows.filter(({ props }) => props.pinned).map(({ conv, props }, i) => (
                <ConversationRow
                  key={props.id}
                  {...props}
                  entranceIndex={entranceOn ? i : null}
                  selectMode={selectMode}
                  selected={selectedIds.has(props.id)}
                  onPress={() => handlePress(conv)}
                  onLongPress={() => enterSelect(props.id)}
                  onToggleSelect={() => toggleSelect(props.id)}
                  onOptions={() => openSheetFor(conv)}
                  onPin={() => togglePin.mutate(conv)}
                  onArchive={() => toggleArchive.mutate({ conv, archived: conv.archivedAt === null })}
                />
              ))}
              {visibleRows.some(({ props }) => props.pinned) ? (
                <SearchSection label="All chats" count={visibleRows.filter(({ props }) => !props.pinned).length} />
              ) : null}
              {visibleRows.filter(({ props }) => !props.pinned).map(({ conv, props }, i) => (
                <ConversationRow
                  key={props.id}
                  {...props}
                  entranceIndex={entranceOn ? i : null}
                  selectMode={selectMode}
                  selected={selectedIds.has(props.id)}
                  onPress={() => handlePress(conv)}
                  onLongPress={() => enterSelect(props.id)}
                  onToggleSelect={() => toggleSelect(props.id)}
                  onOptions={() => openSheetFor(conv)}
                  onPin={() => togglePin.mutate(conv)}
                  onArchive={() => toggleArchive.mutate({ conv, archived: conv.archivedAt === null })}
                />
              ))}
            </motion.div>
            {visibleRows.length === 0 && activeRows.length > 0 ? (
              <p className="px-8 pb-4 pt-10 text-center text-[13px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                {activeFolderId !== null
                  ? 'This folder is empty — tap the folder button on the rail to add chats.'
                  : listFilter === 'unread'
                    ? 'No unread chats — you are all caught up.'
                    : 'No groups yet — start one from Contacts.'}
              </p>
            ) : null}
            {activeRows.length === 0 && archivedRows.length > 0 ? (
              <p className="px-8 pb-4 pt-10 text-center text-[13px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                Every chat is archived.
                <br />
                New messages bring chats back here.
              </p>
            ) : null}
          </div>
        )}
      </div>

      {/* R34-a — Telegram-style floating glass bar over the selected rows.
          Staggered entrance; Archive exits the mode (rows leave the list),
          Mute 8h / Mark read keep it so the operator can keep working. */}
      <AnimatePresence>
        {selectMode ? (
          <motion.div
            key="multi-select-bar"
            initial={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 28, scale: 0.92 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.94 }}
            transition={spring.snappy}
            className="pointer-events-none absolute inset-x-0 bottom-[86px] z-40 flex justify-center px-3"
          >
            <div
              role="toolbar"
              aria-label={`Actions for ${selectedIds.size} selected ${selectedIds.size === 1 ? 'chat' : 'chats'}`}
              className="glass-deep glass-sheen pointer-events-auto flex items-center gap-0.5 rounded-full p-1.5 shadow-xl ring-1 ring-white/40 dark:ring-white/10"
            >
              <span className="ml-1.5 mr-1 shrink-0 text-[12px] font-bold tabular-nums text-zinc-600 dark:text-zinc-300">
                {selectedIds.size} selected
              </span>
              {([
                {
                  key: 'archive',
                  icon: Archive,
                  label: 'Archive selected chats',
                  short: 'Archive',
                  onClick: () => batchArchive.mutate(selectedConvs),
                  pending: batchArchive.isPending,
                },
                {
                  key: 'mute',
                  icon: BellOff,
                  label: 'Mute selected chats for 8 hours',
                  short: 'Mute 8h',
                  onClick: () => batchMute8h.mutate(selectedConvs),
                  pending: batchMute8h.isPending,
                },
                {
                  key: 'read',
                  icon: CheckCheck,
                  label: 'Mark selected chats read',
                  short: 'Mark read',
                  onClick: () => batchMarkRead.mutate(selectedConvs),
                  pending: batchMarkRead.isPending,
                },
              ] as const).map((action, i) => (
                <motion.button
                  key={action.key}
                  type="button"
                  aria-label={action.label}
                  disabled={action.pending || selectedConvs.length === 0}
                  onClick={action.onClick}
                  initial={reducedMotion ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ ...spring.soft, delay: reducedMotion ? 0 : 0.05 + i * 0.045 }}
                  whileTap={reducedMotion ? undefined : pressTap}
                  className="flex size-10 items-center justify-center rounded-full text-zinc-600 outline-none transition-colors hover:bg-emerald-500/15 hover:text-emerald-700 disabled:opacity-40 dark:text-zinc-300 dark:hover:text-emerald-300"
                >
                  {action.pending ? (
                    <LoaderCircle className="size-[18px] animate-spin" aria-hidden />
                  ) : (
                    <action.icon className="size-[18px]" aria-hidden />
                  )}
                  <span className="sr-only">{action.short}</span>
                </motion.button>
              ))}
              <motion.button
                type="button"
                aria-label="Exit multi-select"
                onClick={exitSelect}
                initial={reducedMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...spring.soft, delay: reducedMotion ? 0 : 0.2 }}
                whileTap={reducedMotion ? undefined : pressTap}
                className="ml-0.5 flex size-10 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-500/10 hover:text-zinc-700 dark:hover:text-zinc-200"
              >
                <X className="size-[18px]" aria-hidden />
              </motion.button>
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>

      {/* long-press glass option menu — pin / archive / mute strip / export / clear */}
      <ChatOptionsSheet
        conv={sheetConv}
        me={me}
        pinPending={togglePin.isPending}
        archivePending={toggleArchive.isPending}
        mutePending={toggleMute.isPending}
        clearPending={clearChat.isPending}
        exportPending={exportChat.isPending}
        manualUnread={sheetConv?.myManualUnread ?? false}
        markUnreadPending={toggleMarkUnread.isPending}
        onPin={() => {
          if (sheetConv) togglePin.mutate(sheetConv)
        }}
        onArchive={() => {
          if (sheetConv) toggleArchive.mutate({ conv: sheetConv, archived: sheetConv.archivedAt === null })
        }}
        onMarkUnread={() => {
          if (sheetConv) toggleMarkUnread.mutate(sheetConv)
        }}
        onMute={(until) => {
          if (sheetConv) toggleMute.mutate({ conv: sheetConv, until })
        }}
        onUnmute={() => {
          if (sheetConv) toggleMute.mutate({ conv: sheetConv, until: null })
        }}
        onExport={() => {
          if (sheetConv) exportChat.mutate(sheetConv)
        }}
        onClear={() => {
          if (sheetConv) clearChat.mutate(sheetConv)
        }}
        onClose={() => setSheetConv(null)}
      />

      {/* #/chats/archived — real hash-routed glass sub-page */}
      <ChatsArchivedPage
        open={archivedPageOpen}
        me={me}
        rows={archivedRows}
        loading={conversations.isPending}
        entrance={entranceOn}
        onBack={() => back('/')}
        onPress={handlePress}
        onLongPress={openSheetFor}
        onPin={(conv) => togglePin.mutate(conv)}
        onArchive={(conv) => toggleArchive.mutate({ conv, archived: conv.archivedAt === null })}
      />

      {/* #/chats/channels — R30-c broadcast directory sub-page */}
      <ChannelsPage
        open={channelsPageOpen}
        me={me}
        onBack={() => back('/')}
        onOpenConversation={(conversationId) => onOpenConversation(conversationId, null)}
      />

      {/* #/calls — R34-a WhatsApp 'Calls' history sub-page */}
      <CallsPage
        open={callsPageOpen}
        me={me}
        onBack={() => back('/')}
        onOpenConversation={(conversationId) => onOpenConversation(conversationId, null)}
      />

      {/* #/mentions — R35-b Discord-style @mention feed sub-page */}
      <MentionsPage
        open={mentionsPageOpen}
        me={me}
        onBack={() => back('/')}
        onOpenConversation={(conversationId) => onOpenConversation(conversationId, null)}
      />

      {/* R24-a chat folders manager sheet */}
      <FoldersSheet
        open={foldersOpen}
        onClose={() => setFoldersOpen(false)}
        me={me}
        conversations={data}
      />

      {/* status story viewer + composer (full-screen overlays) */}
      <AnimatePresence>
        {composerOpen ? (
          <StoryComposerSheet key="story-composer" me={me} onClose={() => setComposerOpen(false)} />
        ) : null}
        {viewerStart !== null && storyGroups.length > 0 ? (
          <StoriesSheet
            key="stories-viewer"
            me={me}
            groups={storyGroups}
            start={viewerStart}
            onClose={() => {
              setViewerStart(null)
              void queryClient.invalidateQueries({ queryKey: storiesQueryKey(me.id) })
            }}
          />
        ) : null}
      </AnimatePresence>
    </div>
  )
}

function EmptyChats({ onSayHi }: { onSayHi: () => void }) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.div
      initial={reducedMotion ? false : { opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: ease.out }}
      className="flex flex-col items-center justify-center px-4 pt-10 text-center"
    >
      {/* R32 empty state in the reference language: layered deep glass with a
          soft emerald icon-glow blooming behind the illustration. */}
      <div className="glass-deep glass-sheen relative flex w-full max-w-[300px] flex-col items-center gap-4 rounded-[28px] px-6 py-8">
        <span
          aria-hidden
          className="pointer-events-none absolute -top-10 left-1/2 size-40 -translate-x-1/2 rounded-full bg-[radial-gradient(circle,rgba(16,185,129,0.28),transparent_65%)] blur-md"
        />
        <Image
          src="/empty-chats.png"
          alt="No conversations illustration"
          width={144}
          height={144}
          className="relative rounded-3xl shadow-md shadow-zinc-200/70 ring-1 ring-white/50 dark:shadow-none dark:ring-white/10"
        />
        <div className="relative">
          <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
            No conversations yet
          </h2>
          <p className="mx-auto mt-1 max-w-[240px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
            Your next great chat is one tap away. Find someone and break the ice.
          </p>
        </div>
        <Button
          onClick={onSayHi}
          variant="outline"
          className="glass-pill relative h-10 gap-1.5 rounded-full border-emerald-500/40 px-5 text-sm font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 dark:text-emerald-400 dark:hover:text-emerald-400 active:scale-[0.98]"
        >
          Say hi to someone
          <ArrowRight className="size-4" aria-hidden />
        </Button>
      </div>
    </motion.div>
  )
}

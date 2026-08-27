// ─────────────────────────────────────────────────────────────
// Pulse Chat — Chats tab: conversation list, search, empty state.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useCallback, useMemo, useRef, useState } from 'react'
import Image from 'next/image'
import { motion } from 'framer-motion'
import { useStore } from 'zustand'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, ArrowRight, BellOff, ChevronRight, LoaderCircle, MoreVertical, PencilLine, Pin, PinOff, Search, SquarePen, Users, VolumeX, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary, SearchResultMessage } from '@/lib/types'
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
import { haptic } from '@/lib/pulse-settings'
import type { ChatsListFilter } from '@/lib/pulse-settings'
import { pulseSettingsStore } from '@/lib/pulse-settings'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { ThemeToggleButton } from '@/components/chat/theme-toggle'

interface ConversationsResponse {
  conversations: ConversationSummary[]
}

interface SearchResponse {
  messages: SearchResultMessage[]
  total: number
}

interface ConversationRowProps {
  id: string
  isGroup: boolean
  name: string
  time: string
  preview: string
  previewPrefix: string
  previewDeleted: boolean
  /** unsent composer draft persisted for this conversation (null = none) */
  draft: string | null
  unreadCount: number
  // avatar inputs
  dmName: string | null
  dmColor: string
  groupTitle: string
  online: boolean
  pinned: boolean
  /** viewer muted this conversation (watermark in the future) */
  muted: boolean
  /** someone is typing in this conversation right now */
  typing: boolean
  onPress: () => void
  onLongPress: () => void
}

const LONG_PRESS_MS = 450

const ConversationRow = memo(function ConversationRow({
  id,
  isGroup,
  name,
  time,
  preview,
  previewPrefix,
  previewDeleted,
  draft,
  unreadCount,
  dmName,
  dmColor,
  groupTitle,
  online,
  pinned,
  muted,
  typing,
  onPress,
  onLongPress,
}: ConversationRowProps) {
  const hasUnread = unreadCount > 0
  const longPressRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const longPressFiredRef = useRef(false)

  const clearLongPress = useCallback(() => {
    if (longPressRef.current !== null) {
      clearTimeout(longPressRef.current)
      longPressRef.current = null
    }
  }, [])

  const startLongPress = useCallback(() => {
    clearLongPress()
    longPressFiredRef.current = false
    longPressRef.current = setTimeout(() => {
      longPressFiredRef.current = true
      longPressRef.current = null
      haptic(15)
      onLongPress()
    }, LONG_PRESS_MS)
  }, [clearLongPress, onLongPress])

  const handleClick = useCallback(() => {
    if (!longPressFiredRef.current) onPress()
    longPressFiredRef.current = false
  }, [onPress])

  return (
    <div className="group relative px-2">
      <button
        type="button"
        onClick={handleClick}
        onPointerDown={startLongPress}
        onPointerUp={clearLongPress}
        onPointerLeave={clearLongPress}
        onContextMenu={(e) => e.preventDefault()}
        className={cn(
          'flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 text-left outline-none transition-colors active:bg-zinc-100 dark:active:bg-zinc-800',
          pinned && 'bg-emerald-500/[0.045] dark:bg-emerald-500/[0.06]',
        )}
      >
        {isGroup ? (
          <GroupAvatar title={groupTitle} id={id} size={48} />
        ) : (
          <UserAvatar name={dmName ?? name} color={dmColor} size={48} showPresence online={online} />
        )}

        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="flex min-w-0 items-center gap-1">
              {pinned ? (
                <Pin className="size-3 shrink-0 fill-emerald-500 text-emerald-500" aria-label="Pinned" />
              ) : null}
              <span
                className={cn(
                  'truncate text-[15px] tracking-tight',
                  hasUnread
                    ? 'font-semibold text-zinc-900 dark:text-zinc-50'
                    : 'font-medium text-zinc-900 dark:text-zinc-100',
                )}
              >
                {name}
              </span>
            </span>
            <span
              className={cn(
                'shrink-0 text-[11px]',
                hasUnread
                  ? 'font-semibold text-emerald-600 dark:text-emerald-400'
                  : 'text-zinc-400 dark:text-zinc-500',
              )}
            >
              {time}
            </span>
          </div>
          <div className="mt-0.5 flex items-center justify-between gap-2">
            {typing ? (
              <p className="flex min-w-0 items-center gap-1.5 text-[13px] font-medium italic text-emerald-600 dark:text-emerald-400">
                <span className="inline-flex items-center gap-0.5" aria-hidden>
                  {[0, 1, 2].map((i) => (
                    <motion.span
                      key={i}
                      animate={{ y: [0, -2.5, 0], opacity: [0.45, 1, 0.45] }}
                      transition={{ repeat: Infinity, duration: 0.9, delay: i * 0.15, ease: 'easeInOut' }}
                      className="size-[3.5px] rounded-full bg-emerald-500"
                    />
                  ))}
                </span>
                typing…
              </p>
            ) : draft ? (
              <p className="flex min-w-0 items-center gap-1 truncate text-[13px]">
                <PencilLine className="size-3 shrink-0 text-amber-500" aria-hidden />
                <span className="shrink-0 font-semibold text-amber-600 dark:text-amber-400">Draft:</span>
                <span className="truncate italic text-zinc-500 dark:text-zinc-400">{draft}</span>
              </p>
            ) : (
              <p
                className={cn(
                  'truncate text-[13px]',
                  hasUnread
                    ? 'font-medium text-zinc-600 dark:text-zinc-300'
                    : 'text-zinc-500 dark:text-zinc-400',
                )}
              >
                {previewPrefix ? <span className="text-zinc-400 dark:text-zinc-500">{previewPrefix}</span> : null}
                <span className={previewDeleted ? 'italic' : undefined}>{preview}</span>
              </p>
            )}
            {hasUnread && !muted ? (
              <motion.span
                initial={{ scale: 0 }}
                animate={{ scale: 1 }}
                transition={{ type: 'spring', stiffness: 520, damping: 20 }}
                className="flex h-[18px] min-w-[18px] shrink-0 items-center justify-center rounded-full bg-emerald-500 px-1.5 text-[10px] font-bold text-white shadow-sm shadow-emerald-600/40 ring-2 ring-white dark:ring-zinc-900"
              >
                {unreadCount > 99 ? '99+' : unreadCount}
              </motion.span>
            ) : muted ? (
              <motion.span
                initial={{ scale: 0.6, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                transition={{ type: 'spring', stiffness: 520, damping: 24 }}
                aria-label={hasUnread ? `Muted — ${unreadCount} unread` : 'Muted'}
                className={cn(
                  'flex h-[18px] shrink-0 items-center gap-1 rounded-full px-1.5 text-[10px] font-bold ring-2 ring-white dark:ring-zinc-900',
                  hasUnread
                    ? 'bg-zinc-300 text-zinc-600 dark:bg-zinc-700 dark:text-zinc-300'
                    : 'bg-transparent text-zinc-400 ring-0 dark:text-zinc-500',
                )}
              >
                <BellOff className="size-3.5" aria-hidden />
                {hasUnread ? (unreadCount > 99 ? '99+' : unreadCount) : null}
              </motion.span>
            ) : null}
          </div>
        </div>
      </button>
      <button
        type="button"
        aria-label={`Options for ${name}`}
        onClick={(e) => {
          e.stopPropagation()
          onLongPress()
        }}
        className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full bg-white/90 p-1.5 text-zinc-400 opacity-0 shadow-sm outline-none backdrop-blur transition-opacity hover:text-zinc-600 focus-visible:opacity-100 group-hover:opacity-100 dark:bg-zinc-800/90 dark:hover:text-zinc-200"
      >
        <MoreVertical className="size-4" aria-hidden />
      </button>
      <div aria-hidden className="ml-[64px] h-px bg-zinc-100 dark:bg-zinc-800" />
    </div>
  )
})

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
  return (
    <motion.button
      type="button"
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.16 }}
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
        ) : (
          <SearchSnippet content={hit.content} query={query} />
        )}
      </span>
    </motion.button>
  )
})

function RowSkeleton() {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <Skeleton className="size-12 rounded-full" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-3.5 w-1/3" />
        <Skeleton className="h-3 w-2/3" />
      </div>
    </div>
  )
}

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
  const [searching, setSearching] = useState(false)
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

  // live drafts → "Draft: …" previews in the list (zustand external store)
  const allDrafts = useStore(pulseDraftsStore, (s) => s.drafts)

  const rows = useMemo(() => {
    return (conversations.data ?? []).map((conv) => {
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
          dmName: other?.name ?? null,
          dmColor: other?.color ?? 'emerald',
          groupTitle: groupName,
          online: !conv.isGroup && other !== null && onlineIds.has(other.id),
          pinned: conv.pinnedAt !== null,
          muted: conv.mutedUntil !== null && Date.parse(conv.mutedUntil) > Date.now(),
          typing: typersIn(conv.id, me.id).length > 0,
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
  /** archived-chats drawer (WhatsApp-style) */
  const [archivedOpen, setArchivedOpen] = useState(false)

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

  /** per-user notification mute — '8h' | '1w' | 'always' | null */
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
    onError: () => {
      toast.error('Could not update the mute')
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
      // unarchive → the chat went back to the inbox; leave the archived drawer
      if (!data.archived) setArchivedOpen(false)
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

  const openSheetFor = useCallback((conv: ConversationSummary) => setSheetConv(conv), [])

  const sheetMuted =
    sheetConv !== null &&
    sheetConv.mutedUntil !== null &&
    Date.parse(sheetConv.mutedUntil) > Date.now()

  const data = conversations.data ?? []

  return (
    <div className="absolute inset-0 flex flex-col bg-white dark:bg-zinc-900">
      {/* header */}
      <header className="shrink-0 border-b border-zinc-200 pt-[max(0px,env(safe-area-inset-top))] dark:border-zinc-800">
        {searching ? (
          <div className="flex items-center gap-2 px-3 py-2.5">
            <Input
              autoFocus
              value={searchQuery}
              onChange={(e) => handleSearchInput(e.target.value)}
              placeholder="Search chats and messages…"
              aria-label="Search conversations"
              className="h-10 flex-1 rounded-full border-zinc-200 bg-zinc-100 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
            />
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close search"
              onClick={closeSearch}
              className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
            >
              <X className="size-5" aria-hidden />
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-2 px-3 py-2.5">
            <button
              type="button"
              aria-label="Open my profile"
              onClick={onGoProfile}
              className="rounded-full outline-none transition-transform active:scale-95"
            >
              <UserAvatar name={me.name} color={me.color} size={36} />
            </button>
            <h1 className="mr-auto flex items-center gap-1.5 pl-1 text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
              Pulse
              <span aria-hidden className="inline-block size-1.5 rounded-full bg-gradient-to-br from-emerald-400 to-emerald-600" />
            </h1>
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
          </div>
        )}
        {!searching ? (
          <div className="px-3 pb-2.5">
            <button
              type="button"
              aria-label="Start searching"
              onClick={() => setSearching(true)}
              className="flex h-10 w-full items-center gap-2.5 rounded-full bg-zinc-100 px-4 text-left outline-none ring-emerald-500/60 transition-colors hover:bg-zinc-200/70 focus-visible:ring-2 active:scale-[0.99] dark:bg-zinc-800 dark:hover:bg-zinc-700/70"
            >
              <Search className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              <span className="text-sm text-zinc-400 dark:text-zinc-500">Search chats and messages</span>
            </button>
          </div>
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
              <button
                key={f.key}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => {
                  haptic(6)
                  setListFilter(f.key)
                }}
                className={cn(
                  'flex h-7 shrink-0 items-center gap-1 rounded-full px-3 text-[12px] font-semibold outline-none transition-all active:scale-95',
                  active
                    ? 'bg-emerald-500 text-white shadow-sm shadow-emerald-600/25'
                    : 'bg-zinc-100 text-zinc-500 hover:bg-zinc-200/70 dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-zinc-700',
                )}
              >
                {f.label === 'Unread' && unreadTotal > 0 && !active ? (
                  <span className="flex h-[15px] min-w-[15px] items-center justify-center rounded-full bg-emerald-500/20 px-1 text-[9px] font-bold text-emerald-600 dark:text-emerald-400">
                    {unreadTotal > 99 ? '99+' : unreadTotal}
                  </span>
                ) : null}
                {f.label}
              </button>
            )
          })}
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
                {filteredRows.map(({ conv, props }) => (
                  <ConversationRow
                    key={props.id}
                    {...props}
                    onPress={() => handlePress(conv)}
                    onLongPress={() => openSheetFor(conv)}
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
        ) : data.length === 0 ? (
          <EmptyChats onSayHi={onOpenContacts} />
        ) : (
          <div className="py-1">
            {archivedRows.length > 0 ? (
              <button
                type="button"
                onClick={() => {
                  haptic(6)
                  setArchivedOpen(true)
                }}
                className="flex w-full items-center gap-3 px-4 py-2.5 pl-[26px] text-left outline-none transition-colors hover:bg-zinc-50 active:bg-zinc-100 dark:hover:bg-zinc-800/50 dark:active:bg-zinc-800"
              >
                <Archive className="size-[18px] shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
                <span className="text-sm font-medium text-zinc-700 dark:text-zinc-200">Archived</span>
                {archivedUnread > 0 ? (
                  <span className="flex h-[17px] min-w-[17px] items-center justify-center rounded-full bg-emerald-500 px-1 text-[10px] font-bold text-white">
                    {archivedUnread > 99 ? '99+' : archivedUnread}
                  </span>
                ) : null}
                <span className="ml-auto flex items-center gap-0.5 text-xs text-zinc-400 dark:text-zinc-500">
                  {archivedRows.length}
                  <ChevronRight className="size-3.5" aria-hidden />
                </span>
              </button>
            ) : null}
            {folderFiltered.map(({ conv, props }) => (
              <ConversationRow
                key={props.id}
                {...props}
                onPress={() => handlePress(conv)}
                onLongPress={() => openSheetFor(conv)}
              />
            ))}
            {folderFiltered.length === 0 && activeRows.length > 0 ? (
              <p className="px-8 pb-4 pt-10 text-center text-[13px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                {listFilter === 'unread' ? 'No unread chats — you are all caught up.' : 'No groups yet — start one from Contacts.'}
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

      {/* long-press action sheet — pin / archive / mute */}
      <Drawer open={sheetConv !== null} onOpenChange={(open) => !open && setSheetConv(null)}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          <DrawerTitle className="sr-only">Conversation options</DrawerTitle>
          <DrawerDescription className="sr-only">Pin, archive or mute this chat</DrawerDescription>
          {sheetConv ? (
            <div className="pb-2">
              <p className="px-2 pb-2 pt-1 text-center text-xs font-medium text-zinc-400 dark:text-zinc-500">
                {conversationDisplayName(sheetConv, me.id)}
              </p>
              <button
                type="button"
                role="menuitem"
                disabled={togglePin.isPending}
                onClick={() => togglePin.mutate(sheetConv)}
                className="flex w-full items-center gap-3 rounded-2xl px-3 py-3.5 text-left text-sm font-semibold text-zinc-800 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-100 dark:hover:bg-zinc-800"
              >
                {sheetConv.pinnedAt ? (
                  <>
                    <PinOff className="size-5 text-amber-500" aria-hidden />
                    Unpin from top
                  </>
                ) : (
                  <>
                    <Pin className="size-5 text-emerald-500" aria-hidden />
                    Pin to top
                  </>
                )}
              </button>
              <button
                type="button"
                role="menuitem"
                disabled={toggleArchive.isPending}
                onClick={() => toggleArchive.mutate({ conv: sheetConv, archived: sheetConv.archivedAt === null })}
                className="flex w-full items-center gap-3 rounded-2xl px-3 py-3.5 text-left text-sm font-semibold text-zinc-800 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-100 dark:hover:bg-zinc-800"
              >
                {sheetConv.archivedAt !== null ? (
                  <>
                    <ArchiveRestore className="size-5 text-amber-500" aria-hidden />
                    Unarchive chat
                  </>
                ) : (
                  <>
                    <Archive className="size-5 text-zinc-500 dark:text-zinc-400" aria-hidden />
                    Archive chat
                  </>
                )}
              </button>
              {sheetMuted ? (
                <>
                  <p className="flex items-center gap-1.5 px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-zinc-400 dark:text-zinc-500">
                    <BellOff className="size-3" aria-hidden />
                    Muted until {formatListStamp(sheetConv.mutedUntil as string)}
                  </p>
                  <button
                    type="button"
                    role="menuitem"
                    disabled={toggleMute.isPending}
                    onClick={() => toggleMute.mutate({ conv: sheetConv, until: null })}
                    className="flex w-full items-center gap-3 rounded-2xl px-3 py-3.5 text-left text-sm font-semibold text-zinc-800 outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 disabled:opacity-50 dark:text-zinc-100 dark:hover:bg-zinc-800"
                  >
                    <VolumeX className="size-5 text-emerald-500" aria-hidden />
                    Unmute notifications
                  </button>
                </>
              ) : (
                <>
                  <p className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-zinc-400 dark:text-zinc-500">
                    Mute notifications
                  </p>
                  <div className="flex gap-1.5 px-1 pb-1">
                    {([
                      { until: '8h', label: '8 hours' },
                      { until: '1w', label: '1 week' },
                      { until: 'always', label: 'Always' },
                    ] as const).map((preset) => (
                      <button
                        key={preset.until}
                        type="button"
                        role="menuitem"
                        disabled={toggleMute.isPending}
                        onClick={() => toggleMute.mutate({ conv: sheetConv, until: preset.until })}
                        className="h-10 flex-1 rounded-xl bg-zinc-100 text-[13px] font-semibold text-zinc-700 outline-none transition-colors hover:bg-emerald-500/15 hover:text-emerald-700 active:scale-95 disabled:opacity-50 dark:bg-zinc-800 dark:text-zinc-200 dark:hover:bg-emerald-500/15 dark:hover:text-emerald-400"
                      >
                        {preset.label}
                      </button>
                    ))}
                  </div>
                </>
              )}
              <button
                type="button"
                role="menuitem"
                onClick={() => setSheetConv(null)}
                className="flex w-full items-center justify-center rounded-2xl px-3 py-3 text-left text-sm font-medium text-zinc-500 outline-none transition-colors hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800"
              >
                Cancel
              </button>
            </div>
          ) : null}
        </DrawerContent>
      </Drawer>

      {/* archived chats drawer — WhatsApp-style inbox */}
      <Drawer open={archivedOpen} onOpenChange={(open) => !open && setArchivedOpen(false)}>
        <DrawerContent className="mx-auto flex max-h-[82dvh] max-w-[420px] flex-col rounded-t-3xl bg-white px-1.5 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-1 dark:bg-zinc-900">
          <DrawerDescription className="sr-only">Your archived conversations</DrawerDescription>
          <DrawerTitle className="flex items-center gap-2 px-4 pb-1.5 pt-2 text-sm font-bold tracking-tight text-zinc-800 dark:text-zinc-100">
            <Archive className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
            Archived
            <span className="ml-auto text-xs font-medium text-zinc-400 dark:text-zinc-500">
              {archivedRows.length === 1 ? '1 chat' : `${archivedRows.length} chats`}
            </span>
          </DrawerTitle>
          <p className="px-4 pb-1 text-[11px] leading-relaxed text-zinc-400 dark:text-zinc-500">
            Muted here — a new message moves a chat back to your inbox.
          </p>
          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain">
            {archivedRows.map(({ conv, props }) => (
              <ConversationRow
                key={props.id}
                {...props}
                onPress={() => {
                  setArchivedOpen(false)
                  handlePress(conv)
                }}
                onLongPress={() => openSheetFor(conv)}
              />
            ))}
          </div>
        </DrawerContent>
      </Drawer>
    </div>
  )
}

function EmptyChats({ onSayHi }: { onSayHi: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 px-8 pt-16 text-center">
      <Image
        src="/empty-chats.png"
        alt="No conversations illustration"
        width={168}
        height={168}
        className="rounded-3xl shadow-md shadow-zinc-200/70 dark:shadow-none"
      />
      <div>
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
        className="h-10 gap-1.5 rounded-full border-emerald-500/40 px-5 text-sm font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 dark:text-emerald-400 dark:hover:text-emerald-400 active:scale-[0.98]"
      >
        Say hi to someone
        <ArrowRight className="size-4" aria-hidden />
      </Button>
    </div>
  )
}

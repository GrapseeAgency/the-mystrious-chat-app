// ─────────────────────────────────────────────────────────────
// Pulse Chat — SpotlightOverlay: macOS-Spotlight-style unified
// search palette for the whole app.
// Groups (all REAL data, nothing fabricated):
//   · Chats    — the viewer's conversations (TanStack cache shared
//                with the Chats tab via ['conversations', me.id])
//   · People   — the real user directory (GET /api/users)
//   · Messages — server-side message search (GET /api/search
//                → { messages: SearchResultMessage[], total })
//   · Actions  — New chat · Check in to Hub · Toggle theme
//                (all backed by real endpoints/stores)
// Recents (last 5 queries) persist in localStorage.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Clock,
  Flame,
  LoaderCircle,
  MessageCircle,
  MessagesSquare,
  Moon,
  Search,
  SearchX,
  SquarePen,
  SunMedium,
  UserRound,
  type LucideIcon,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary, SearchResultMessage } from '@/lib/types'
import { apiJson, conversationDisplayName, conversationPreview, formatListStamp } from '@/lib/pulse-utils'
import { useTheme } from 'next-themes'
import { usePulseSession } from '@/lib/pulse-store'
import { usePrefsValues } from '@/lib/prefs'
import { cn } from '@/lib/utils'
import { spring, ease, stagger } from '@/lib/motion'
import { glassSurface } from '@/components/ui/glass-card'
import { Input } from '@/components/ui/input'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'

// ── Recents (localStorage) ───────────────────────────────────

const RECENTS_KEY = 'pulse.spotlight.recents.v1'
const RECENTS_MAX = 5

function readRecents(): string[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(RECENTS_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x): x is string => typeof x === 'string' && x.trim().length > 0).slice(0, RECENTS_MAX)
  } catch {
    return []
  }
}

function pushRecent(query: string): void {
  const q = query.trim()
  if (!q) return
  const next = [q, ...readRecents().filter((r) => r.toLowerCase() !== q.toLowerCase())].slice(0, RECENTS_MAX)
  try {
    window.localStorage.setItem(RECENTS_KEY, JSON.stringify(next))
  } catch {
    // storage unavailable — recents are best-effort
  }
}

function clearRecents(): void {
  try {
    window.localStorage.removeItem(RECENTS_KEY)
  } catch {
    // ignore
  }
}

// ── Row model ────────────────────────────────────────────────

interface ChatRow {
  kind: 'chat'
  key: string
  conv: ConversationSummary
  title: string
  subtitle: string
  unread: number
  isGroup: boolean
  /** avatar color of the DM partner (groups use GroupAvatar) */
  dmColor: string
}
interface PersonRow {
  kind: 'person'
  key: string
  user: AppUser
}
interface MessageRow {
  kind: 'message'
  key: string
  hit: SearchResultMessage
}
interface ActionRow {
  kind: 'action'
  key: string
  label: string
  hint: string
  Icon: LucideIcon
  /** true when the overlay should stay open after running (theme flips look best in place) */
  keepOpen: boolean
  run: () => void
}
interface RecentRow {
  kind: 'recent'
  key: string
  text: string
}

type SpotlightRow = ChatRow | PersonRow | MessageRow | ActionRow | RecentRow

interface Section {
  label: string
  rows: SpotlightRow[]
}

// ── Highlight ────────────────────────────────────────────────

function Highlight({ text, query }: { text: string; query: string }) {
  const q = query.trim()
  if (!q) return <>{text}</>
  const idx = text.toLowerCase().indexOf(q.toLowerCase())
  if (idx < 0) return <>{text}</>
  return (
    <>
      {text.slice(0, idx)}
      <span className="font-semibold text-emerald-600 dark:text-emerald-400">
        {text.slice(idx, idx + q.length)}
      </span>
      {text.slice(idx + q.length)}
    </>
  )
}

// ── Overlay ──────────────────────────────────────────────────

export function SpotlightOverlay({
  open,
  onClose,
  onOpenConversation,
  onOpenDm,
  onRequestNewChat,
  onOpenHub,
}: {
  open: boolean
  onClose: () => void
  /** open a conversation (anchorMs / jumpMessageId follow the ChatsTab contract) */
  onOpenConversation?: (conversationId: string, anchorMs?: number | null, jumpMessageId?: string) => void
  /** open (or create) a 1:1 DM with a user id */
  onOpenDm?: (userId: string) => void
  /** open the shell's New Chat sheet (DM mode) */
  onRequestNewChat?: () => void
  /** switch the shell to the Hub tab */
  onOpenHub?: () => void
}) {
  const sessionUser = usePulseSession((s) => s.user)
  const me = sessionUser
  const prefs = usePrefsValues()
  const { resolvedTheme, setTheme } = useTheme()
  const queryClient = useQueryClient()

  const [query, setQuery] = useState('')
  const [debouncedQ, setDebouncedQ] = useState('')
  const [recents, setRecents] = useState<string[]>(() => readRecents())
  /** highlighted row key — stale keys fall back to the first row (derived) */
  const [selKey, setSelKey] = useState<string | null>(null)
  const [checkinPending, setCheckinPending] = useState(false)
  const listRef = useRef<HTMLDivElement | null>(null)

  const reducedMotion = prefs.reducedMotion

  // 250ms debounce feeding the server message search
  useEffect(() => {
    const t = setTimeout(() => setDebouncedQ(query.trim()), 250)
    return () => clearTimeout(t)
  }, [query])

  // Escape always closes, even when the input lost focus
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  // ── real data ──────────────────────────────────────────────
  const conversations = useQuery({
    queryKey: ['conversations', me?.id ?? '-'],
    enabled: open && !!me,
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<{ conversations: ConversationSummary[] }>(
        `/api/conversations?userId=${encodeURIComponent(me?.id ?? '')}`,
      )
      return res.conversations
    },
  })

  const directory = useQuery({
    queryKey: ['users'],
    enabled: open,
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<{ users: AppUser[] }>('/api/users')
      return res.users
    },
    staleTime: 30_000,
  })

  const messageSearch = useQuery({
    queryKey: ['global-search', me?.id ?? '-', debouncedQ],
    enabled: open && !!me && debouncedQ.length >= 2,
    staleTime: 15_000,
    queryFn: async (): Promise<{ messages: SearchResultMessage[]; total: number }> =>
      apiJson<{ messages: SearchResultMessage[]; total: number }>(
        `/api/search?userId=${encodeURIComponent(me?.id ?? '')}&q=${encodeURIComponent(debouncedQ)}`,
      ),
  })

  const q = query.trim()
  const ql = q.toLowerCase()

  // ── actions (real endpoints/stores only) ───────────────────
  const runCheckin = async () => {
    if (!me || checkinPending) return
    setCheckinPending(true)
    try {
      const res = await apiJson<{ wallet: { checkedInToday: boolean; streak: number }; reward: number; streak: number }>(
        '/api/hub/wallet/checkin',
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me.id }),
        },
      )
      void queryClient.invalidateQueries({ queryKey: ['hub-wallet', me.id] })
      toast.success(`Checked in — +${res.reward} PC · ${res.streak}-day streak`)
      onClose()
      onOpenHub?.()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Check-in failed')
    } finally {
      setCheckinPending(false)
    }
  }

  const actions: SpotlightRow[] = [
    {
      kind: 'action',
      key: 'act-new-chat',
      label: 'New chat',
      hint: 'Pick someone to message',
      Icon: SquarePen,
      keepOpen: false,
      run: () => onRequestNewChat?.(),
    },
    {
      kind: 'action',
      key: 'act-checkin',
      label: checkinPending ? 'Checking in…' : 'Check in to Hub',
      hint: 'Daily Pulse Coins reward',
      Icon: Flame,
      keepOpen: false,
      run: () => void runCheckin(),
    },
    {
      kind: 'action',
      key: 'act-theme',
      label: resolvedTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme',
      hint: 'Appearance',
      Icon: resolvedTheme === 'dark' ? SunMedium : Moon,
      keepOpen: true,
      run: () => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark'),
    },
  ].filter(
    (a): a is ActionRow =>
      !ql || a.label.toLowerCase().includes(ql) || a.hint.toLowerCase().includes(ql),
  )

  // ── grouped + flattened rows ───────────────────────────────
  const sections: Section[] = useMemo(() => {
    if (!q) {
      const out: Section[] = []
      if (recents.length > 0) {
        out.push({
          label: 'Recents',
          rows: recents.map((text, i) => ({ kind: 'recent', key: `recent-${i}`, text })),
        })
      }
      out.push({ label: 'Actions', rows: actions })
      return out
    }

    const myId = me?.id ?? ''
    const convRows: ChatRow[] = (conversations.data ?? [])
      .map((conv) => {
        const title = conversationDisplayName(conv, myId)
        const preview = conversationPreview(conv, myId)
        const other = conv.members.find((m) => m.id !== myId)
        return { conv, title, preview, dmColor: other?.color ?? 'emerald' }
      })
      .filter(({ title, preview }) => title.toLowerCase().includes(ql) || preview.text.toLowerCase().includes(ql))
      .slice(0, 6)
      .map(({ conv, title, preview, dmColor }) => ({
        kind: 'chat' as const,
        key: `chat-${conv.id}`,
        conv,
        title,
        subtitle: preview.text,
        unread: conv.unreadCount,
        isGroup: conv.isGroup,
        dmColor,
      }))

    const seen = new Set<string>()
    const personRows: PersonRow[] = (directory.data ?? [])
      .filter((u) => u.id !== me?.id)
      .filter((u) => {
        if (seen.has(u.id)) return false
        seen.add(u.id)
        return (
          u.name.toLowerCase().includes(ql) ||
          (u.username !== null && u.username.toLowerCase().includes(ql))
        )
      })
      .slice(0, 6)
      .map((user) => ({ kind: 'person' as const, key: `person-${user.id}`, user }))

    const msgRows: MessageRow[] =
      debouncedQ.length >= 2
        ? (messageSearch.data?.messages ?? [])
            .slice(0, 8)
            .map((hit) => ({ kind: 'message' as const, key: `msg-${hit.id}`, hit }))
        : []

    const out: Section[] = []
    if (convRows.length > 0) out.push({ label: 'Chats', rows: convRows })
    if (personRows.length > 0) out.push({ label: 'People', rows: personRows })
    if (msgRows.length > 0) out.push({ label: 'Messages', rows: msgRows })
    if (actions.length > 0) out.push({ label: 'Actions', rows: actions })
    return out
  }, [q, ql, recents, actions, conversations.data, directory.data, messageSearch.data, debouncedQ, me?.id])

  const flatRows = useMemo(() => sections.flatMap((s) => s.rows), [sections])

  // active row: derive from selKey so list changes never need render-time writes
  const activeIdx = Math.max(
    0,
    flatRows.findIndex((r) => r.key === selKey),
  )
  const activeRow: SpotlightRow | undefined = flatRows[activeIdx]

  // keep the active row in view
  useEffect(() => {
    const el = listRef.current?.querySelector(`[data-spot-idx="${activeIdx}"]`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [activeIdx, flatRows.length])

  const activate = (row: SpotlightRow) => {
    switch (row.kind) {
      case 'recent': {
        setQuery(row.text)
        return
      }
      case 'chat': {
        if (q) pushRecent(q)
        // freeze the "where was I" anchor at tap time (ChatsTab contract)
        const mine = row.conv.members.find(
          (m) => m.id === me?.id,
        ) as (AppUser & { lastReadAt?: string }) | undefined
        const wm = Date.parse(mine?.lastReadAt ?? '')
        const anchor = row.conv.unreadCount > 0 && !Number.isNaN(wm) ? wm : null
        onOpenConversation?.(row.conv.id, anchor)
        onClose()
        return
      }
      case 'person': {
        if (q) pushRecent(q)
        onOpenDm?.(row.user.id)
        onClose()
        return
      }
      case 'message': {
        if (q) pushRecent(q)
        onOpenConversation?.(row.hit.conversationId, null, row.hit.id)
        onClose()
        return
      }
      case 'action': {
        row.run()
        if (!row.keepOpen) onClose()
        return
      }
    }
  }

  const onInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (flatRows.length > 0) setSelKey(flatRows[(activeIdx + 1) % flatRows.length].key)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (flatRows.length > 0) setSelKey(flatRows[(activeIdx - 1 + flatRows.length) % flatRows.length].key)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (activeRow) activate(activeRow)
    }
  }

  if (!open || !me) return null

  const searching = debouncedQ.length >= 2 && messageSearch.isFetching
  const nothingFound =
    q.length > 0 && flatRows.length === 0 && !messageSearch.isFetching

  const panelTransition = reducedMotion ? { duration: 0 } : spring.soft

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: reducedMotion ? 0 : 0.16 }}
      className="absolute inset-0 z-[90]"
      role="dialog"
      aria-modal="true"
      aria-label="Spotlight search"
    >
      {/* dim + blur backdrop */}
      <div
        className="absolute inset-0 bg-zinc-950/45 backdrop-blur-[10px]"
        onClick={onClose}
        aria-hidden
      />

      {/* card — liquid-glass surface (R22 recipe) with a spring entrance */}
      <motion.div
        initial={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: 6, transition: { duration: 0.12 } }}
        transition={panelTransition}
        className={cn(
          glassSurface,
          'absolute inset-x-0 top-[7%] mx-auto flex w-[92%] max-w-md flex-col overflow-hidden',
        )}
      >
        {/* input row */}
        <div className="flex items-center gap-2.5 border-b border-zinc-200/70 px-4 dark:border-zinc-700/60">
          <Search className="size-[18px] shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
          <Input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onInputKeyDown}
            placeholder="Search chats, people, messages…"
            aria-label="Search Pulse"
            className="h-12 border-0 bg-transparent px-0 text-[15px] font-medium shadow-none focus-visible:ring-0 dark:bg-transparent"
          />
          {searching ? (
            <LoaderCircle className="size-4 shrink-0 animate-spin text-emerald-500" aria-label="Searching" />
          ) : (
            <kbd className="hidden shrink-0 rounded-md border border-zinc-200 bg-zinc-100 px-1.5 py-0.5 text-[10px] font-semibold text-zinc-400 sm:block dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-500">
              esc
            </kbd>
          )}
        </div>

        {/* results */}
        <div
          ref={listRef}
          className="pulse-scroll max-h-[58vh] min-h-[120px] overflow-y-auto overscroll-contain px-2 pb-2"
          role="listbox"
          aria-label="Search results"
        >
          {nothingFound ? (
            <div className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
              <SearchX className="size-7 text-zinc-300 dark:text-zinc-600" aria-hidden />
              <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">No results for “{q}”</p>
              <p className="text-xs text-zinc-400 dark:text-zinc-500">Try a different name or phrase.</p>
            </div>
          ) : q.length === 1 ? (
            <p className="px-4 pb-3 pt-4 text-center text-xs text-zinc-400 dark:text-zinc-500">
              Keep typing to search inside messages…
            </p>
          ) : (
            sections.map((section) => (
              <div key={section.label} className="pt-2.5">
                <p className="px-3 pb-1 text-[10.5px] font-bold uppercase tracking-[0.12em] text-zinc-400 dark:text-zinc-500">
                  {section.label}
                </p>
                {section.rows.map((row) => {
                  const idx = flatRows.indexOf(row)
                  return (
                    <SpotlightItemRow
                      key={row.key}
                      row={row}
                      query={q}
                      active={idx === activeIdx}
                      index={idx}
                      reducedMotion={reducedMotion}
                      onSelect={() => activate(row)}
                      onHover={() => setSelKey(row.key)}
                    />
                  )
                })}
              </div>
            ))
          )}

          {debouncedQ.length >= 2 && messageSearch.isPending && !messageSearch.data ? (
            <div className="flex items-center justify-center gap-2 py-5" role="status" aria-label="Searching messages">
              <LoaderCircle className="size-4 animate-spin text-emerald-500" aria-hidden />
              <span className="text-xs font-medium text-zinc-400 dark:text-zinc-500">Searching messages…</span>
            </div>
          ) : null}
        </div>

        {/* footer */}
        <div className="flex items-center justify-between border-t border-zinc-200/70 px-4 py-2 dark:border-zinc-700/60">
          <div className="flex items-center gap-2.5 text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
            <span className="flex items-center gap-1">
              <kbd className="rounded border border-zinc-200 bg-zinc-100 px-1 dark:border-zinc-700 dark:bg-zinc-800">↑↓</kbd>
              navigate
            </span>
            <span className="flex items-center gap-1">
              <kbd className="rounded border border-zinc-200 bg-zinc-100 px-1 dark:border-zinc-700 dark:bg-zinc-800">↵</kbd>
              open
            </span>
          </div>
          {recents.length > 0 ? (
            <button
              type="button"
              onClick={() => {
                clearRecents()
                setRecents([])
              }}
              className="rounded-lg px-2 py-1 text-[10.5px] font-semibold text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
            >
              Clear recents
            </button>
          ) : null}
        </div>
      </motion.div>
    </motion.div>
  )
}

// ── One result row (discriminated render, no `any`) ──────────

function SpotlightItemRow({
  row,
  query,
  active,
  index,
  reducedMotion,
  onSelect,
  onHover,
}: {
  row: SpotlightRow
  query: string
  active: boolean
  index: number
  reducedMotion: boolean
  onSelect: () => void
  onHover: () => void
}) {
  const base = cn(
    'flex min-h-[46px] w-full items-center gap-3 rounded-2xl px-3 py-2 text-left outline-none transition-colors',
    active ? 'bg-emerald-500/[0.12] dark:bg-emerald-400/[0.12]' : 'hover:bg-zinc-100/70 dark:hover:bg-zinc-800/50',
  )

  let content: React.ReactNode = null
  if (row.kind === 'chat') {
    content = (
      <>
        {row.isGroup ? (
          <GroupAvatar title={row.title} id={row.conv.id} size={36} />
        ) : (
          <UserAvatar name={row.title} color={row.dmColor} size={36} />
        )}
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-1.5">
            <span className="truncate text-[14px] font-medium text-zinc-900 dark:text-zinc-100">
              <Highlight text={row.title} query={query} />
            </span>
            {row.unread > 0 ? (
              <span className="flex h-4 min-w-4 shrink-0 items-center justify-center rounded-full bg-emerald-500 px-1 text-[9px] font-bold text-white">
                {row.unread > 99 ? '99+' : row.unread}
              </span>
            ) : null}
          </span>
          <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">
            <Highlight text={row.subtitle} query={query} />
          </span>
        </span>
        <MessageCircle className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
      </>
    )
  } else if (row.kind === 'person') {
    content = (
      <>
        <UserAvatar name={row.user.name} color={row.user.color} size={36} />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[14px] font-medium text-zinc-900 dark:text-zinc-100">
            <Highlight text={row.user.name} query={query} />
          </span>
          <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">
            {row.user.username !== null ? (
              <>
                @<Highlight text={row.user.username} query={query} />
              </>
            ) : (
              'Pulse user'
            )}
          </span>
        </span>
        <UserRound className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
      </>
    )
  } else if (row.kind === 'message') {
    content = (
      <>
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-100 dark:bg-zinc-800">
          <MessagesSquare className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline gap-2">
            <span className="truncate text-[14px] font-medium text-zinc-900 dark:text-zinc-100">
              <Highlight text={row.hit.conversationName} query={query} />
            </span>
            <span className="shrink-0 text-[10.5px] text-zinc-400 dark:text-zinc-500">
              {formatListStamp(row.hit.createdAt)}
            </span>
          </span>
          <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">
            <span className="text-zinc-400 dark:text-zinc-500">{row.hit.sender.name}: </span>
            <Highlight text={row.hit.content} query={query} />
          </span>
        </span>
      </>
    )
  } else if (row.kind === 'action') {
    const Icon = row.Icon
    content = (
      <>
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 dark:bg-emerald-400/10">
          <Icon className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[14px] font-medium text-zinc-900 dark:text-zinc-100">{row.label}</span>
          <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">{row.hint}</span>
        </span>
      </>
    )
  } else {
    content = (
      <>
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-100 dark:bg-zinc-800">
          <Clock className="size-[18px] text-zinc-400 dark:text-zinc-500" aria-hidden />
        </span>
        <span className="min-w-0 flex-1 truncate text-[14px] font-medium text-zinc-800 dark:text-zinc-200">
          {row.text}
        </span>
      </>
    )
  }

  return (
    <motion.button
      type="button"
      role="option"
      aria-selected={active}
      data-spot-idx={index}
      onClick={onSelect}
      onMouseEnter={onHover}
      className={base}
      // shared-token entrance: 25ms stagger, swift-out — rows flow in
      initial={reducedMotion ? false : { opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={
        reducedMotion
          ? { duration: 0 }
          : { duration: 0.32, ease: ease.out, delay: stagger(index, 0.025) }
      }
      whileTap={reducedMotion ? undefined : { scale: 0.97 }}
      style={{ willChange: 'transform, opacity' }}
    >
      {content}
    </motion.button>
  )
}

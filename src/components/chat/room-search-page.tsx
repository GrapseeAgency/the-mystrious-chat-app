// ─────────────────────────────────────────────────────────────
// Pulse — in-room message search sub-page (#/room/<id>/search) — R27-c.
// Full-screen glass sheet above the chat room: a glass search field
// with LIVE client filtering over the room's real loaded messages,
// plus a debounced room-scoped API search (?q= scans the whole
// conversation) so hits beyond the loaded window are still real.
// Results are grouped by sender; tapping one closes this page and
// reuses the room's jump-to-message machinery (scroll + glass flash).
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, Mic, Search, SearchX, X, Image as ImageIcon } from 'lucide-react'
import type { AppUser, ChatMessage } from '@/lib/types'
import { apiJson, formatListStamp, formatTime } from '@/lib/pulse-utils'
import { ease, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { UserAvatar } from '@/components/chat/user-avatar'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

export interface RoomSearchPageProps {
  me: AppUser
  conversationId: string
  /** the room's real loaded message window (client filter source) */
  loadedMessages: ChatMessage[]
  reducedMotion?: boolean
  /** backHash() — returns to the room */
  onClose: () => void
  /** jump + flash in the room (after onClose) */
  onJump: (messageId: string) => void
}

/** Escape a string for safe RegExp use (local copy — keeps parity with the room). */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** Renders text with the first case-insensitive occurrence of `query` highlighted. */
function MatchedText({ content, query }: { content: string; query: string }) {
  if (query.length === 0) return <>{content}</>
  const idx = content.toLowerCase().indexOf(query.toLowerCase())
  if (idx === -1) return <>{content}</>
  const re = new RegExp(`(${escapeRegExp(query)})`, 'i')
  const parts = content.split(re)
  return (
    <>
      {parts.map((part, i) =>
        part.toLowerCase() === query.toLowerCase() ? (
          <mark
            key={i}
            className="rounded bg-emerald-400/30 px-0.5 text-emerald-800 outline outline-1 outline-emerald-500/40 dark:text-emerald-200"
          >
            {part}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </>
  )
}

interface SenderGroup {
  senderId: string
  sender: ChatMessage['sender']
  items: ChatMessage[]
}

export function RoomSearchPage({
  me,
  conversationId,
  loadedMessages,
  reducedMotion = false,
  onClose,
  onJump,
}: RoomSearchPageProps) {
  const [draft, setDraft] = useState('')
  const [query, setQuery] = useState('')
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // debounced commit (same cadence as the room's drafts)
  useEffect(() => {
    if (timerRef.current !== null) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      setQuery(draft.trim())
    }, 220)
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [draft])

  // unmount safety
  useEffect(
    () => () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current)
    },
    [],
  )

  /** LIVE client filter over the real loaded window. */
  const clientHits = useMemo(() => {
    const q = query.toLowerCase()
    if (q.length === 0) return []
    return loadedMessages.filter(
      (m) => m.deletedAt === null && m.content.toLowerCase().includes(q),
    )
  }, [loadedMessages, query])

  /**
   * Room-scoped whole-history search — the conversation messages API
   * accepts ?q= and scans every non-deleted row of THIS conversation
   * (real data, not the global cross-chat endpoint).
   */
  const historyHits = useQuery({
    queryKey: ['room-search', conversationId, query],
    enabled: query.length > 0,
    staleTime: 20_000,
    queryFn: async (): Promise<{ items: ChatMessage[]; total: number }> => {
      const res = await apiJson<{ messages: ChatMessage[]; total?: number }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=100&q=${encodeURIComponent(query)}`,
      )
      return { items: res.messages, total: res.total ?? res.messages.length }
    },
  })

  /** merged + deduped, oldest first */
  const results = useMemo(() => {
    const byId = new Map<string, ChatMessage>()
    for (const m of historyHits.data?.items ?? []) byId.set(m.id, m)
    for (const m of clientHits) byId.set(m.id, m) // loaded cache wins (freshest)
    return [...byId.values()].sort(
      (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
    )
  }, [clientHits, historyHits.data])

  /** consecutive-by-sender grouping */
  const groups = useMemo<SenderGroup[]>(() => {
    const out: SenderGroup[] = []
    for (const m of results) {
      const last = out[out.length - 1]
      if (last && last.senderId === m.senderId) {
        last.items.push(m)
      } else {
        out.push({ senderId: m.senderId, sender: m.sender, items: [m] })
      }
    }
    return out
  }, [results])

  const searching = query.length > 0 && historyHits.isPending && clientHits.length === 0

  const jump = (messageId: string) => {
    haptic(10)
    onClose()
    onJump(messageId)
  }

  return (
    <motion.div
      initial={reducedMotion ? false : { y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={spring.soft}
      role="dialog"
      aria-label={`Search messages in this chat`}
      className="absolute inset-0 z-[55] flex flex-col bg-zinc-100/85 backdrop-blur-2xl dark:bg-black/70"
    >
      {/* header — back + glass search field */}
      <div className="flex min-h-14 shrink-0 items-center gap-1.5 px-2 pt-[env(safe-area-inset-top)]">
        <button
          type="button"
          aria-label="Back to conversation"
          onClick={() => {
            haptic(6)
            onClose()
          }}
          className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-transform active:scale-90 dark:text-zinc-300"
        >
          <ChevronLeft className="size-6" aria-hidden />
        </button>
        <div className="glass-pill relative min-w-0 flex-1">
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400"
            aria-hidden
          />
          <input
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Search this chat"
            aria-label="Search messages"
            autoComplete="off"
            className="h-10 w-full rounded-full bg-transparent pr-9 pl-9 text-sm text-zinc-900 placeholder:text-zinc-400 outline-none dark:text-zinc-100"
          />
          {draft.length > 0 ? (
            <button
              type="button"
              aria-label="Clear search"
              onClick={() => setDraft('')}
              className="absolute top-1/2 right-2 -translate-y-1/2 rounded-full p-1 text-zinc-400 outline-none transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
            >
              <X className="size-4" aria-hidden />
            </button>
          ) : null}
        </div>
      </div>

      {/* results */}
      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 pt-3 pb-6">
        {query.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
            <div
              aria-hidden
              className="glass-sheen flex size-16 items-center justify-center rounded-3xl bg-gradient-to-br from-emerald-400/15 to-emerald-600/10 text-emerald-500 dark:from-emerald-400/10 dark:to-emerald-600/5"
            >
              <Search className="size-7" aria-hidden />
            </div>
            <div>
              <p className="glass-pill inline-block px-3 py-1 text-sm font-semibold text-zinc-600 dark:text-zinc-300">
                Search this conversation
              </p>
              <p className="mt-2 max-w-[230px] text-xs leading-relaxed text-zinc-400 dark:text-zinc-500">
                Find any message by its text — results group by sender and jump straight back to the thread.
              </p>
            </div>
          </div>
        ) : searching ? (
          <div className="space-y-2 pt-2" role="status" aria-label="Searching messages">
            <Skeleton className="mx-auto h-6 w-32 rounded-full" />
            <Skeleton className="h-14 w-full rounded-2xl" />
            <Skeleton className="ml-auto h-14 w-4/5 rounded-2xl" />
          </div>
        ) : results.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
            <div
              aria-hidden
              className="flex size-14 items-center justify-center rounded-2xl bg-zinc-200/60 text-zinc-400 dark:bg-zinc-800"
            >
              <SearchX className="size-6" aria-hidden />
            </div>
            <p className="text-sm font-semibold text-zinc-500 dark:text-zinc-400">
              No matches for “{query}”
            </p>
            <p className="text-xs text-zinc-400 dark:text-zinc-500">
              Try a shorter or different phrase.
            </p>
          </div>
        ) : (
          <>
            <div className="mb-2.5 flex justify-center">
              <span className="glass-pill px-3 py-1 text-[11px] font-medium text-zinc-600 dark:text-zinc-300">
                {results.length === 1 ? '1 message found' : `${results.length} messages found`}
                {historyHits.data && historyHits.data.total > results.length
                  ? ` · showing newest ${results.length}`
                  : ''}
              </span>
            </div>
            {groups.map((group, gi) => (
              <section
                key={group.senderId + gi}
                aria-label={`Messages from ${group.sender.id === me.id ? 'you' : group.sender.name}`}
                className="mb-3"
              >
                <div className="mb-1 flex items-center gap-2 px-1">
                  <UserAvatar name={group.sender.name} color={group.sender.color} size={22} />
                  <span className="truncate text-xs font-bold text-emerald-700 dark:text-emerald-400">
                    {group.sender.id === me.id ? 'You' : group.sender.name}
                  </span>
                  <span className="text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
                    {group.items.length === 1 ? '1 hit' : `${group.items.length} hits`}
                  </span>
                  <span className="h-px flex-1 bg-zinc-900/[0.06] dark:bg-white/[0.07]" />
                </div>
                <div className="glass-deep glass-sheen overflow-hidden rounded-2xl p-1">
                  {group.items.map((m, mi) => (
                    <motion.button
                      key={m.id}
                      type="button"
                      initial={reducedMotion ? false : { opacity: 0, y: 5 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ duration: 0.18, delay: Math.min(mi * 0.02 + gi * 0.03, 0.2), ease: ease.out }}
                      onClick={() => jump(m.id)}
                      className="glass-row-hover flex w-full items-start gap-2.5 rounded-xl p-2.5 text-left outline-none"
                    >
                      <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-lg bg-zinc-900/[0.04] text-zinc-400 dark:bg-white/[0.06]">
                        {m.imagePath ? (
                          <ImageIcon className="size-3.5" aria-hidden />
                        ) : m.audioPath ? (
                          <Mic className="size-3.5" aria-hidden />
                        ) : (
                          <Search className="size-3.5" aria-hidden />
                        )}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="flex items-baseline justify-between gap-2">
                          <span className="truncate text-[11px] font-semibold text-zinc-400 dark:text-zinc-500">
                            {formatListStamp(m.createdAt)}
                          </span>
                          <span className="shrink-0 text-[10px] tabular-nums text-zinc-400 dark:text-zinc-500">
                            {formatTime(m.createdAt)}
                          </span>
                        </span>
                        <span className="mt-0.5 line-clamp-2 block text-[13px] leading-snug break-words text-zinc-700 dark:text-zinc-200">
                          <MatchedText
                            content={
                              m.content.replace(/\s+/g, ' ').trim() ||
                              (m.imagePath ? 'Photo' : 'Voice message')
                            }
                            query={query}
                          />
                        </span>
                      </span>
                    </motion.button>
                  ))}
                </div>
              </section>
            ))}
          </>
        )}
      </div>
    </motion.div>
  )
}

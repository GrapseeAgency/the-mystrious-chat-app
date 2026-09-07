// ─────────────────────────────────────────────────────────────
// Pulse — #/mentions sub-page (R35-b).
// Discord mobile "Mentions" tab paradigm INSIDE the chats tab,
// mounted exactly like #/calls: glass page, hash-routed
// (navigate('/mentions') opens, backHash() closes), staggered row
// entrances. Data is the REAL GET /api/mentions feed (R35-b) — no
// mocks:
//   • author avatar stays CIRCULAR (people, not things — shapes system)
//   • author name semibold, snippet with the @Me token highlighted in
//     an emerald glass chip (mirrors the API's matching regex — keep
//     the two in sync)
//   • conversation name (or "Direct message") + relative time, chevron
//   • tap a row → opens that conversation via the same navigation call
//     the chats list and calls page use
// Empty state is honest: no mentions yet — when someone @mentions you,
// it shows up here.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, AtSign, ChevronRight, LoaderCircle } from 'lucide-react'
import type { AppUser } from '@/lib/types'
import { apiJson, formatListStamp } from '@/lib/pulse-utils'
import { spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Skeleton } from '@/components/ui/skeleton'
import { UserAvatar } from '@/components/chat/user-avatar'

// ── wire contract (must match /api/mentions) ─────────────────

export interface MentionItem {
  messageId: string
  conversationId: string
  conversationName: string | null
  isGroup: boolean
  author: { id: string; name: string; color: string; avatar: string | null }
  snippet: string
  createdAt: string
}

export interface MentionsResponse {
  items: MentionItem[]
}

export async function fetchMentions(meId: string): Promise<MentionsResponse> {
  return apiJson<MentionsResponse>(`/api/mentions?userId=${encodeURIComponent(meId)}&limit=50`)
}

/**
 * Client-side copy of the API's mention rule (see /api/mentions header):
 * '@' + full display name, case-insensitive, followed by whitespace,
 * end-of-string or a non-alphanumeric character.
 */
function mentionPatternFor(name: string): RegExp {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`@${escaped}(?=\\s|$|[^A-Za-z0-9])`, 'i')
}

export interface MentionsPageProps {
  /** reactive: hash path === '/mentions' */
  open: boolean
  me: AppUser
  onBack: () => void
  /** open the conversation a mention belongs to — same call the chats list uses */
  onOpenConversation: (conversationId: string) => void
}

/** Snippet with the first @Me token wrapped in an emerald glass chip. */
function MentionSnippet({ snippet, meName }: { snippet: string; meName: string }) {
  const parts = useMemo(() => {
    const match = mentionPatternFor(meName).exec(snippet)
    if (!match) return null
    return {
      before: snippet.slice(0, match.index),
      token: match[0],
      after: snippet.slice(match.index + match[0].length),
    }
  }, [snippet, meName])
  if (!parts) return <>{snippet}</>
  return (
    <>
      {parts.before}
      <mark
        className="mx-0.5 rounded-md bg-emerald-500/15 px-1 py-px font-semibold text-emerald-700 ring-1 ring-emerald-500/30 dark:text-emerald-300 dark:ring-emerald-400/25"
      >
        {parts.token}
      </mark>
      {parts.after}
    </>
  )
}

/** One mention row — 56px target, circular author avatar, emerald token chip. */
function MentionRow({
  item,
  index,
  reducedMotion,
  meName,
  onPress,
}: {
  item: MentionItem
  index: number
  reducedMotion: boolean | null
  meName: string
  onPress: () => void
}) {
  const authorName = item.author?.name?.trim() ? item.author.name : 'Unknown'
  const authorColor = item.author?.color ?? 'emerald'
  const authorAvatar = item.author?.avatar ?? null
  const where = item.isGroup
    ? item.conversationName?.trim() || 'Group'
    : 'Direct message'
  return (
    <motion.button
      type="button"
      initial={reducedMotion ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...spring.soft, delay: stagger(Math.min(index, 9)) }}
      whileTap={reducedMotion ? undefined : { scale: 0.985 }}
      onClick={onPress}
      className="glass-row-hover flex w-full items-start gap-3 rounded-2xl px-2.5 py-2.5 text-left outline-none"
      role="row"
      aria-label={`Mention from ${authorName} in ${where}, ${formatListStamp(item.createdAt)}`}
    >
      {/* people are CIRCLES — the shapes system only squircles things */}
      <span className="pt-0.5">
        <UserAvatar name={authorName} color={authorColor} avatar={authorAvatar} size={44} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14.5px] font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          {authorName}
        </span>
        <span className="mt-0.5 line-clamp-2 break-words text-[13px] leading-snug text-zinc-500 dark:text-zinc-400">
          <MentionSnippet snippet={item.snippet} meName={meName} />
        </span>
        <span className="mt-1 flex items-center gap-1 text-[11px] text-zinc-400 dark:text-zinc-500">
          <AtSign className="size-3 shrink-0 text-emerald-500/70 dark:text-emerald-400/70" aria-hidden />
          <span className="truncate">{where}</span>
        </span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1 pt-0.5">
        <span className="text-[11px] text-zinc-400 dark:text-zinc-500">
          {formatListStamp(item.createdAt)}
        </span>
        <ChevronRight className="size-4 text-zinc-300 dark:text-zinc-600" aria-hidden />
      </span>
    </motion.button>
  )
}

export function MentionsPage({ open, me, onBack, onOpenConversation }: MentionsPageProps) {
  const reducedMotion = useReducedMotion()

  const mentions = useQuery({
    queryKey: ['mentions', me.id],
    queryFn: () => fetchMentions(me.id),
    enabled: open,
    refetchInterval: 30_000,
  })

  const items = mentions.data?.items ?? []

  return (
    <AnimatePresence>
      {open ? (
        <motion.div
          key="mentions-page"
          initial={reducedMotion ? false : { opacity: 0, x: '7%' }}
          animate={{ opacity: 1, x: 0 }}
          exit={reducedMotion ? { opacity: 0 } : { opacity: 0, x: '7%' }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="absolute inset-0 z-30 flex flex-col bg-white dark:bg-zinc-900"
          role="region"
          aria-label="Mentions"
        >
          {/* frosted sub-page header — same glass recipe as #/calls */}
          <header className="glass-deep glass-sheen shrink-0 border-b border-zinc-200/70 pt-[max(0px,env(safe-area-inset-top))] dark:border-white/10">
            <div className="flex items-center gap-2 px-3 py-2.5">
              <motion.button
                type="button"
                aria-label="Back to chats"
                onClick={() => {
                  haptic(8)
                  onBack()
                }}
                whileTap={reducedMotion ? undefined : { scale: 0.9 }}
                transition={spring.snappy}
                className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-colors hover:text-zinc-900 dark:text-zinc-300 dark:hover:text-white"
              >
                <ArrowLeft className="size-5" aria-hidden />
              </motion.button>
              <div className="min-w-0 flex-1">
                <h1 className="flex items-center gap-2 truncate text-[17px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                  Mentions
                  {items.length > 0 ? (
                    <span className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-emerald-500/15 px-1.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                      {items.length > 99 ? '99+' : items.length}
                    </span>
                  ) : null}
                </h1>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  Messages that mention you — tap a row to jump in
                </p>
              </div>
              {mentions.isFetching ? (
                <LoaderCircle className="size-4 shrink-0 animate-spin text-emerald-500" aria-hidden />
              ) : null}
            </div>
          </header>

          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-6">
            {mentions.isPending ? (
              <div className="space-y-2 px-2 pt-4" role="status" aria-label="Loading mentions">
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-11/12 rounded-2xl" />
              </div>
            ) : mentions.isError ? (
              <motion.div
                initial={reducedMotion ? false : { opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3 }}
                className={cn(
                  'flex flex-col items-center justify-center gap-3 px-8 pt-24 text-center',
                )}
              >
                <span aria-hidden className="glass-deep flex size-16 items-center justify-center rounded-3xl">
                  <AtSign className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  Could not load mentions
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  {mentions.error instanceof Error ? mentions.error.message : 'Something went wrong.'}
                </p>
                <button
                  type="button"
                  onClick={() => void mentions.refetch()}
                  className="glass-pill h-9 px-4 text-xs font-bold text-emerald-600 outline-none dark:text-emerald-400"
                >
                  Try again
                </button>
              </motion.div>
            ) : items.length === 0 ? (
              <motion.div
                initial={reducedMotion ? false : { opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3 }}
                className="flex flex-col items-center justify-center gap-3 px-8 pt-24 text-center"
              >
                <span aria-hidden className="glass-deep flex size-16 items-center justify-center rounded-3xl">
                  <AtSign className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  No mentions yet
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  No mentions yet — when someone @mentions you, it shows up here.
                </p>
              </motion.div>
            ) : (
              <div className="px-2 pt-3">
                <div className="glass-deep glass-sheen rounded-3xl p-1.5">
                  {items.map((item, i) => (
                    <MentionRow
                      key={item.messageId}
                      item={item}
                      index={i}
                      reducedMotion={reducedMotion}
                      meName={me.name}
                      onPress={() => {
                        haptic(10)
                        onOpenConversation(item.conversationId)
                      }}
                    />
                  ))}
                </div>
              </div>
            )}
          </div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

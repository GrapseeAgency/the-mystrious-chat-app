// ─────────────────────────────────────────────────────────────
// Pulse — #/calls sub-page (R34-a).
// WhatsApp "Calls" tab paradigm INSIDE the chats tab, mounted
// exactly like #/chats/channels: glass page, hash-routed
// (navigate('/calls') opens, backHash() closes), staggered row
// entrances, day sections (Today / Yesterday / Earlier).
// Data is the REAL GET /api/calls log (R33-a) — no mocks:
//   • direction arrow — PhoneOutgoing / PhoneIncoming (rose when missed)
//   • peer avatar stays CIRCULAR (people, not things — shapes system)
//   • Video glyph on video rows, time + duration on the right
//   • tap a row → opens that DM via the same navigation call the
//     chats list uses
// Empty state is honest: no calls yet — start one from any chat.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowLeft,
  LoaderCircle,
  Phone,
  PhoneIncoming,
  PhoneOutgoing,
  Video,
} from 'lucide-react'
import type { AppUser } from '@/lib/types'
import type { CallLogItem } from '@/lib/call-types'
import { apiJson, formatListStamp } from '@/lib/pulse-utils'
import { spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Skeleton } from '@/components/ui/skeleton'
import { UserAvatar } from '@/components/chat/user-avatar'

interface CallsResponse {
  items: CallLogItem[]
}

export interface CallsPageProps {
  /** reactive: hash path === '/calls' */
  open: boolean
  me: AppUser
  onBack: () => void
  /** open the DM a call belongs to — the same navigation call the chats list uses */
  onOpenConversation: (conversationId: string) => void
}

/** durationSec → "45 sec" · "12 min" · "1 h 05 min" (empty when 0/absent). */
function formatCallDuration(sec: number): string {
  if (!Number.isFinite(sec) || sec <= 0) return ''
  if (sec < 60) return `${sec} sec`
  const minutes = Math.floor(sec / 60)
  if (minutes < 60) {
    const rest = sec % 60
    return rest > 0 ? `${minutes} min ${rest} sec` : `${minutes} min`
  }
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`
}

/** Calendar-day bucket in the viewer's local time. */
function dayBucketOf(iso: string, now: Date): 'today' | 'yesterday' | 'earlier' {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return 'earlier'
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  if (sameDay(d, now)) return 'today'
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (sameDay(d, yesterday)) return 'yesterday'
  return 'earlier'
}

/** Section label — same rhythm as the archived / channels pages. */
function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="px-3 pb-1.5 pt-4 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
      {children}
    </p>
  )
}

/** One call-history row — 56px target, direction arrow + circle peer avatar. */
function CallRow({
  item,
  index,
  reducedMotion,
  onPress,
}: {
  item: CallLogItem
  index: number
  reducedMotion: boolean | null
  onPress: () => void
}) {
  const missed = item.status !== 'completed' // 'missed' | 'declined'
  // empty-peer safe: the API falls back to { name: 'Unknown' } but never trust the wire
  const peerName = item.peer?.name?.trim() ? item.peer.name : 'Unknown'
  const peerColor = item.peer?.color ?? 'emerald'
  const peerAvatar = item.peer?.avatar ?? null
  const Arrow = item.outgoing ? PhoneOutgoing : PhoneIncoming
  const verb = missed
    ? item.status === 'declined'
      ? 'Declined'
      : 'Missed'
    : item.outgoing
      ? 'Outgoing'
      : 'Incoming'
  const kindNoun = item.kind === 'video' ? 'video call' : 'voice call'
  return (
    <motion.button
      type="button"
      initial={reducedMotion ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...spring.soft, delay: stagger(Math.min(index, 9)) }}
      whileTap={reducedMotion ? undefined : { scale: 0.985 }}
      onClick={onPress}
      className="glass-row-hover flex w-full items-center gap-3 rounded-2xl px-2.5 py-2.5 text-left outline-none"
      role="row"
      aria-label={`${verb} ${kindNoun} with ${peerName}, ${formatListStamp(item.startedAt)}`}
    >
      {/* people are CIRCLES — the shapes system only squircles things */}
      <UserAvatar name={peerName} color={peerColor} avatar={peerAvatar} size={44} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14.5px] font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          {peerName}
        </span>
        <span
          className={cn(
            'mt-0.5 flex items-center gap-1.5 text-[12px]',
            missed
              ? 'font-medium text-rose-500 dark:text-rose-400'
              : 'text-zinc-500 dark:text-zinc-400',
          )}
        >
          <span
            aria-hidden
            className={cn(
              'flex size-5 shrink-0 items-center justify-center rounded-full',
              missed ? 'bg-rose-500/10' : 'bg-emerald-500/10',
            )}
          >
            <Arrow className={cn('size-3', missed ? 'text-rose-500 dark:text-rose-400' : 'text-emerald-600 dark:text-emerald-400')} />
          </span>
          <span className="truncate">
            {verb} {kindNoun}
          </span>
          {item.kind === 'video' ? (
            <Video className="size-3.5 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
          ) : null}
        </span>
      </span>
      <span className="shrink-0 text-right">
        <span className="block text-[11px] text-zinc-400 dark:text-zinc-500">
          {formatListStamp(item.startedAt)}
        </span>
        {item.status === 'completed' && item.durationSec > 0 ? (
          <span className="mt-0.5 block text-[10.5px] font-medium tabular-nums text-zinc-400 dark:text-zinc-500">
            {formatCallDuration(item.durationSec)}
          </span>
        ) : null}
      </span>
    </motion.button>
  )
}

export function CallsPage({ open, me, onBack, onOpenConversation }: CallsPageProps) {
  const reducedMotion = useReducedMotion()

  const calls = useQuery({
    queryKey: ['calls', me.id],
    queryFn: async (): Promise<CallsResponse> =>
      apiJson<CallsResponse>(`/api/calls?userId=${encodeURIComponent(me.id)}`),
    enabled: open,
    refetchInterval: 20_000,
  })

  const items = calls.data?.items ?? []

  /** Day sections — Today / Yesterday / Earlier, newest first inside each. */
  const sections = useMemo(() => {
    const now = new Date()
    const buckets: Record<'today' | 'yesterday' | 'earlier', CallLogItem[]> = {
      today: [],
      yesterday: [],
      earlier: [],
    }
    for (const item of items) buckets[dayBucketOf(item.startedAt, now)].push(item)
    return (
      [
        { key: 'today', label: 'Today', rows: buckets.today },
        { key: 'yesterday', label: 'Yesterday', rows: buckets.yesterday },
        { key: 'earlier', label: 'Earlier', rows: buckets.earlier },
      ] as const
    ).filter((section) => section.rows.length > 0)
  }, [items])

  return (
    <AnimatePresence>
      {open ? (
        <motion.div
          key="calls-page"
          initial={reducedMotion ? false : { opacity: 0, x: '7%' }}
          animate={{ opacity: 1, x: 0 }}
          exit={reducedMotion ? { opacity: 0 } : { opacity: 0, x: '7%' }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="absolute inset-0 z-30 flex flex-col bg-white dark:bg-zinc-900"
          role="region"
          aria-label="Calls"
        >
          {/* frosted sub-page header — same glass recipe as #/chats/archived */}
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
                  Calls
                  {items.length > 0 ? (
                    <span className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-emerald-500/15 px-1.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                      {items.length > 99 ? '99+' : items.length}
                    </span>
                  ) : null}
                </h1>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  Voice and video history — tap a row to reopen the chat
                </p>
              </div>
              {calls.isFetching ? (
                <LoaderCircle className="size-4 shrink-0 animate-spin text-emerald-500" aria-hidden />
              ) : null}
            </div>
          </header>

          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-6">
            {calls.isPending ? (
              <div className="space-y-2 px-2 pt-4" role="status" aria-label="Loading calls">
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-11/12 rounded-2xl" />
              </div>
            ) : calls.isError ? (
              <motion.div
                initial={reducedMotion ? false : { opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3 }}
                className="flex flex-col items-center justify-center gap-3 px-8 pt-24 text-center"
              >
                <span aria-hidden className="glass-deep flex size-16 items-center justify-center rounded-3xl">
                  <Phone className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  Could not load calls
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  {calls.error instanceof Error ? calls.error.message : 'Something went wrong.'}
                </p>
                <button
                  type="button"
                  onClick={() => void calls.refetch()}
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
                  <Phone className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  No calls yet
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  No calls yet — start one from any chat. Tap the phone or video icon in a DM
                  header and the history lands here.
                </p>
              </motion.div>
            ) : (
              <div className="px-2">
                {sections.map((section) => (
                  <div key={section.key}>
                    <SectionLabel>
                      {section.label} · {section.rows.length}
                    </SectionLabel>
                    <div className="glass-deep glass-sheen rounded-3xl p-1.5">
                      {section.rows.map((item, i) => (
                        <CallRow
                          key={item.id}
                          item={item}
                          index={i}
                          reducedMotion={reducedMotion}
                          onPress={() => {
                            haptic(10)
                            onOpenConversation(item.conversationId)
                          }}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

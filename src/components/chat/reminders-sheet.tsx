// ─────────────────────────────────────────────────────────────
// Pulse — per-message reminders (R30-b, Beeper/Zulip-style).
//
// Three exports wire the whole feature into chat-room:
//   1. <RemindersSheet> — dark glass bottom panel listing the
//      viewer's upcoming reminders (anchored snippet or chat name,
//      live countdown, tap-to-jump, cancel) plus a muted collapsible
//      History group of already-fired rows.
//   2. useReminderDueLoop(meId) — mounted once per room; polls
//      GET /api/reminders?due=1 every 30s (skips while the tab is
//      hidden) and delivers each due reminder as a sonner toast with
//      a View action, then PATCHes it fired. Idempotent: a module-
//      level fired-id Set survives re-mounts, the server-side
//      firedAt stamp survives reloads.
//   3. REMINDER_JUMP_EVENT — fired by toast actions + sheet rows;
//      chat-room listens and jumps (or toasts for other rooms).
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion } from 'framer-motion'
import { Bell, BellOff, ChevronDown, History, LoaderCircle } from 'lucide-react'
import { toast } from 'sonner'
import type { ReminderItem } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { spring } from '@/lib/motion'
import { GlassMenu, GlassMenuLabel, GlassMenuSeparator } from '@/components/ui/glass-menu'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

// ── event + cache contracts ──────────────────────────────────

/** Fired by toast View actions + sheet rows — chat-room jumps to the anchor. */
export const REMINDER_JUMP_EVENT = 'pulse:reminder-jump'

export interface ReminderJumpDetail {
  conversationId: string
  messageId: string | null
  /** display name for cross-room fallback toasts */
  conversationName: string
}

/** Shared TanStack cache key for the viewer's reminder list (badge + sheet). */
export function remindersKey(meId: string): ['reminders', string] {
  return ['reminders', meId]
}

export async function fetchReminders(meId: string, dueOnly = false): Promise<ReminderItem[]> {
  const res = await apiJson<{ items: ReminderItem[] }>(
    `/api/reminders?userId=${encodeURIComponent(meId)}${dueOnly ? '&due=1' : ''}`,
  )
  return res.items
}

// ── formatting helpers ───────────────────────────────────────

const hmFormatter = new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })
const weekdayFormatter = new Intl.DateTimeFormat('en-US', { weekday: 'short' })
const dayMonthFormatter = new Intl.DateTimeFormat('en-US', { day: 'numeric', month: 'short' })

function isSameCalendarDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

/**
 * Human countdown for a future timestamp: "in <1m" / "in 12m" / "in 3h" /
 * "tomorrow 09:00" / "Fri 14:00" (≤7 days) / "Aug 3, 09:00" (beyond).
 */
export function formatReminderCountdown(iso: string, now: Date = new Date()): string {
  const target = new Date(iso)
  if (Number.isNaN(target.getTime())) return ''
  const diffMs = target.getTime() - now.getTime()
  if (diffMs <= 45_000) return 'due now'
  if (diffMs < 3_600_000) return `in ${Math.max(1, Math.round(diffMs / 60_000))}m`
  const hours = Math.floor(diffMs / 3_600_000)
  const tomorrow = new Date(now)
  tomorrow.setDate(tomorrow.getDate() + 1)
  if (isSameCalendarDay(target, tomorrow)) return `tomorrow ${hmFormatter.format(target)}`
  if (hours < 24) return `in ${hours}h`
  const nextWeek = new Date(now)
  nextWeek.setDate(nextWeek.getDate() + 7)
  if (target.getTime() <= nextWeek.getTime()) {
    return `${weekdayFormatter.format(target)} ${hmFormatter.format(target)}`
  }
  return `${dayMonthFormatter.format(target)}, ${hmFormatter.format(target)}`
}

// ── /remind relative-time parser (kept small + pure/testable) ──

export interface ParsedReminder {
  note: string
  remindAt: Date
}

const DURATION_TOKEN =
  '(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|week|weeks)'

function durationToMs(value: number, unit: string): number | null {
  switch (unit.charAt(0)) {
    case 'm':
      return value * 60_000
    case 'h':
      return value * 3_600_000
    case 'd':
      return value * 86_400_000
    case 'w':
      return value * 7 * 86_400_000
    default:
      return null
  }
}

function endOfDayShift(base: Date, hour: number): Date {
  const d = new Date(base)
  d.setHours(hour, 0, 0, 0)
  if (d.getTime() <= base.getTime()) d.setDate(d.getDate() + 1)
  return d
}

/**
 * Parse the tail of a "/remind <note> <time>" draft into { note, remindAt }.
 * Accepted trailing times: "in 30m|2h|1d|2w", bare "30m|2h", "tomorrow"
 * (09:00), "tonight" (20:00), "next week" (same time +7d). Case-insensitive.
 * Returns null when no sane future time token is found or the note is empty.
 */
export function parseRelativeReminder(arg: string, now: Date = new Date()): ParsedReminder | null {
  const input = arg.trim().replace(/\s+/g, ' ')
  if (input.length === 0) return null
  const patterns: Array<{ re: RegExp; stripTrailingIn?: boolean; resolve: (m: RegExpMatchArray) => Date | null }> = [
    {
      re: new RegExp(`\\s+in\\s+(\\d+)\\s*${DURATION_TOKEN}$`, 'i'),
      resolve: (m) => {
        const ms = durationToMs(Number(m[1]), m[2].toLowerCase())
        return ms === null ? null : new Date(now.getTime() + ms)
      },
    },
    {
      // bare trailing token ("buy milk 30m") — a leftover "in" before the
      // number ("log in in 30m") is stripped from the note, not sent.
      re: new RegExp(`\\s+(\\d+)\\s*${DURATION_TOKEN}$`, 'i'),
      stripTrailingIn: true,
      resolve: (m) => {
        const ms = durationToMs(Number(m[1]), m[2].toLowerCase())
        return ms === null ? null : new Date(now.getTime() + ms)
      },
    },
    { re: /\s+next\s+week$/i, resolve: () => new Date(now.getTime() + 7 * 86_400_000) },
    { re: /\s+tomorrow$/i, resolve: () => endOfDayShift(now, 9) },
    { re: /\s+tonight$/i, resolve: () => endOfDayShift(now, 20) },
  ]
  for (const { re, stripTrailingIn, resolve } of patterns) {
    const m = input.match(re)
    if (!m) continue
    const remindAt = resolve(m)
    if (!remindAt) continue
    let note = input.slice(0, m.index ?? 0).trim()
    if (stripTrailingIn) note = note.replace(/\s+in$/i, '').trim()
    if (note.length === 0 || note.toLowerCase() === 'in') return null
    return { note, remindAt }
  }
  return null
}

// ── due-loop hook ────────────────────────────────────────────

/**
 * Fired reminder ids — module scope so a sheet/hook re-mount can never
 * double-toast the same row within a session (firedAt covers reloads).
 */
const deliveredReminderIds = new Set<string>()

const DUE_POLL_MS = 30_000

/**
 * Poll due reminders every 30s while mounted; toast each one once with a
 * View action that fires REMINDER_JUMP_EVENT, then PATCH it fired. Skipped
 * while the tab is hidden (a visibility-change back to visible polls at once).
 */
export function useReminderDueLoop(meId: string): void {
  const queryClient = useQueryClient()
  const inFlightRef = useRef(false)

  useEffect(() => {
    if (!meId) return
    let cancelled = false

    const poll = async () => {
      if (cancelled || inFlightRef.current) return
      if (typeof document !== 'undefined' && document.hidden) return
      inFlightRef.current = true
      try {
        const due = await fetchReminders(meId, true)
        let changed = false
        for (const item of due) {
          if (deliveredReminderIds.has(item.id)) continue
          deliveredReminderIds.add(item.id)
          changed = true

          const detail: ReminderJumpDetail = {
            conversationId: item.conversationId,
            messageId: item.messageId,
            conversationName: item.conversation.name,
          }
          const title = item.note.trim().length > 0 ? item.note.trim() : (item.snippet ?? 'Reminder')
          haptic(24)
          toast(title, {
            description: item.conversation.name,
            duration: 8_000,
            action: {
              label: 'View',
              onClick: () => {
                window.dispatchEvent(new CustomEvent<ReminderJumpDetail>(REMINDER_JUMP_EVENT, { detail }))
              },
            },
          })

          // Mark fired server-side (survives reloads); refresh the shared cache.
          void apiJson(`/api/reminders/${encodeURIComponent(item.id)}`, {
            method: 'PATCH',
            body: JSON.stringify({ userId: meId }),
          })
            .catch(() => {
              // Server stamp is best-effort: the delivered-id Set already
              // prevents re-toasting this session.
            })
        }
        if (changed) {
          void queryClient.invalidateQueries({ queryKey: remindersKey(meId) })
        }
      } catch {
        // Network hiccup — the next tick retries.
      } finally {
        inFlightRef.current = false
      }
    }

    void poll()
    const interval = setInterval(() => void poll(), DUE_POLL_MS)
    const onVisibility = () => {
      if (typeof document !== 'undefined' && !document.hidden) void poll()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      cancelled = true
      clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [meId, queryClient])
}

// ── sheet ────────────────────────────────────────────────────

export interface RemindersSheetProps {
  onClose: () => void
  myId: string
}

/** One row's primary text: note, anchored snippet, or a generic fallback. */
function reminderText(item: ReminderItem): string {
  const note = item.note.replace(/\s+/g, ' ').trim()
  if (note.length > 0) return note
  const snippet = item.snippet?.replace(/\s+/g, ' ').trim() ?? ''
  if (snippet.length > 0) return snippet
  return item.messageId === null ? 'Reminder' : 'Message reminder'
}

export function RemindersSheet({ onClose, myId }: RemindersSheetProps) {
  const queryClient = useQueryClient()
  const [historyOpen, setHistoryOpen] = useState(false)

  const listQuery = useQuery({
    queryKey: remindersKey(myId),
    queryFn: () => fetchReminders(myId),
    staleTime: 5_000,
  })
  const items = listQuery.data ?? []
  const upcoming = items.filter((item) => item.firedAt === null)
  const history = items
    .filter((item) => item.firedAt !== null)
    .sort((a, b) => Date.parse(b.remindAt) - Date.parse(a.remindAt))

  const cancelReminder = useMutation({
    mutationFn: async (id: string) => {
      return apiJson<{ ok: true }>(`/api/reminders/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        body: JSON.stringify({ userId: myId }),
      })
    },
    onMutate: async (id: string) => {
      await queryClient.cancelQueries({ queryKey: remindersKey(myId) })
      const previous = queryClient.getQueryData<ReminderItem[]>(remindersKey(myId))
      queryClient.setQueryData<ReminderItem[]>(remindersKey(myId), (old) =>
        (old ?? []).filter((item) => item.id !== id),
      )
      return { previous }
    },
    onError: (_error, _id, context) => {
      if (context?.previous) {
        queryClient.setQueryData(remindersKey(myId), context.previous)
      }
      toast.error('Could not cancel the reminder')
    },
    onSuccess: () => {
      toast.success('Reminder canceled')
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: remindersKey(myId) })
    },
  })

  const jump = (item: ReminderItem) => {
    haptic(8)
    const detail: ReminderJumpDetail = {
      conversationId: item.conversationId,
      messageId: item.messageId,
      conversationName: item.conversation.name,
    }
    window.dispatchEvent(new CustomEvent<ReminderJumpDetail>(REMINDER_JUMP_EVENT, { detail }))
  }

  const renderRow = (item: ReminderItem, idx: number, fired: boolean) => (
    <motion.li
      key={item.id}
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...spring.soft, delay: Math.min(idx * 0.03, 0.18) }}
      className={cn('glass-row-hover rounded-xl p-2', fired && 'opacity-60')}
    >
      <div className="flex items-center gap-2">
        <span
          className={cn(
            'flex size-6 shrink-0 items-center justify-center rounded-full',
            fired ? 'bg-zinc-500/10' : 'bg-emerald-500/10',
          )}
          aria-hidden
        >
          {fired ? (
            <BellOff className="size-3 text-zinc-400 dark:text-zinc-500" />
          ) : (
            <Bell className="size-3 text-emerald-500" />
          )}
        </span>
        <button
          type="button"
          onClick={() => {
            if (fired) return
            jump(item)
          }}
          disabled={fired}
          className="min-w-0 flex-1 text-left outline-none"
          aria-label={
            fired
              ? `Fired reminder — ${reminderText(item)}`
              : `Jump to reminder — ${reminderText(item)} in ${item.conversation.name}`
          }
        >
          <span className="block truncate text-xs font-semibold text-zinc-700 dark:text-zinc-200">
            {item.conversation.name}
          </span>
          <span className="block truncate text-[13px] leading-snug text-zinc-600 dark:text-zinc-300">
            {reminderText(item)}
          </span>
        </button>
        {!fired ? (
          <>
            <span
              className="shrink-0 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold tabular-nums text-emerald-600 dark:text-emerald-400"
              title={new Date(item.remindAt).toLocaleString()}
            >
              {formatReminderCountdown(item.remindAt)}
            </span>
            <button
              type="button"
              aria-label={`Cancel reminder — ${reminderText(item)}`}
              disabled={cancelReminder.isPending && cancelReminder.variables === item.id}
              onClick={() => {
                haptic(12)
                cancelReminder.mutate(item.id)
              }}
              className="flex size-7 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90 disabled:opacity-50 dark:text-zinc-500"
            >
              {cancelReminder.isPending && cancelReminder.variables === item.id ? (
                <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
              ) : (
                <BellOff className="size-3.5" aria-hidden />
              )}
            </button>
          </>
        ) : (
          <span className="shrink-0 text-[10px] tabular-nums text-zinc-400 dark:text-zinc-500">
            fired
          </span>
        )}
      </div>
    </motion.li>
  )

  return (
    <>
      {/* backdrop — tap anywhere outside to dismiss */}
      <motion.button
        type="button"
        aria-hidden
        tabIndex={-1}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.16 }}
        onClick={onClose}
        className="fixed inset-0 z-[62] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
      />
      <GlassMenu
        aria-label={
          upcoming.length === 1 ? '1 reminder' : `${upcoming.length} reminders`
        }
        className="glass-menu-panel fixed bottom-[max(0.9rem,env(safe-area-inset-bottom))] left-1/2 z-[63] flex max-h-[56dvh] w-[min(420px,calc(100vw-16px))] translate-x-[-50%] flex-col rounded-2xl"
      >
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="flex items-center gap-1.5 px-2 pt-1">
            <span
              className="flex size-6 shrink-0 items-center justify-center rounded-full bg-emerald-500/10"
              aria-hidden
            >
              <Bell className="size-3 text-emerald-500" />
            </span>
            <GlassMenuLabel className="flex-1 px-1 pb-0 pt-1.5">
              {upcoming.length === 1 ? '1 reminder' : `${upcoming.length} reminders`}
            </GlassMenuLabel>
          </div>
          <GlassMenuSeparator className="mb-0.5" />
          {listQuery.isPending && items.length === 0 ? (
            <div className="space-y-2 px-2 pb-2" role="status" aria-label="Loading reminders">
              <Skeleton className="h-14 w-full rounded-xl" />
              <Skeleton className="h-14 w-5/6 rounded-xl" />
            </div>
          ) : upcoming.length === 0 && history.length === 0 ? (
            <p className="px-4 pb-3 pt-1 text-center text-xs text-zinc-400 dark:text-zinc-500">
              No reminders yet — long-press a message and choose Remind me.
            </p>
          ) : (
            <div className="pulse-scroll min-h-0 overflow-y-auto px-1 pb-1.5">
              {upcoming.length > 0 ? (
                <ul className="space-y-0.5">{upcoming.map((item, idx) => renderRow(item, idx, false))}</ul>
              ) : (
                <p className="px-3 pb-1 pt-1 text-center text-xs text-zinc-400 dark:text-zinc-500">
                  Nothing upcoming.
                </p>
              )}

              {history.length > 0 ? (
                <div className="mt-1">
                  <button
                    type="button"
                    onClick={() => setHistoryOpen((v) => !v)}
                    aria-expanded={historyOpen}
                    className="flex w-full items-center gap-1.5 rounded-xl px-2 py-1.5 text-left outline-none transition-colors hover:bg-zinc-900/[0.04] dark:hover:bg-white/[0.05]"
                  >
                    <History className="size-3 text-zinc-400 dark:text-zinc-500" aria-hidden />
                    <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-zinc-400 dark:text-zinc-500">
                      History · {history.length}
                    </span>
                    <ChevronDown
                      className={cn(
                        'ml-auto size-3 text-zinc-400 transition-transform duration-200 dark:text-zinc-500',
                        historyOpen && 'rotate-180',
                      )}
                      aria-hidden
                    />
                  </button>
                  <AnimatePresence initial={false}>
                    {historyOpen ? (
                      <motion.ul
                        key="reminder-history"
                        initial={{ opacity: 0, height: 0 }}
                        animate={{ opacity: 1, height: 'auto' }}
                        exit={{ opacity: 0, height: 0 }}
                        transition={{ duration: 0.2, ease: 'easeInOut' }}
                        className="space-y-0.5 overflow-hidden"
                      >
                        {history.map((item, idx) => renderRow(item, idx, true))}
                      </motion.ul>
                    ) : null}
                  </AnimatePresence>
                </div>
              ) : null}
            </div>
          )}
        </div>
      </GlassMenu>
    </>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — leaderboard sheet (Task R24-d, Twitch-style XP ranks)
//
// Bottom sheet with the room's full ranked ladder: XP bars (spring
// widths, relative to the room's XP leader), medals for the top 3
// and real per-row mini-stats (messages / game wins / tournament
// points). Every number comes from GET /api/leaderboard — the same
// all-aggregates API — refreshed every 20s while open.
//
// Wiring contract for chat-room / group-info (lead + R24-d):
//   const lb = useLeaderboardSheet(conversationId, me.id)
//   ... {lb.node}
// and open it via the `pulse:open-leaderboard` CustomEvent.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { LoaderCircle, MessageSquare, Swords, Trophy, X } from 'lucide-react'
import { apiJson, gradientFor } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import type { LeaderboardRow } from '@/lib/types'
import { cn } from '@/lib/utils'
import { spring, stagger } from '@/lib/motion'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'

/** Window CustomEvent that opens the sheet — fired by group-info/composer. */
export const LEADERBOARD_OPEN_EVENT = 'pulse:open-leaderboard'

const REFRESH_MS = 20_000

interface LeaderboardResponse {
  rows: LeaderboardRow[]
}

const MEDALS = ['1', '2', '3'] as const

function Sheet({ open, onClose, conversationId, meId }: {
  open: boolean
  onClose: () => void
  conversationId: string
  meId: string
}) {
  const reducedMotion = useReducedMotion()

  const rowsQ = useQuery({
    queryKey: ['leaderboard-full', conversationId], // distinct from the mini-section key: this query returns the RAW {rows} response, the section's returns the array — sharing a key poisoned the cache both ways
    queryFn: () =>
      apiJson<LeaderboardResponse>(
        `/api/leaderboard?conversationId=${encodeURIComponent(conversationId)}&userId=${encodeURIComponent(meId)}`,
      ),
    enabled: open && conversationId.length > 0 && meId.length > 0,
    refetchInterval: open ? REFRESH_MS : false,
    staleTime: 5_000,
  })

  const rows = rowsQ.data?.rows ?? []
  const maxXp = useMemo(
    () => (rows.length > 0 ? Math.max(...rows.map((r) => r.xp)) : 0),
    [rows],
  )

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl border-white/10 bg-zinc-950 dark:border-white/10 dark:bg-zinc-950">
        <DrawerTitle className="sr-only">Leaderboard</DrawerTitle>
        <DrawerDescription className="sr-only">
          Ranked by tournament points, then game wins, then XP — every stat is a live aggregate
        </DrawerDescription>

        <motion.div
          initial={{ opacity: 0, y: 24, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="flex flex-col gap-3 px-4 pb-6 pt-2"
        >
          {/* header */}
          <header className="flex items-center gap-2.5">
            <span
              aria-hidden
              className="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-emerald-600 shadow-[0_10px_24px_-10px_rgba(16,185,129,0.8)]"
            >
              <Trophy className="size-5 text-white" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold leading-tight text-zinc-50">Leaderboard</p>
              <p className="text-[11px] font-medium leading-tight text-zinc-500">
                Points → wins → XP · live every 20s
              </p>
            </div>
            <button
              type="button"
              aria-label="Close leaderboard"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
            >
              <X className="size-4.5" aria-hidden />
            </button>
          </header>

          {/* ranked list */}
          {rowsQ.isPending ? (
            <div className="flex items-center justify-center gap-2 py-10 text-zinc-400">
              <LoaderCircle className="size-5 animate-spin" aria-hidden />
              <span className="text-[13px] font-medium">Ranking the room…</span>
            </div>
          ) : rowsQ.isError ? (
            <div className="rounded-2xl border border-rose-500/25 bg-rose-500/10 px-4 py-5 text-center">
              <p className="text-[13px] font-medium text-zinc-300">
                Could not load the leaderboard.
              </p>
              <button
                type="button"
                onClick={() => void rowsQ.refetch()}
                className="mt-2 rounded-full border border-white/15 px-4 py-1.5 text-[12px] font-bold text-zinc-200 outline-none ring-emerald-400/60 focus-visible:ring-2"
              >
                Try again
              </button>
            </div>
          ) : rows.length === 0 ? (
            <p className="py-10 text-center text-[13px] font-medium text-zinc-500">
              No members to rank yet.
            </p>
          ) : (
            <div
              className="pulse-scroll flex max-h-[58dvh] flex-col gap-1 overflow-y-auto pr-0.5"
              role="list"
              aria-label="Full leaderboard"
            >
              {rows.map((row, i) => {
                const isMe = row.userId === meId
                const pct = maxXp > 0 ? Math.round((row.xp / maxXp) * 100) : 0
                return (
                  <motion.div
                    key={row.userId}
                    role="listitem"
                    initial={reducedMotion ? false : { opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: stagger(i, 0.035), ...spring.soft }}
                    style={{ willChange: 'transform' }}
                    className={cn(
                      'rounded-2xl border px-3 py-2.5',
                      isMe
                        ? 'border-emerald-400/60 bg-emerald-500/10 ring-1 ring-emerald-400/40'
                        : 'border-white/10 bg-white/[0.04]',
                    )}
                    aria-current={isMe ? 'true' : undefined}
                  >
                    <div className="flex items-center gap-2.5">
                      <span
                        aria-hidden
                        className={cn(
                          'w-6 shrink-0 text-center text-[13px] font-bold tabular-nums',
                          i === 0 ? 'text-amber-500' : i === 1 ? 'text-zinc-400' : i === 2 ? 'text-orange-400' : 'text-zinc-400',
                        )}
                      >
                        {i < 3 ? MEDALS[i] : i + 1}
                      </span>
                      <span
                        aria-hidden
                        className={cn(
                          'flex size-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br text-[12px] font-bold text-white',
                          gradientFor(row.color),
                        )}
                      >
                        {row.name.slice(0, 1).toUpperCase()}
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="flex items-center gap-1.5 truncate text-[13.5px] font-bold text-zinc-100">
                          <span className="truncate">{row.name}</span>
                          {isMe ? (
                            <span className="shrink-0 rounded-full bg-emerald-500/20 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-emerald-300">
                              You
                            </span>
                          ) : null}
                        </p>
                        {/* XP bar — spring width relative to the room's XP leader */}
                        <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-white/10">
                          <motion.div
                            aria-hidden
                            className="h-full rounded-full bg-gradient-to-r from-emerald-400 to-emerald-600"
                            initial={reducedMotion ? { width: `${pct}%` } : { width: 0 }}
                            animate={{ width: `${pct}%` }}
                            transition={spring.soft}
                            style={{ willChange: 'width' }}
                          />
                        </div>
                      </div>
                      <span className="shrink-0 text-right">
                        <span className="block text-[14px] font-extrabold tabular-nums leading-tight text-emerald-400">
                          {row.xp}
                        </span>
                        <span className="block text-[9.5px] font-bold uppercase tracking-wide text-zinc-500">
                          XP
                        </span>
                      </span>
                    </div>
                    {/* mini-stats — real counts from the API */}
                    <div className="mt-1.5 flex items-center gap-3 pl-[34px] text-[10.5px] font-semibold text-zinc-400">
                      <span className="flex items-center gap-1" title="Messages in this chat">
                        <MessageSquare className="size-3" aria-hidden />
                        {row.messageCount} msg
                      </span>
                      <span className="flex items-center gap-1" title="Game wins in this chat">
                        <Swords className="size-3" aria-hidden />
                        {row.gameWins} win{row.gameWins === 1 ? '' : 's'}
                      </span>
                      <span className="flex items-center gap-1" title="Tournament points">
                        <Trophy className="size-3" aria-hidden />
                        {row.tournamentPoints} pt{row.tournamentPoints === 1 ? '' : 's'}
                      </span>
                    </div>
                  </motion.div>
                )
              })}
            </div>
          )}
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}

// ── hook (2-line wiring, mirrors useRedPacketSheet) ──────────

/**
 * Mount once per room:
 *   const lb = useLeaderboardSheet(conversationId, me.id)
 *   ... {lb.node}
 * Listens for the `pulse:open-leaderboard` CustomEvent dispatched by
 * the group-info Leaderboard section / composer.
 */
export function useLeaderboardSheet(conversationId: string, meId: string) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(LEADERBOARD_OPEN_EVENT, handler)
    return () => window.removeEventListener(LEADERBOARD_OPEN_EVENT, handler)
  }, [])

  const node = (
    <Sheet
      open={open}
      onClose={() => setOpen(false)}
      conversationId={conversationId}
      meId={meId}
    />
  )

  return { open, setOpen, node }
}

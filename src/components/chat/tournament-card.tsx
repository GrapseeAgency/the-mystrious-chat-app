// ─────────────────────────────────────────────────────────────
// Pulse — tournament standings card (Task R24-d, Twitch-style XP)
//
// Self-fetching bubble for kind:'tournament' messages. Renders the
// live season card: 🏆 name + game chip + status, the top-3 podium
// (medal rows with layout springs), real player count, and a JOIN
// button for members of the room who haven't entered yet. Polls
// every 15s while the season runs so standings stay fresh.
//
// Wiring contract for chat-room (lead): render inside the
// kind === 'tournament' branch —
//   <TournamentCard tournamentId={parsed.tournamentId} meId={me.id} />
// (the wrapper adds `data-card-interactive`, not this component).
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useMemo, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle, Swords, Trophy, Users } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson, gradientFor, jsonBody } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { fireParticles, spring, stagger } from '@/lib/motion'
import type { TournamentSummary } from '@/lib/types'
import { glassSurface } from '@/components/ui/glass-card'

const POLL_MS = 15_000

/** Wire shape of GET /api/tournaments/[id]. */
interface TournamentDetail {
  tournament: TournamentSummary
}

/** Wire shape of POST /api/tournaments/[id]/join. */
interface JoinResponse {
  entry: { id: string; tournamentId: string; userId: string; points: number }
}

const MEDALS = ['1', '2', '3'] as const

/** Compact initial-circle avatar keyed by the user's palette color. */
function StandingsAvatar({ name, color }: { name: string; color: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        'flex size-7 shrink-0 items-center justify-center rounded-full bg-gradient-to-br text-[10px] font-bold text-white',
        gradientFor(color),
      )}
    >
      {name.slice(0, 1).toUpperCase()}
    </span>
  )
}

export default function TournamentCard({
  tournamentId,
  meId,
}: {
  tournamentId: string
  meId: string
}) {
  const reducedMotion = useReducedMotion()
  const qc = useQueryClient()
  const [joining, setJoining] = useState(false)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['tournament', tournamentId],
    queryFn: async () =>
      apiJson<TournamentDetail>(
        `/api/tournaments/${encodeURIComponent(tournamentId)}`,
      ),
    enabled: tournamentId.length > 0,
    refetchInterval: (query) =>
      query.state.data?.tournament.status === 'running' ? POLL_MS : false,
    staleTime: 5_000,
  })

  const tournament = data?.tournament ?? null
  const running = tournament?.status === 'running'
  const isMember = useMemo(
    () => tournament?.entries.some((e) => e.userId === meId) ?? false,
    [tournament, meId],
  )
  const showJoin = running && !isMember && tournament !== null

  const join = useCallback(async () => {
    if (joining) return
    setJoining(true)
    try {
      await apiJson<JoinResponse>(
        `/api/tournaments/${encodeURIComponent(tournamentId)}/join`,
        jsonBody({ userId: meId }),
      )
      haptic(20)
      fireParticles({ kind: 'stars', count: 44 })
      toast.success('Joined the season — wins now score points')
      void qc.invalidateQueries({ queryKey: ['tournament', tournamentId] })
      void qc.invalidateQueries({ queryKey: ['tournaments'] })
      void qc.invalidateQueries({ queryKey: ['leaderboard'] })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Could not join the tournament.')
      void qc.invalidateQueries({ queryKey: ['tournament', tournamentId] })
    } finally {
      setJoining(false)
    }
  }, [joining, meId, qc, tournamentId])

  if (isError) {
    return (
      <div
        aria-label="Tournament"
        className={cn(glassSurface, 'flex w-60 items-center gap-3 rounded-3xl px-4 py-3.5 sm:w-64')}
      >
        <Trophy className="size-6 text-zinc-400 opacity-40" aria-hidden />
        <p className="text-[12.5px] font-medium text-zinc-500 dark:text-zinc-400">
          Tournament unavailable
        </p>
      </div>
    )
  }

  return (
    <section aria-label="Tournament season" className="w-60 select-none sm:w-64">
      <div className={cn(glassSurface, 'overflow-hidden rounded-3xl')}>
        {/* header */}
        <div className="flex items-center gap-2.5 px-3.5 pb-2.5 pt-3">
          <span
            aria-hidden
            className="flex size-9 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-emerald-600 shadow-[0_8px_20px_-8px_rgba(16,185,129,0.7)]"
          >
            <Trophy className="size-4.5 text-white" aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13.5px] font-bold leading-tight text-zinc-900 dark:text-zinc-50">
              {isLoading ? 'Loading season…' : tournament?.name}
            </p>
            <div className="mt-1 flex items-center gap-1.5">
              <span className="flex items-center gap-1 rounded-full bg-zinc-500/10 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
                <Swords className="size-2.5" aria-hidden />
                {tournament?.game === 'tictactoe' ? 'Tic-tac-toe' : tournament?.game}
              </span>
              <span
                className={cn(
                  'flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide',
                  running
                    ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                    : 'bg-zinc-500/10 text-zinc-500 dark:text-zinc-400',
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    'size-1.5 rounded-full',
                    running ? 'animate-pulse bg-emerald-500' : 'bg-zinc-400',
                  )}
                />
                {running ? 'Running' : 'Finished'}
              </span>
            </div>
          </div>
        </div>

        {/* standings — top-3 medals, layout springs on re-order */}
        <div
          className="pulse-scroll max-h-56 overflow-y-auto px-2.5"
          role="list"
          aria-label="Tournament standings"
        >
          {isLoading ? (
            <div className="flex items-center justify-center gap-2 py-5 text-zinc-400">
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
              <span className="text-[12px] font-medium">Loading standings…</span>
            </div>
          ) : tournament && tournament.entries.length === 0 ? (
            <p className="flex items-center gap-2 px-1.5 py-4 text-[12px] font-medium text-zinc-500 dark:text-zinc-400">
              <Users className="size-3.5 shrink-0" aria-hidden />
              No players yet — be the first
            </p>
          ) : (
            tournament?.entries.map((entry, i) => (
              <motion.div
                key={entry.userId}
                role="listitem"
                layout={!reducedMotion}
                initial={reducedMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: stagger(i, 0.045), ...spring.soft }}
                style={{ willChange: 'transform' }}
                className={cn(
                  'flex min-h-[40px] items-center gap-2.5 rounded-xl px-1.5 py-1.5',
                  i % 2 === 1 && 'bg-zinc-500/[0.04] dark:bg-white/[0.03]',
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    'w-6 shrink-0 text-center text-[13px] font-bold tabular-nums',
                    i === 0 ? 'text-amber-500' : i === 1 ? 'text-zinc-400' : i === 2 ? 'text-orange-400' : 'text-zinc-400 dark:text-zinc-500',
                  )}
                >
                  {i < 3 ? MEDALS[i] : i + 1}
                </span>
                <StandingsAvatar name={entry.name} color={entry.color} />
                <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-zinc-800 dark:text-zinc-100">
                  {entry.name}
                  {entry.userId === meId ? (
                    <span className="font-medium text-emerald-600 dark:text-emerald-400">
                      {' '}
                      · you
                    </span>
                  ) : null}
                </span>
                <span className="shrink-0 text-[12.5px] font-bold tabular-nums text-emerald-600 dark:text-emerald-400">
                  {entry.points} pt{entry.points === 1 ? '' : 's'}
                </span>
              </motion.div>
            ))
          )}
        </div>

        {/* footer + join */}
        <div className="border-t border-zinc-200/70 px-3.5 py-2.5 dark:border-white/10">
          <p className="text-[11px] font-medium text-zinc-500 dark:text-zinc-400">
            {tournament ? `${tournament.entries.length} player${tournament.entries.length === 1 ? '' : 's'}` : '—'}
            {' · '}
            {running ? 'Season running' : 'Season finished'}
          </p>
          {showJoin ? (
            <motion.button
              type="button"
              onClick={() => void join()}
              disabled={joining}
              aria-label={`Join ${tournament?.name ?? 'tournament'}`}
              whileTap={reducedMotion ? undefined : { scale: 0.96 }}
              transition={spring.snappy}
              style={{ willChange: 'transform' }}
              className="mt-2 flex h-10 w-full items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-emerald-500 to-emerald-600 text-[13px] font-bold text-white shadow-[0_10px_24px_-10px_rgba(16,185,129,0.8)] outline-none ring-emerald-400/60 transition-colors focus-visible:ring-2 disabled:opacity-60"
            >
              {joining ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <>
                  <Swords className="size-4" aria-hidden />
                  Join season
                </>
              )}
            </motion.button>
          ) : null}
        </div>
      </div>
    </section>
  )
}

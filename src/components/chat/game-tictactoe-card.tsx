// ─────────────────────────────────────────────────────────────
// Pulse — in-chat tic-tac-toe card (Task R23-b).
// A match rides the chat as a real kind:"game" message; this card
// self-fetches GET /api/games/[id], polls every 1.5s while the
// match is active (TanStack clears the timer on unmount and
// refetchIntervalInBackground:false pauses it when the tab is
// hidden), plays moves with optimistic cell fill and celebrates a
// win with the app-wide confetti layer.
//
// Wiring contract for chat-room (lead): render for kind:"game"
// messages whose payload JSON carries {matchId, game:"tictactoe"} —
//   <GameTicTacToeCard matchId={parsed.matchId} meId={me.id} />
// Rematch dispatches the `pulse:external-message` CustomEvent
// (detail = the fresh ChatMessage) for the room to append.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle, RefreshCw, Swords, Trophy } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson, ApiError } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { spring, fireParticles } from '@/lib/motion'
import type { ChatMessage } from '@/lib/types'
import { GlassCard, glassSurface } from '@/components/ui/glass-card'

/** window CustomEvent fired after a rematch invite message is created. */
export const GAME_EXTERNAL_MESSAGE_EVENT = 'pulse:external-message'

const POLL_MS = 1500
const BOARD_SIZE = 9

// ── wire types (mirror of the REST contract) ─────────────────

export interface GameMatchState {
  id: string
  conversationId: string
  game: string
  playerXId: string
  playerOId: string | null
  board: string
  turn: 'X' | 'O'
  status: string
  winnerId: string | null
  winLine: number[] | null
  moveCount: number
  createdAt: string
  updatedAt: string
}

export interface GamePlayerInfo {
  id: string
  name: string
  color: string
}

interface GameDetail {
  match: GameMatchState
  playerX: GamePlayerInfo
  playerO: GamePlayerInfo | null
}

interface GameCreateResponse {
  match: GameMatchState
  message: ChatMessage
}

// ── name colors — same palette keys the avatars use ──────────

const NAME_TEXT: Record<string, string> = {
  emerald: 'text-emerald-600 dark:text-emerald-400',
  rose: 'text-rose-600 dark:text-rose-400',
  amber: 'text-amber-600 dark:text-amber-500',
  violet: 'text-violet-600 dark:text-violet-400',
  teal: 'text-teal-600 dark:text-teal-400',
  orange: 'text-orange-600 dark:text-orange-400',
  pink: 'text-pink-600 dark:text-pink-400',
  cyan: 'text-cyan-600 dark:text-cyan-400',
}

function nameClass(color: string): string {
  return NAME_TEXT[color] ?? NAME_TEXT.emerald
}

// ── helpers ──────────────────────────────────────────────────

function isFinished(status: string): boolean {
  return status !== 'active'
}

function winnerOf(match: GameMatchState, players: GameDetail): string | null {
  if (match.status === 'x_won') return players.playerX.name
  if (match.status === 'o_won') return players.playerO?.name ?? null
  return null
}

/** Shimmering status strip (reduced-motion → calm static text). */
function StatusLine({ text, reducedMotion }: { text: string; reducedMotion: boolean }) {
  return (
    <motion.p
      aria-live="polite"
      className="text-center text-[12.5px] font-medium text-zinc-500 dark:text-zinc-400"
      animate={reducedMotion ? undefined : { opacity: [0.45, 1, 0.45] }}
      transition={reducedMotion ? undefined : { duration: 1.6, repeat: Infinity, ease: 'easeInOut' }}
    >
      {text}
    </motion.p>
  )
}

// ── the card ─────────────────────────────────────────────────

export default function GameTicTacToeCard({ matchId, meId }: { matchId: string; meId: string }) {
  const queryClient = useQueryClient()
  const reducedMotion = useReducedMotion() ?? false

  const [pendingCell, setPendingCell] = useState<number | null>(null)
  const [joining, setJoining] = useState(false)
  const [rematching, setRematching] = useState(false)
  const celebratedRef = useRef(false)

  const queryKey = useMemo(() => ['game-match', matchId], [matchId])

  // Self-fetch + live sync: 1.5s poll only while the match is active;
  // TanStack Query clears the interval on unmount and pauses it while
  // the document is hidden (refetchIntervalInBackground: false).
  const gameQuery = useQuery({
    queryKey,
    enabled: matchId.length > 0,
    refetchInterval: (query) =>
      (query.state.data as GameDetail | undefined)?.match.status === 'active' ? POLL_MS : false,
    refetchIntervalInBackground: false,
    staleTime: 0,
    retry: 1,
    queryFn: async (): Promise<GameDetail> =>
      apiJson<GameDetail>(`/api/games/${encodeURIComponent(matchId)}`),
  })

  const data = gameQuery.data
  const match = data?.match ?? null

  const mySide: 'X' | 'O' | null = !match
    ? null
    : match.playerXId === meId
      ? 'X'
      : match.playerOId === meId
        ? 'O'
        : null
  const isPlayer = mySide !== null
  const isMyTurn = match?.status === 'active' && mySide !== null && match.turn === mySide
  const isOpenChallenge = match?.status === 'active' && match.playerOId === null
  const canJoin = isOpenChallenge && meId !== match?.playerXId
  const iWon =
    match !== null &&
    (match.status === 'x_won' || match.status === 'o_won') &&
    match.winnerId === meId
  const finished = match !== null && isFinished(match.status)

  // Confetti ONCE when a win by me is observed (live transition or a
  // fresh mount on an already-finished match — never twice per mount).
  useEffect(() => {
    if (!data || !iWon || celebratedRef.current) return
    celebratedRef.current = true
    if (!reducedMotion) fireParticles({ kind: 'confetti', count: 120 })
  }, [data, iWon, reducedMotion])

  const playMove = useCallback(
    async (cell: number) => {
      if (!match || pendingCell !== null) return
      const side = mySide
      if (!side || match.turn !== side || match.board[cell] !== ' ') return

      haptic(6)
      setPendingCell(cell) // optimistic fill rendered below
      try {
        const res = await apiJson<GameDetail>(`/api/games/${encodeURIComponent(matchId)}/move`, {
          method: 'POST',
          body: JSON.stringify({ userId: meId, cell }),
        })
        queryClient.setQueryData(queryKey, res)
      } catch (err) {
        queryClient.invalidateQueries({ queryKey })
        toast.error(err instanceof ApiError ? err.message : 'Move failed — try again.')
      } finally {
        setPendingCell(null)
      }
    },
    [match, mySide, pendingCell, matchId, meId, queryClient, queryKey],
  )

  const joinAsO = useCallback(async () => {
    if (joining) return
    haptic(6)
    setJoining(true)
    try {
      const res = await apiJson<GameDetail>(`/api/games/${encodeURIComponent(matchId)}/join`, {
        method: 'POST',
        body: JSON.stringify({ userId: meId }),
      })
      queryClient.setQueryData(queryKey, res)
      toast.success(`You are O — ${res.playerX.name} moves first.`)
    } catch (err) {
      queryClient.invalidateQueries({ queryKey })
      toast.error(err instanceof ApiError ? err.message : 'Could not join this match.')
    } finally {
      setJoining(false)
    }
  }, [joining, matchId, meId, queryClient, queryKey])

  const rematch = useCallback(async () => {
    if (!match || !isPlayer || rematching) return
    const opponentId = mySide === 'X' ? match.playerOId : match.playerXId
    if (!opponentId) return
    haptic(6)
    setRematching(true)
    try {
      const res = await apiJson<GameCreateResponse>('/api/games', {
        method: 'POST',
        body: JSON.stringify({
          userId: meId,
          conversationId: match.conversationId,
          game: 'tictactoe',
          opponentId,
        }),
      })
      // Hand the fresh invite message to the room (lead-owned listener).
      window.dispatchEvent(
        new CustomEvent<ChatMessage>(GAME_EXTERNAL_MESSAGE_EVENT, { detail: res.message }),
      )
      toast.success('Rematch sent — new challenge in the chat.')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Rematch failed — try again.')
    } finally {
      setRematching(false)
    }
  }, [match, isPlayer, mySide, rematching, meId, queryClient, queryKey])

  // ── loading / error / missing states ───────────────────────
  if (gameQuery.isPending) {
    return (
      <div className={cn(glassSurface, 'w-full max-w-[300px] p-4')} aria-busy="true">
        <div className="mb-3 flex items-center gap-2">
          <span className="text-base">⚔️</span>
          <div className="h-3.5 w-28 animate-pulse rounded-full bg-zinc-200 dark:bg-zinc-700" />
        </div>
        <div className="grid aspect-square grid-cols-3 gap-1.5">
          {Array.from({ length: BOARD_SIZE }).map((_, i) => (
            <div key={i} className="animate-pulse rounded-xl bg-zinc-200/80 dark:bg-zinc-700/60" />
          ))}
        </div>
      </div>
    )
  }

  if (gameQuery.isError || !data || !match) {
    return (
      <div className={cn(glassSurface, 'flex w-full max-w-[300px] flex-col items-center gap-2 p-4')}>
        <span className="text-base" aria-hidden>
          ⚔️
        </span>
        <p className="text-center text-[12.5px] text-zinc-500 dark:text-zinc-400">
          This game could not be loaded.
        </p>
        <button
          type="button"
          onClick={() => gameQuery.refetch()}
          className="inline-flex min-h-[36px] items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 text-[12.5px] font-semibold text-emerald-600 dark:text-emerald-400"
        >
          <RefreshCw className="size-3.5" aria-hidden /> Retry
        </button>
      </div>
    )
  }

  const { playerX, playerO } = data
  const winnerName = winnerOf(match, data)
  const winSet = new Set(match.winLine ?? [])

  // Board cells — server truth overlaid with the optimistic pending move.
  const glyphs = match.board.split('')
  if (pendingCell !== null && glyphs[pendingCell] === ' ' && mySide) {
    glyphs[pendingCell] = mySide
  }

  const statusText = (() => {
    if (finished) {
      if (winnerName) return `${winnerName} wins!`
      if (match.status === 'draw') return 'Draw — board is full.'
      return 'Match abandoned.'
    }
    if (isOpenChallenge) {
      return mySide === 'X' ? 'Open challenge — waiting for an opponent…' : 'Open challenge'
    }
    if (isMyTurn) return 'Your move — tap a cell'
    if (mySide === 'X' && match.playerOId === null) return 'Waiting for an opponent…'
    const other = mySide === 'X' ? playerO : playerX
    return `Waiting for ${other?.name ?? 'opponent'}…`
  })()

  return (
    <GlassCard
      glow="emerald"
      className="w-full max-w-[300px] p-4"
      initial={reducedMotion ? false : { opacity: 0, y: 10, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={spring.soft}
      style={{ willChange: 'transform' }}
      role="group"
      aria-label="Tic-tac-toe game"
    >
      {/* header */}
      <div className="mb-2 flex items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 text-[13px] font-bold tracking-tight text-zinc-800 dark:text-zinc-100">
          <Swords className="size-4 text-emerald-500 dark:text-emerald-400" aria-hidden />
          Tic-tac-toe
        </p>
        {finished ? (
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-wide',
              winnerName
                ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                : 'bg-zinc-500/15 text-zinc-500 dark:text-zinc-400',
            )}
          >
            {match.status === 'draw' ? 'Draw' : 'Finished'}
          </span>
        ) : (
          <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-wide text-emerald-600 dark:text-emerald-400">
            Live
          </span>
        )}
      </div>

      {/* players line */}
      <p className="mb-3 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[12.5px] font-semibold">
        <span className="text-zinc-400 dark:text-zinc-500">X</span>
        <span className={nameClass(playerX.color)}>{playerX.name}</span>
        <span className="text-zinc-400 dark:text-zinc-500">vs</span>
        <span className="text-zinc-400 dark:text-zinc-500">O</span>
        {playerO ? (
          <span className={nameClass(playerO.color)}>{playerO.name}</span>
        ) : (
          <span className="italic text-zinc-400 dark:text-zinc-500">open seat</span>
        )}
      </p>

      {/* board */}
      <div className="grid aspect-square grid-cols-3 gap-1.5">
        {glyphs.map((glyph, i) => {
          const cellFilled = glyph !== ' '
          const isWinCell = winSet.has(i)
          const dimmed = finished && winnerName && !isWinCell
          const canTap = Boolean(isMyTurn && !cellFilled && pendingCell === null)
          const markColor =
            glyph === 'X'
              ? nameClass(playerX.color)
              : glyph === 'O' && playerO
                ? nameClass(playerO.color)
                : 'text-zinc-400 dark:text-zinc-500'
          return (
            <motion.button
              key={i}
              type="button"
              disabled={!canTap}
              aria-label={`Cell ${i}${cellFilled ? ` — ${glyph}` : ''}`}
              onClick={() => playMove(i)}
              whileTap={canTap ? { scale: 0.92 } : undefined}
              transition={spring.snappy}
              style={{ willChange: 'transform' }}
              className={cn(
                'relative flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl',
                'border border-white/30 dark:border-white/10',
                'bg-white/60 dark:bg-zinc-800/50',
                'text-2xl font-black tabular-nums',
                canTap && 'cursor-pointer active:bg-emerald-500/10',
                isWinCell &&
                  'ring-2 ring-emerald-400/80 bg-emerald-400/15 border-emerald-400/40',
                dimmed && 'opacity-45',
                !canTap && !cellFilled && 'opacity-70',
              )}
            >
              {cellFilled ? (
                <motion.span
                  key={`${i}-${glyph}`}
                  className={cn(markColor, 'select-none', pendingCell === i && 'opacity-60')}
                  initial={reducedMotion ? false : { scale: 0.4, opacity: 0, rotate: -8 }}
                  animate={{ scale: 1, opacity: 1, rotate: 0 }}
                  transition={spring.bouncy}
                  style={{ willChange: 'transform' }}
                  aria-hidden
                >
                  {glyph}
                </motion.span>
              ) : (
                <span className="sr-only">empty</span>
              )}
            </motion.button>
          )
        })}
      </div>

      {/* status strip */}
      <div className="mt-3 min-h-[20px]">
        {finished ? (
          <p
            aria-live="polite"
            className={cn(
              'flex items-center justify-center gap-1.5 text-center text-[13px] font-bold',
              winnerName ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
            )}
          >
            {winnerName ? (
              <>
                <Trophy className="size-4" aria-hidden /> {statusText}
              </>
            ) : (
              statusText
            )}
          </p>
        ) : canJoin ? (
          <motion.button
            type="button"
            onClick={joinAsO}
            disabled={joining}
            whileTap={reducedMotion ? undefined : { scale: 0.95 }}
            transition={spring.snappy}
            style={{ willChange: 'transform' }}
            className={cn(
              'mx-auto flex min-h-[40px] w-full items-center justify-center gap-2 rounded-full',
              'bg-gradient-to-r from-emerald-400 to-emerald-600 text-[13px] font-bold text-white',
              'shadow-lg shadow-emerald-600/25 disabled:opacity-70',
            )}
          >
            {joining ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <Swords className="size-4" aria-hidden />
            )}
            Join as O
          </motion.button>
        ) : isPlayer ? (
          <StatusLine text={statusText} reducedMotion={reducedMotion} />
        ) : (
          <StatusLine text={statusText} reducedMotion={reducedMotion} />
        )}
      </div>

      {/* rematch — finished matches with me as a player */}
      {finished && isPlayer && match.playerOId !== null ? (
        <motion.button
          type="button"
          onClick={rematch}
          disabled={rematching}
          initial={reducedMotion ? false : { opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={spring.soft}
          whileTap={reducedMotion ? undefined : { scale: 0.95 }}
          style={{ willChange: 'transform' }}
          className={cn(
            'mt-2 flex min-h-[40px] w-full items-center justify-center gap-2 rounded-full',
            'border border-emerald-500/30 bg-emerald-500/10 text-[13px] font-bold',
            'text-emerald-600 dark:text-emerald-400 disabled:opacity-70',
          )}
        >
          {rematching ? (
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
          ) : (
            <RefreshCw className="size-4" aria-hidden />
          )}
          Rematch
        </motion.button>
      ) : null}
    </GlassCard>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — tournament composer sheet (Task R24-d, Twitch-style XP)
//
// Bottom sheet to open a season in the current room: a 1..40 char
// name and the game (tic-tac-toe is the only shipped season game —
// shown as a locked chip). POSTs /api/tournaments, then closes,
// toasts and fires the `pulse:external-message` CustomEvent with
// the server-serialized kind:'tournament' message so the room
// appends it instantly (room polling remains the fallback).
//
// Wiring contract for chat-room (lead), same 2-line pattern as the
// red packet —
//   const ts = useTournamentSheet(conversationId, me)
//   ... {ts.node}
// and the composer opens it via the `pulse:open-tournament`
// CustomEvent.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { useQueryClient } from '@tanstack/react-query'
import { LoaderCircle, Lock, Swords, Trophy, X } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import type { ChatMessage } from '@/lib/types'
import { cn } from '@/lib/utils'
import { spring } from '@/lib/motion'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'

/** Window CustomEvent that opens the sheet — fired by the composer/slash entry. */
export const TOURNAMENT_OPEN_EVENT = 'pulse:open-tournament'

/** Matches NAME_MIN/NAME_MAX in /api/tournaments (server enforces 1–40). */
const NAME_MIN = 1
const NAME_MAX = 40

// ── wire types (mirror of the REST contract) ─────────────────

interface TournamentCreateResponse {
  tournament: {
    id: string
    name: string
    game: string
    status: string
    createdAt: string
    endsAt: string | null
  }
  message: ChatMessage
}

export interface SheetMe {
  id: string
  name: string
  color: string
}

// ── sheet ────────────────────────────────────────────────────

export function TournamentSheet({
  open,
  onClose,
  conversationId,
  me,
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  me: SheetMe
}) {
  const [name, setName] = useState('')
  const [pending, setPending] = useState(false)
  const queryClient = useQueryClient()

  // fresh compose every time the sheet opens
  useEffect(() => {
    if (open) {
      setName('')
      setPending(false)
    }
  }, [open])

  const trimmed = name.trim()
  const valid = trimmed.length >= NAME_MIN && trimmed.length <= NAME_MAX

  const submit = useCallback(async () => {
    if (!valid || pending) return
    setPending(true)
    try {
      const res = await apiJson<TournamentCreateResponse>(
        '/api/tournaments',
        jsonBody({
          userId: me.id,
          conversationId,
          name: trimmed.slice(0, NAME_MAX),
          game: 'tictactoe',
        }),
      )
      onClose()
      toast.success('Tournament started')
      // instant local append — room polling is the fallback for stragglers
      window.dispatchEvent(
        new CustomEvent<ChatMessage>('pulse:external-message', { detail: res.message }),
      )
      // re-sync the group-info seasons list + standings caches
      void queryClient.invalidateQueries({ queryKey: ['tournaments', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['tournament', res.tournament.id] })
      void queryClient.invalidateQueries({ queryKey: ['leaderboard'] })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Could not start the tournament.')
    } finally {
      setPending(false)
    }
  }, [conversationId, me.id, onClose, pending, queryClient, trimmed, valid])

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl border-white/10 bg-zinc-950 dark:border-white/10 dark:bg-zinc-950">
        <DrawerTitle className="sr-only">Start a tournament</DrawerTitle>
        <DrawerDescription className="sr-only">
          Open a season in this chat — every won tic-tac-toe match scores one standings point
        </DrawerDescription>

        <motion.form
          initial={{ opacity: 0, y: 24, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          onSubmit={(e) => {
            e.preventDefault()
            void submit()
          }}
          className="flex flex-col gap-4 px-4 pb-6 pt-2"
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
              <p className="text-[15px] font-bold leading-tight text-zinc-50">Start tournament</p>
              <p className="text-[11px] font-medium leading-tight text-zinc-500">
                Season ladder · +1 pt per win · +25 XP to winners
              </p>
            </div>
            <button
              type="button"
              aria-label="Close tournament sheet"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
            >
              <X className="size-4.5" aria-hidden />
            </button>
          </header>

          {/* name */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="tournament-name"
              className="flex items-center justify-between text-[12px] font-semibold uppercase tracking-wide text-zinc-500"
            >
              Season name
              <span className="tabular-nums normal-case text-zinc-600">
                {trimmed.length}/{NAME_MAX}
              </span>
            </label>
            <Input
              id="tournament-name"
              value={name}
              maxLength={NAME_MAX}
              onChange={(e) => setName(e.target.value.slice(0, NAME_MAX))}
              placeholder="R24 Cup"
              aria-label="Tournament name"
              aria-invalid={(name !== '' && !valid) || undefined}
              enterKeyHint="done"
              className="h-12 rounded-2xl border-white/10 bg-white/5 text-[15px] font-bold text-zinc-50 placeholder:text-zinc-600 focus-visible:border-emerald-400/60 focus-visible:ring-emerald-400/30"
            />
            {name !== '' && !valid ? (
              <p className="text-[11.5px] font-medium text-rose-400">
                Season name needs at least {NAME_MIN} character.
              </p>
            ) : null}
          </div>

          {/* game — single shipped season game, locked chip */}
          <div className="flex flex-col gap-1.5">
            <span className="text-[12px] font-semibold uppercase tracking-wide text-zinc-500">
              Game
            </span>
            <div
              className="flex h-12 items-center gap-2.5 rounded-2xl border border-emerald-400/30 bg-emerald-500/10 px-3.5"
              role="group"
              aria-label="Season game"
            >
              <span aria-hidden className="text-xl leading-none text-emerald-300">
                <Swords className="size-5" />
              </span>
              <span className="flex-1 text-[14px] font-bold text-emerald-300">Tic-tac-toe</span>
              <span className="flex items-center gap-1 rounded-full bg-white/10 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-zinc-400">
                <Lock className="size-3" aria-hidden />
                Only game
              </span>
            </div>
          </div>

          {/* live preview */}
          <div className="rounded-2xl border border-white/10 bg-white/5 px-3.5 py-2.5">
            <p className="text-[12.5px] font-semibold text-zinc-200" aria-live="polite">
              {valid ? trimmed : 'Season name'}
            </p>
            <p className="mt-0.5 text-[11.5px] font-medium text-zinc-500">
              Tic-tac-toe · started by {me.name} · joins open to everyone in this chat
            </p>
          </div>

          {/* submit */}
          <Button
            type="submit"
            disabled={!valid || pending}
            className={cn(
              'h-12 w-full rounded-2xl border-0 bg-gradient-to-r from-emerald-500 to-emerald-600',
              'text-[15px] font-bold text-white shadow-[0_14px_32px_-12px_rgba(16,185,129,0.8)]',
              'transition-transform active:scale-[0.98] disabled:from-zinc-700 disabled:to-zinc-700 disabled:text-zinc-400 disabled:shadow-none',
            )}
            aria-label="Start tournament"
          >
            {pending ? (
              <LoaderCircle className="size-5 animate-spin" aria-hidden />
            ) : (
              <>
                <Trophy className="size-4" aria-hidden /> Start tournament
              </>
            )}
          </Button>
        </motion.form>
      </DrawerContent>
    </Drawer>
  )
}

// ── hook (2-line wiring, mirrors useRedPacketSheet) ──────────

/**
 * Mount once per room:
 *   const ts = useTournamentSheet(conversationId, me)
 *   ... {ts.node}
 * Listens for the `pulse:open-tournament` CustomEvent dispatched by
 * the composer/slash entry (lead-owned wiring).
 */
export function useTournamentSheet(conversationId: string, me: SheetMe) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(TOURNAMENT_OPEN_EVENT, handler)
    return () => window.removeEventListener(TOURNAMENT_OPEN_EVENT, handler)
  }, [])

  const node = (
    <TournamentSheet
      open={open}
      onClose={() => setOpen(false)}
      conversationId={conversationId}
      me={me}
    />
  )

  return { open, setOpen, node }
}

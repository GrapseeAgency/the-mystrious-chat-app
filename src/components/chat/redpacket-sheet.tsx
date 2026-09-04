// ─────────────────────────────────────────────────────────────
// Pulse — red packet composer sheet (Task R23-a, WeChat-style)
//
// Bottom sheet to send a red packet into the current room: amount
// (PC), grab count (1..50 stepper) and a 60-char festive note, with
// a live preview of the split. POSTs /api/redpackets, then closes,
// toasts and fires the `pulse:external-message` CustomEvent with
// the server-serialized message so the room appends it instantly
// (room polling remains the fallback).
//
// Wiring contract for chat-room (lead), same 2-line pattern as the
// whiteboard —
//   const rp = useRedPacketSheet(conversationId, me.id)
//   ... {rp.node}
// and the composer opens it via the `pulse:open-redpacket`
// CustomEvent (slash-palette entry is lead-owned).
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { LoaderCircle, Minus, Plus, Wallet, X } from 'lucide-react'
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
export const REDPACKET_OPEN_EVENT = 'pulse:open-redpacket'

// ── wire types (mirror of the REST contract) ─────────────────

interface RedPacketCreateResponse {
  message: ChatMessage
  packet: {
    id: string
    total: number
    count: number
    grabbed: number
    note: string
    expiresAt: string
  }
}

interface WalletResponse {
  wallet: { userId: string; coins: number; gems: number }
}

// ── constants (mirror the server-side validation bounds) ─────

const TOTAL_MIN = 1
const TOTAL_MAX = 10_000
const COUNT_MIN = 1
const COUNT_MAX = 50
const NOTE_MAX = 60

// ── sheet ────────────────────────────────────────────────────

export function RedPacketSheet({
  open,
  onClose,
  conversationId,
  meId,
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  meId: string
}) {
  const [amountRaw, setAmountRaw] = useState('')
  const [count, setCount] = useState(1)
  const [note, setNote] = useState('')
  const [pending, setPending] = useState(false)

  // fresh compose every time the sheet opens
  useEffect(() => {
    if (open) {
      setAmountRaw('')
      setCount(1)
      setNote('')
      setPending(false)
    }
  }, [open])

  // real balance for the affordability hint
  const { data: walletData } = useQuery({
    queryKey: ['redpacket-wallet', meId],
    queryFn: () =>
      apiJson<WalletResponse>(`/api/hub/wallet?userId=${encodeURIComponent(meId)}`),
    enabled: open && meId.length > 0,
    staleTime: 10_000,
  })
  const coins = walletData?.wallet.coins ?? null

  const total = Number.isFinite(Number(amountRaw)) ? Math.trunc(Number(amountRaw)) : NaN
  const totalValid = Number.isInteger(total) && total >= TOTAL_MIN && total <= TOTAL_MAX
  const countValid = count >= COUNT_MIN && count <= COUNT_MAX && Number.isInteger(count)
  const countFitsTotal = totalValid && count <= total
  const affordable = coins === null || (totalValid && total <= coins)
  const valid = totalValid && countValid && countFitsTotal && affordable

  const avg = totalValid && countFitsTotal ? Math.floor(total / count) : 0

  const submit = useCallback(async () => {
    if (!valid || pending) return
    setPending(true)
    try {
      const res = await apiJson<RedPacketCreateResponse>(
        '/api/redpackets',
        jsonBody({
          userId: meId,
          conversationId,
          total,
          count,
          note: note.trim().slice(0, NOTE_MAX),
        }),
      )
      onClose()
      toast.success('🧧 Red packet sent')
      // instant local append — room polling is the fallback for stragglers
      window.dispatchEvent(
        new CustomEvent<ChatMessage>('pulse:external-message', { detail: res.message }),
      )
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Could not send the red packet.')
    } finally {
      setPending(false)
    }
  }, [conversationId, count, meId, note, onClose, pending, total, valid])

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl border-white/10 bg-zinc-950 dark:border-white/10 dark:bg-zinc-950">
        <DrawerTitle className="sr-only">Send a red packet</DrawerTitle>
        <DrawerDescription className="sr-only">
          Split Pulse Coins into random grabs for this chat — the sender's wallet is debited now
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
              className="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-amber-500 to-rose-500 text-xl shadow-[0_10px_24px_-10px_rgba(244,63,94,0.6)]"
            >
              🧧
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold leading-tight text-zinc-50">Red packet</p>
              <p className="text-[11px] font-medium leading-tight text-zinc-500">
                Random split · every grab wins at least 1 PC
              </p>
            </div>
            <button
              type="button"
              aria-label="Close red packet sheet"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
            >
              <X className="size-4.5" aria-hidden />
            </button>
          </header>

          {/* amount */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="redpacket-amount"
              className="text-[12px] font-semibold uppercase tracking-wide text-zinc-500"
            >
              Total (PC)
            </label>
            <div className="relative">
              <Input
                id="redpacket-amount"
                type="number"
                inputMode="numeric"
                min={TOTAL_MIN}
                max={TOTAL_MAX}
                step={1}
                value={amountRaw}
                onChange={(e) => setAmountRaw(e.target.value)}
                placeholder="30"
                aria-label="Packet amount in PC"
                className={cn(
                  'h-12 rounded-2xl border-white/10 bg-white/5 pr-12 text-[16px] font-bold tabular-nums text-zinc-50',
                  'placeholder:text-zinc-600 focus-visible:border-amber-400/60 focus-visible:ring-amber-400/30',
                )}
              />
              <span className="pointer-events-none absolute inset-y-0 right-4 flex items-center text-[12px] font-bold text-zinc-500">
                PC
              </span>
            </div>
            {amountRaw !== '' && !totalValid ? (
              <p className="text-[11.5px] font-medium text-rose-400">
                Enter a whole number between {TOTAL_MIN} and {TOTAL_MAX} PC.
              </p>
            ) : countFitsTotal === false && totalValid ? (
              <p className="text-[11.5px] font-medium text-rose-400">
                Total must be at least {count} PC so every grab wins 1 PC.
              </p>
            ) : !affordable ? (
              <p className="text-[11.5px] font-medium text-rose-400">
                Insufficient PC — your balance is {coins}.
              </p>
            ) : null}
          </div>

          {/* count stepper */}
          <div className="flex flex-col gap-1.5">
            <span className="text-[12px] font-semibold uppercase tracking-wide text-zinc-500">
              Grabs
            </span>
            <div className="flex items-center gap-2" role="group" aria-label="Number of grabs">
              <button
                type="button"
                aria-label="Decrease grab count"
                disabled={count <= COUNT_MIN}
                onClick={() => {
                  setCount((c) => Math.max(COUNT_MIN, c - 1))
                  haptic(6)
                }}
                className="flex size-11 shrink-0 items-center justify-center rounded-2xl border border-white/10 bg-white/5 text-zinc-200 outline-none ring-emerald-400/60 transition-all hover:bg-white/10 focus-visible:ring-2 active:scale-90 disabled:opacity-40"
              >
                <Minus className="size-4" aria-hidden />
              </button>
              <Input
                aria-label="Number of grabs"
                type="number"
                inputMode="numeric"
                min={COUNT_MIN}
                max={COUNT_MAX}
                value={count}
                onChange={(e) => {
                  const next = Math.trunc(Number(e.target.value))
                  if (Number.isFinite(next)) setCount(Math.min(COUNT_MAX, Math.max(COUNT_MIN, next)))
                }}
                className="h-11 flex-1 rounded-2xl border-white/10 bg-white/5 text-center text-[15px] font-bold tabular-nums text-zinc-50 focus-visible:border-amber-400/60 focus-visible:ring-amber-400/30"
              />
              <button
                type="button"
                aria-label="Increase grab count"
                disabled={count >= COUNT_MAX}
                onClick={() => {
                  setCount((c) => Math.min(COUNT_MAX, c + 1))
                  haptic(6)
                }}
                className="flex size-11 shrink-0 items-center justify-center rounded-2xl border border-white/10 bg-white/5 text-zinc-200 outline-none ring-emerald-400/60 transition-all hover:bg-white/10 focus-visible:ring-2 active:scale-90 disabled:opacity-40"
              >
                <Plus className="size-4" aria-hidden />
              </button>
            </div>
          </div>

          {/* note */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="redpacket-note"
              className="flex items-center justify-between text-[12px] font-semibold uppercase tracking-wide text-zinc-500"
            >
              Note
              <span className="tabular-nums normal-case text-zinc-600">
                {note.length}/{NOTE_MAX}
              </span>
            </label>
            <Input
              id="redpacket-note"
              value={note}
              maxLength={NOTE_MAX}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Lucky money, on me!"
              aria-label="Packet note"
              className="h-11 rounded-2xl border-white/10 bg-white/5 text-[14px] font-medium text-zinc-50 placeholder:text-zinc-600 focus-visible:border-amber-400/60 focus-visible:ring-amber-400/30"
            />
          </div>

          {/* live preview + balance */}
          <div className="rounded-2xl border border-white/10 bg-white/5 px-3.5 py-2.5">
            <p className="text-[12.5px] font-semibold text-zinc-200" aria-live="polite">
              {totalValid ? `${total} PC` : '— PC'}
              <span className="font-medium text-zinc-500">
                {' '}
                · {count} grab{count === 1 ? '' : 's'}
                {totalValid && countFitsTotal ? ` · ≈ ${avg} PC each` : ''}
              </span>
            </p>
            {coins !== null ? (
              <p
                className={cn(
                  'mt-0.5 flex items-center gap-1.5 text-[11.5px] font-medium',
                  affordable ? 'text-zinc-500' : 'text-rose-400',
                )}
              >
                <Wallet className="size-3.5 shrink-0" aria-hidden />
                Balance {coins} PC
              </p>
            ) : null}
          </div>

          {/* submit */}
          <Button
            type="submit"
            disabled={!valid || pending}
            className={cn(
              'h-12 w-full rounded-2xl border-0 bg-gradient-to-r from-amber-500 to-rose-500',
              'text-[15px] font-bold text-white shadow-[0_14px_32px_-12px_rgba(244,63,94,0.65)]',
              'transition-transform active:scale-[0.98] disabled:from-zinc-700 disabled:to-zinc-700 disabled:text-zinc-400 disabled:shadow-none',
            )}
            aria-label="Send red packet"
          >
            {pending ? (
              <LoaderCircle className="size-5 animate-spin" aria-hidden />
            ) : (
              <>Send {totalValid ? `${total} PC` : 'red packet'}</>
            )}
          </Button>
        </motion.form>
      </DrawerContent>
    </Drawer>
  )
}

// ── hook (2-line wiring, mirrors useWhiteboardSheet) ─────────

/**
 * Mount once per room:
 *   const redpacket = useRedPacketSheet(conversationId, me.id)
 *   ... {redpacket.node}
 * Listens for the `pulse:open-redpacket` CustomEvent dispatched by
 * the composer/slash entry (lead-owned wiring).
 */
export function useRedPacketSheet(conversationId: string, meId: string) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(REDPACKET_OPEN_EVENT, handler)
    return () => window.removeEventListener(REDPACKET_OPEN_EVENT, handler)
  }, [])

  const node = (
    <RedPacketSheet
      open={open}
      onClose={() => setOpen(false)}
      conversationId={conversationId}
      meId={meId}
    />
  )

  return { open, setOpen, node }
}

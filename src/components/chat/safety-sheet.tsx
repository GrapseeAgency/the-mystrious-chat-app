// ─────────────────────────────────────────────────────────────
// Pulse — shared Signal-style safety-number sheet (R37).
// Extracted from room-info-page.tsx (R35-a) so BOTH the room info
// Encryption row and the new chat-room header ShieldCheck badge can
// open the same compare-and-verify surface. The glass sheet, the
// 3x4 digit-tile grid and the real verify/reset actions (queries +
// toasts) are unchanged — only the mount contract moved to
// open/onOpenChange so callers no longer hand-roll AnimatePresence.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeCheck, LoaderCircle, ShieldCheck } from 'lucide-react'
import type { AppUser } from '@/lib/types'
import { apiJson, formatListStamp } from '@/lib/pulse-utils'
import { ease, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { toast } from 'sonner'
import { Skeleton } from '@/components/ui/skeleton'
import { UserAvatar } from '@/components/chat/user-avatar'

/** GET/POST payload shape of /api/users/[peerId]/safety. */
export interface SafetyState {
  peerId: string
  safetyNumber: string
  verified: boolean
  verifiedAt: string | null
}

/** Shared TanStack cache key for one viewer→peer verification pair. */
export function safetyKey(peerId: string, meId: string): ['safety', string, string] {
  return ['safety', peerId, meId]
}

/**
 * "##### ##### …" → the 12 five-digit group strings for the sheet's grid.
 * Pure local split — src/lib/safety.ts is server-only (node:crypto), so the
 * client never imports it; the API already returns the formatted string.
 */
function safetyGroups(safetyNumber: string): string[] {
  const digits = safetyNumber.replace(/\D/g, '').padStart(60, '0')
  const groups: string[] = []
  for (let i = 0; i < 12; i += 1) groups.push(digits.slice(i * 5, i * 5 + 5))
  return groups
}

export interface SafetySheetProps {
  peer: AppUser
  meId: string
  /** reactive open state — the sheet mounts/unmounts with its spring */
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Bottom glass sheet: the pair's 60-digit safety number as a 3×4 grid of
 * glass tiles, the honest compare-in-person caption and the real verify /
 * reset actions over /api/users/[peerId]/safety. Backdrop tap dismisses;
 * spring entrance mirrors the reminders-sheet conventions.
 */
export function SafetySheet({ peer, meId, open, onOpenChange }: SafetySheetProps) {
  const reducedMotion = useReducedMotion() ?? false
  return (
    <AnimatePresence>
      {open ? (
        <SafetySheetInner
          key="safety-number-sheet"
          peer={peer}
          meId={meId}
          reducedMotion={reducedMotion}
          onClose={() => onOpenChange(false)}
        />
      ) : null}
    </AnimatePresence>
  )
}

function SafetySheetInner({
  peer,
  meId,
  reducedMotion,
  onClose,
}: {
  peer: AppUser
  meId: string
  reducedMotion: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()

  const safetyQuery = useQuery({
    queryKey: safetyKey(peer.id, meId),
    queryFn: () =>
      apiJson<SafetyState>(
        `/api/users/${encodeURIComponent(peer.id)}/safety?userId=${encodeURIComponent(meId)}`,
      ),
    staleTime: 10_000,
  })
  const state = safetyQuery.data
  const groups = useMemo(() => safetyGroups(state?.safetyNumber ?? ''), [state?.safetyNumber])
  const verified = state?.verified ?? false

  const verifyMutation = useMutation({
    mutationFn: () =>
      apiJson<{ verified: true; verifiedAt: string }>(
        `/api/users/${encodeURIComponent(peer.id)}/safety`,
        { method: 'POST', body: JSON.stringify({ userId: meId }) },
      ),
    onSuccess: () => {
      toast.success('Safety number verified')
      haptic(14)
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : 'Could not verify the safety number',
      ),
    // No optimistic lies — refetch the server truth on settle.
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: safetyKey(peer.id, meId) }),
  })

  const resetMutation = useMutation({
    mutationFn: () =>
      apiJson<{ verified: false }>(
        `/api/users/${encodeURIComponent(peer.id)}/safety?userId=${encodeURIComponent(meId)}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => {
      toast.success('Verification reset')
      haptic(10)
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : 'Could not reset the verification',
      ),
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: safetyKey(peer.id, meId) }),
  })

  const busy = verifyMutation.isPending || resetMutation.isPending

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
        className="absolute inset-0 z-[60] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
      />
      {/* glass bottom sheet — rounded top, spring entrance */}
      <motion.div
        role="dialog"
        aria-label={`Safety number with ${peer.name}`}
        initial={reducedMotion ? false : { y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={spring.soft}
        className="glass-deep glass-sheen absolute inset-x-0 bottom-0 z-[61] mx-auto flex max-h-[86%] w-full flex-col overflow-hidden rounded-t-3xl px-4 pt-2.5 pb-[max(1rem,env(safe-area-inset-bottom))]"
      >
        {/* grab rail */}
        <div
          aria-hidden
          className="mx-auto mb-2 h-1 w-10 shrink-0 rounded-full bg-zinc-900/15 dark:bg-white/20"
        />

        {/* peer identity */}
        <div className="flex items-center gap-3 px-1">
          <UserAvatar name={peer.name} color={peer.color} avatar={peer.avatar} size={40} />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-bold text-zinc-900 dark:text-zinc-50">
              {peer.name}
            </p>
            <p className="text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
              Encryption
            </p>
          </div>
        </div>

        {/* the 60 digits — 12 groups of 5, 3 × 4 glass tiles */}
        {safetyQuery.isPending && !state ? (
          <div
            className="mt-3 grid grid-cols-3 gap-1.5"
            role="status"
            aria-label="Loading safety number"
          >
            {Array.from({ length: 12 }).map((_, i) => (
              <Skeleton key={i} className="h-10 rounded-xl" />
            ))}
          </div>
        ) : (
          <div className="mt-3 grid grid-cols-3 gap-1.5">
            {groups.map((group, i) => (
              <motion.span
                key={i}
                initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{
                  duration: 0.18,
                  delay: Math.min(i * 0.025, 0.2),
                  ease: ease.out,
                }}
                className="flex h-10 items-center justify-center rounded-xl border border-zinc-900/[0.06] bg-white/45 font-mono text-[13px] font-semibold tabular-nums tracking-[0.14em] text-zinc-800 dark:border-white/[0.09] dark:bg-white/[0.06] dark:text-zinc-100"
              >
                {group}
              </motion.span>
            ))}
          </div>
        )}

        <p className="mt-2.5 px-1 text-center text-[11px] leading-relaxed text-zinc-500 dark:text-zinc-400">
          Compare these 60 digits with {peer.name} in person. If they match, mark this
          contact as verified.
        </p>

        {/* actions — real API calls, loading states, no optimistic lies */}
        <div className="mt-3 flex flex-col gap-2">
          {verified ? (
            <>
              <span
                className="mx-auto flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1.5 text-xs font-bold text-emerald-600 dark:text-emerald-400"
                aria-label="Contact verified"
              >
                <BadgeCheck className="size-3.5" aria-hidden />
                Verified
                {state?.verifiedAt ? (
                  <span className="font-semibold opacity-70">
                    · {formatListStamp(state.verifiedAt)}
                  </span>
                ) : null}
              </span>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  haptic(8)
                  resetMutation.mutate()
                }}
                className="h-9 w-full rounded-full bg-zinc-900/[0.05] text-xs font-bold text-zinc-500 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-[0.98] disabled:opacity-50 dark:bg-white/[0.07] dark:text-zinc-400"
              >
                {resetMutation.isPending ? (
                  <LoaderCircle className="mx-auto size-3.5 animate-spin" aria-hidden />
                ) : (
                  'Reset verification'
                )}
              </button>
            </>
          ) : (
            <button
              type="button"
              disabled={busy || safetyQuery.isPending}
              onClick={() => {
                haptic(8)
                verifyMutation.mutate()
              }}
              className="flex h-11 w-full shrink-0 items-center justify-center gap-1.5 rounded-full bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-60"
            >
              {verifyMutation.isPending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <ShieldCheck className="size-4" aria-hidden />
              )}
              Mark as verified
            </button>
          )}
        </div>
      </motion.div>
    </>
  )
}

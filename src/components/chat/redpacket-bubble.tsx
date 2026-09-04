// ─────────────────────────────────────────────────────────────
// Pulse — red packet chat bubble (Task R23-a, WeChat-style grabs)
//
// Self-fetching bubble for kind:'redpacket' messages. Tap opens
// the packet (POST grab) with a spring flip + particle burst, or
// expands the sender's grabs list (best-luck badge on the max).
// Real data only — every render reads GET /api/redpackets/[id].
//
// Wiring contract for chat-room (lead): render inside the
// kind === 'redpacket' branch —
//   <RedPacketBubble packetId={payload.packetId} meId={me.id}
//     mine={msg.senderId === me.id} senderName={msg.sender.name} />
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Crown, LoaderCircle, Users } from 'lucide-react'
import { toast } from 'sonner'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { fireParticles, spring } from '@/lib/motion'

// ── wire types (mirror of the REST contract) ─────────────────

interface RedPacketDetail {
  packet: {
    id: string
    senderId: string
    total: number
    count: number
    grabbed: number
    note: string
    expiresAt: string
    status: 'open' | 'exhausted' | 'expired'
  }
  senderName: string
  grabs: { userId: string; name: string; amount: number; createdAt: string }[]
  myGrab: number | null
  isMine: boolean
}

interface GrabResponse {
  amount: number
  grabbed: number
  count: number
}

// ── component ────────────────────────────────────────────────

export default function RedPacketBubble({
  packetId,
  meId,
  mine,
  senderName,
}: {
  packetId: string
  meId: string
  /** true when the viewer sent this packet (drives the owner detail view) */
  mine: boolean
  senderName: string
}) {
  const reducedMotion = useReducedMotion()
  const qc = useQueryClient()
  const [expanded, setExpanded] = useState(false)
  const [grabbing, setGrabbing] = useState(false)

  const queryKey = useMemo(() => ['redpacket', packetId, meId] as const, [packetId, meId])

  const { data, isLoading, isError } = useQuery({
    queryKey,
    queryFn: async () =>
      apiJson<RedPacketDetail>(
        `/api/redpackets/${encodeURIComponent(packetId)}?userId=${encodeURIComponent(meId)}`,
      ),
    enabled: packetId.length > 0,
    refetchInterval: 20_000, // keep open→exhausted→expired transitions live
    staleTime: 5_000,
  })

  const packet = data?.packet
  const myGrab = data?.myGrab ?? null
  const isMine = mine || (data?.isMine ?? false)

  const openable = packet?.status === 'open' && !isMine && myGrab === null

  /** Status line exactly per the QA contract (mine > grabbed > state). */
  const statusLine = useMemo(() => {
    if (isMine && packet) return `${packet.grabbed} of ${packet.count} grabbed — tap for details`
    if (myGrab !== null) return `+${myGrab} PC`
    if (!packet) return ''
    if (packet.status === 'open') return 'Tap to open'
    if (packet.status === 'exhausted') return 'All grabbed'
    return 'Expired'
  }, [isMine, myGrab, packet])

  const maxAmount = useMemo(
    () => (data && data.grabs.length > 0 ? Math.max(...data.grabs.map((g) => g.amount)) : 0),
    [data],
  )

  const onGrab = useCallback(async () => {
    setGrabbing(true)
    try {
      const res = await apiJson<GrabResponse>(
        `/api/redpackets/${encodeURIComponent(packetId)}/grab`,
        jsonBody({ userId: meId }),
      )
      // patch cache so the reveal is instant, then re-sync the grabs list
      qc.setQueryData<RedPacketDetail>(queryKey, (prev) =>
        prev
          ? {
              ...prev,
              packet: { ...prev.packet, grabbed: res.grabbed },
              myGrab: res.amount,
            }
          : prev,
      )
      void qc.invalidateQueries({ queryKey: ['redpacket', packetId] })
      fireParticles({ kind: 'burst', count: 90 })
      haptic(30)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Could not grab the red packet.')
      void qc.invalidateQueries({ queryKey: ['redpacket', packetId] })
    } finally {
      setGrabbing(false)
    }
  }, [meId, packetId, qc, queryKey])

  const onTap = useCallback(() => {
    if (openable) {
      void onGrab()
      return
    }
    // owners + already-grabbed viewers expand the grabs ledger
    setExpanded((v) => !v)
    haptic(8)
  }, [openable, onGrab])

  const justRevealed = myGrab !== null
  const note = packet?.note?.trim() || 'A little luck for you'

  if (isError) {
    return (
      <div
        aria-label="Red packet"
        className="glassSurface flex w-56 items-center gap-3 rounded-3xl px-4 py-3.5"
      >
        <span className="text-2xl opacity-40" aria-hidden>
          🧧
        </span>
        <p className="text-[12.5px] font-medium text-zinc-500">Red packet unavailable</p>
      </div>
    )
  }

  return (
    <section aria-label="Red packet" className="w-56 select-none sm:w-60">
      <motion.button
        type="button"
        onClick={onTap}
        disabled={grabbing || isLoading}
        aria-label={openable ? 'Grab red packet' : 'Red packet'}
        aria-live="polite"
        whileTap={reducedMotion ? undefined : { scale: 0.96 }}
        transition={spring.snappy}
        style={{ willChange: 'transform' }}
        className={cn(
          'block w-full overflow-hidden rounded-3xl text-left outline-none',
          'border border-white/25 bg-gradient-to-br from-amber-500 to-rose-500',
          'shadow-[0_16px_40px_-16px_rgba(244,63,94,0.55)]',
          'focus-visible:ring-2 focus-visible:ring-amber-200 focus-visible:ring-offset-2 focus-visible:ring-offset-zinc-950',
          'dark:border-white/15',
        )}
      >
        <div className="flex items-center gap-3 px-4 py-3.5">
          {/* envelope face ↔ revealed amount — spring flip */}
          <AnimatePresence mode="wait" initial={false}>
            {justRevealed ? (
              <motion.span
                key="revealed"
                initial={reducedMotion ? { opacity: 0 } : { opacity: 0, rotateY: 90, scale: 0.7 }}
                animate={reducedMotion ? { opacity: 1 } : { opacity: 1, rotateY: 0, scale: 1 }}
                exit={reducedMotion ? { opacity: 0 } : { opacity: 0, rotateY: -90, scale: 0.7 }}
                transition={spring.bouncy}
                style={{ willChange: 'transform' }}
                className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-white/20 text-[15px] font-extrabold text-white"
              >
                +{myGrab}
              </motion.span>
            ) : (
              <motion.span
                key="envelope"
                initial={reducedMotion ? { opacity: 0 } : { opacity: 0, rotateY: -90, scale: 0.7 }}
                animate={
                  grabbing && !reducedMotion
                    ? { opacity: 1, rotateY: 0, scale: [1, 1.12, 1] }
                    : { opacity: 1, rotateY: 0, scale: 1 }
                }
                exit={reducedMotion ? { opacity: 0 } : { opacity: 0, rotateY: 90, scale: 0.7 }}
                transition={spring.bouncy}
                style={{ willChange: 'transform' }}
                className="flex size-12 shrink-0 items-center justify-center"
                aria-hidden
              >
                {grabbing || isLoading ? (
                  <LoaderCircle className="size-6 animate-spin text-white/90" />
                ) : (
                  <span className="text-4xl drop-shadow-sm">🧧</span>
                )}
              </motion.span>
            )}
          </AnimatePresence>

          <span className="min-w-0 flex-1">
            <span className="block truncate text-[14px] font-bold leading-tight text-white">
              {note}
            </span>
            <span className="mt-0.5 block truncate text-[12px] font-semibold leading-tight text-white/90">
              {statusLine || '…'}
            </span>
            <span className="mt-0.5 block truncate text-[11px] font-medium leading-tight text-white/70">
              {isMine ? 'From you' : `From ${senderName}`}
            </span>
          </span>
        </div>
      </motion.button>

      {/* grabs ledger — owner detail view + claim history for grabbers */}
      <AnimatePresence initial={false}>
        {expanded && data ? (
          <motion.div
            key="grabs"
            initial={reducedMotion ? { opacity: 0 } : { opacity: 0, height: 0, y: -6 }}
            animate={reducedMotion ? { opacity: 1 } : { opacity: 1, height: 'auto', y: 0 }}
            exit={reducedMotion ? { opacity: 0 } : { opacity: 0, height: 0, y: -6 }}
            transition={spring.soft}
            style={{ willChange: 'transform' }}
            className="glassSurface mt-1.5 overflow-hidden rounded-2xl"
          >
            <div
              className="max-h-40 overflow-y-auto px-2.5 py-2"
              role="list"
              aria-label="Red packet grabs"
            >
              {data.grabs.length === 0 ? (
                <p className="flex items-center gap-2 px-1.5 py-2 text-[12px] font-medium text-zinc-500">
                  <Users className="size-3.5 shrink-0" aria-hidden />
                  No grabs yet
                </p>
              ) : (
                data.grabs.map((grab, i) => (
                  <motion.div
                    key={grab.userId}
                    role="listitem"
                    initial={reducedMotion ? false : { opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: Math.min(i, 8) * 0.04, ...spring.soft }}
                    className="flex items-center gap-2.5 rounded-xl px-1.5 py-1.5"
                  >
                    <span
                      aria-hidden
                      className="flex size-7 shrink-0 items-center justify-center rounded-full bg-zinc-500/20 text-[11px] font-bold text-zinc-400"
                    >
                      {grab.name.slice(0, 1).toUpperCase()}
                    </span>
                    <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-zinc-200">
                      {grab.name}
                      {grab.userId === meId ? (
                        <span className="font-medium text-zinc-500"> · you</span>
                      ) : null}
                    </span>
                    {grab.amount === maxAmount && data.grabs.length > 1 ? (
                      <span className="flex shrink-0 items-center gap-1 rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-bold text-amber-400">
                        <Crown className="size-3" aria-hidden />
                        Lucky
                      </span>
                    ) : null}
                    <span className="shrink-0 text-[12.5px] font-bold tabular-nums text-amber-400">
                      +{grab.amount}
                    </span>
                  </motion.div>
                ))
              )}
            </div>
            <p className="border-t border-white/10 px-3 py-1.5 text-[11px] font-medium text-zinc-500">
              Total {data.packet.total} PC · {data.packet.grabbed}/{data.packet.count} grabbed
            </p>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </section>
  )
}

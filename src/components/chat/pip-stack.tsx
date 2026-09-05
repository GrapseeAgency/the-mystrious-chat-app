// ─────────────────────────────────────────────────────────────
// Pulse — PiP pane stack (R28-a pane-management rework).
// Compact vertical pill stack pinned to the right edge just above
// the composer / bottom nav capsule. Every live-but-collapsed pane
// renders as a 48px glass pill (avatar + unread badge + close).
// Tapping a pill expands that pane and demotes the current one
// (focusPane demotes everyone else in the store).
// Zero emojis — Lucide icons + motion; reduced-motion respected.
// ─────────────────────────────────────────────────────────────
'use client'

import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { X } from 'lucide-react'
import { haptic } from '@/lib/pulse-settings'
import { spring } from '@/lib/motion'
import type { PipPane } from '@/components/chat/pip-store'
import { usePaneUnread } from '@/components/chat/pip-store'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { Skeleton } from '@/components/ui/skeleton'

interface PipStackProps {
  /** collapsed (minimized) panes, oldest first */
  panes: PipPane[]
  /** px above the frame bottom (clears composer + safe area) */
  bottom: number
  meId: string
  onExpand: (conversationId: string) => void
  onClose: (conversationId: string) => void
}

export function PipStack({ panes, bottom, meId, onExpand, onClose }: PipStackProps) {
  if (panes.length === 0) return null
  return (
    <div
      className="pointer-events-none absolute right-2.5 z-[1] flex flex-col items-end gap-2"
      style={{ bottom }}
    >
      <AnimatePresence initial={false}>
        {panes.map((pane) => (
          <PipStackPill
            key={pane.conversationId}
            pane={pane}
            meId={meId}
            onExpand={onExpand}
            onClose={onClose}
          />
        ))}
      </AnimatePresence>
    </div>
  )
}

function PipStackPill({
  pane,
  meId,
  onExpand,
  onClose,
}: {
  pane: PipPane
  meId: string
  onExpand: (conversationId: string) => void
  onClose: (conversationId: string) => void
}) {
  const reduced = useReducedMotion()
  const realtime = usePulseRealtime()
  const meta = pane.meta
  const unread = usePaneUnread(pane.conversationId, meId, pane.lastSeenAt)
  const label = meta?.displayName ?? 'Mini chat'
  const partnerOnline = meta?.partnerId != null ? realtime.onlineIds.has(meta.partnerId) : false

  const enter = reduced
    ? { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 } }
    : {
        initial: { opacity: 0, scale: 0.55, x: 26 },
        animate: { opacity: 1, scale: 1, x: 0 },
        exit: { opacity: 0, scale: 0.55, x: 26 },
      }

  return (
    <motion.div
      layout={reduced ? undefined : true}
      {...enter}
      transition={spring.bouncy}
      className="pointer-events-auto relative"
    >
      <motion.button
        type="button"
        aria-label={`Show ${label} mini chat`}
        onClick={() => {
          haptic(8)
          onExpand(pane.conversationId)
        }}
        whileTap={reduced ? undefined : { scale: 0.92 }}
        className="glass-pill glass-row-hover relative flex size-12 items-center justify-center rounded-full outline-none"
      >
        {meta ? (
          meta.isGroup ? (
            <GroupAvatar title={meta.displayName} id={pane.conversationId} size={36} />
          ) : (
            <UserAvatar
              name={meta.partnerName ?? meta.displayName}
              color={meta.partnerColor ?? undefined}
              avatar={meta.partnerAvatar}
              size={36}
              showPresence
              online={partnerOnline}
            />
          )
        ) : (
          <Skeleton className="size-9 rounded-full" />
        )}
        {unread > 0 ? (
          <span
            aria-label={`${unread} unread`}
            className="absolute -left-1 -top-1 grid min-w-5 place-items-center rounded-full border-2 border-white bg-emerald-500 px-1 text-[10px] font-bold leading-4 text-white shadow-sm dark:border-zinc-900"
          >
            {unread > 9 ? '9+' : unread}
          </span>
        ) : null}
      </motion.button>
      <button
        type="button"
        aria-label={`Close ${label} mini chat`}
        onClick={() => {
          haptic(6)
          onClose(pane.conversationId)
        }}
        className="absolute -right-1 -top-1 flex size-6 items-center justify-center rounded-full border border-zinc-200/80 bg-white text-zinc-500 shadow-md outline-none transition-colors hover:bg-rose-500 hover:text-white active:scale-90 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-rose-500 dark:hover:text-white"
      >
        <X className="size-3" aria-hidden />
      </button>
    </motion.div>
  )
}

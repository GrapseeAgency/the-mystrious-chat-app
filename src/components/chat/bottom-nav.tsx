// ─────────────────────────────────────────────────────────────
// Pulse Chat — liquid-glass floating dock (R22 premium overhaul).
// Detached capsule dock that floats OVER scrolling content: glass
// blur refracts it, a sliding emerald pill (layoutId) glides between
// tabs with spring physics, icons get press micro-physics + wobble,
// and unread badges pop on value change. Motion comes exclusively
// from the shared system (@/lib/motion) — no hand-rolled springs.
// ─────────────────────────────────────────────────────────────
'use client'

import { memo, useCallback } from 'react'
import { motion, useAnimationControls, useReducedMotion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { CircleUserRound, Flame, MessageCircle, Users } from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { haptic } from '@/lib/pulse-settings'
import { ease, spring, stagger } from '@/lib/motion'

export type PulseTab = 'chats' | 'hub' | 'contacts' | 'profile'

interface ConversationsResponse {
  conversations: ConversationSummary[]
}

const TABS: Array<{ id: PulseTab; label: string; Icon: typeof MessageCircle }> = [
  { id: 'chats', label: 'Chats', Icon: MessageCircle },
  { id: 'hub', label: 'Hub', Icon: Flame },
  { id: 'contacts', label: 'Contacts', Icon: Users },
  { id: 'profile', label: 'Profile', Icon: CircleUserRound },
]

/** Unread counter — pops (0.4 → 1, spring.bouncy) on every value CHANGE via key remount. */
function UnreadBadge({ count }: { count: number }) {
  const reduced = useReducedMotion()
  if (count <= 0) return null
  return (
    <motion.span
      key={count}
      initial={reduced ? false : { scale: 0.4, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={reduced ? { duration: 0 } : spring.bouncy}
      className="pointer-events-none absolute -right-2.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-gradient-to-br from-emerald-500 to-teal-500 px-1.5 text-[10px] font-bold text-white shadow-[0_4px_12px_-2px_rgba(16,185,129,0.65)] ring-2 ring-white dark:ring-zinc-900"
    >
      {count > 99 ? '99+' : count}
    </motion.span>
  )
}

/** One dock destination: press physics, wobble keyframes, active pill, staggered label. */
function DockTab({
  id,
  label,
  Icon,
  active,
  unread,
  onSelect,
}: {
  id: PulseTab
  label: string
  Icon: typeof MessageCircle
  active: boolean
  unread: number
  onSelect: (tab: PulseTab) => void
}) {
  const reduced = useReducedMotion()
  const wobble = useAnimationControls()

  const press = useCallback(() => {
    haptic(12)
    onSelect(id)
    if (reduced) return
    // release wobble — tiny rotate keyframes, 0.35s, signature swift-out ease
    void wobble.start({ rotate: [0, -8, 6, 0] }, { duration: 0.35, ease: ease.out })
  }, [id, onSelect, reduced, wobble])

  return (
    <motion.button
      type="button"
      role="tab"
      aria-selected={active}
      aria-label={label}
      onClick={press}
      whileTap={reduced ? undefined : { scale: 0.88, y: 1 }}
      transition={reduced ? { duration: 0 } : spring.bouncy}
      className="relative flex min-h-[52px] flex-1 touch-manipulation select-none flex-col items-center justify-center gap-[3px] rounded-[22px] outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
    >
      {active ? (
        <motion.span
          layoutId="nav-active-pill"
          transition={reduced ? { duration: 0 } : spring.snappy}
          className="absolute inset-0 rounded-[22px] bg-gradient-to-b from-emerald-500/20 to-emerald-500/[0.06] shadow-[0_6px_20px_-6px_rgba(16,185,129,0.55)] ring-1 ring-inset ring-emerald-500/30 dark:from-emerald-400/[0.16] dark:to-emerald-400/[0.05] dark:ring-emerald-400/25"
          style={{ willChange: 'transform' }}
        />
      ) : null}
      {/* wobble layer (rotate keyframes on press) */}
      <motion.span animate={wobble} className="relative">
        {/* active-state layer: scale 1.08 + lift, springs back bouncy */}
        <motion.span
          animate={{ scale: active ? 1.08 : 1, y: active ? -1 : 0 }}
          transition={reduced ? { duration: 0 } : spring.bouncy}
          className="relative block"
        >
          <Icon
            className={cn(
              'size-[22px] transition-colors duration-200',
              active ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
            )}
            strokeWidth={active ? 2.2 : 1.8}
            aria-hidden
          />
          {id === 'chats' ? <UnreadBadge count={unread} /> : null}
        </motion.span>
      </motion.span>
      {/* label follows the icon 20ms later (stagger recipe) */}
      <motion.span
        animate={{ y: active ? -1 : 0 }}
        transition={{ ...(reduced ? { duration: 0 } : spring.bouncy), delay: reduced ? 0 : stagger(1, 0.02) }}
        className={cn(
          'text-[10px] leading-none transition-colors duration-200',
          active ? 'font-semibold text-emerald-600 dark:text-emerald-400' : 'font-medium text-zinc-500 dark:text-zinc-400',
        )}
      >
        {label}
      </motion.span>
    </motion.button>
  )
}

export const BottomNav = memo(function BottomNav({
  me,
  active,
  onChange,
}: {
  me: AppUser
  active: PulseTab
  onChange: (tab: PulseTab) => void
}) {
  const reduced = useReducedMotion()

  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    refetchInterval: 25_000,
  })

  const totalUnread = (conversations.data ?? []).reduce((sum, c) => sum + c.unreadCount, 0)

  return (
    // detached capsule: floats over content, safe-area + 10px off the bottom edge,
    // pointer-events-none shell so content behind the glass stays interactive
    <div className="pointer-events-none absolute inset-x-3 bottom-0 z-[45] mb-[calc(env(safe-area-inset-bottom,0px)_+_10px)]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation"
        initial={reduced ? false : { y: 64, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: 64, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className="pointer-events-auto flex items-stretch gap-1 rounded-[28px] border border-zinc-200/70 bg-white/70 p-1.5 shadow-[0_8px_32px_rgba(0,0,0,0.14),inset_0_1px_0_rgba(255,255,255,0.65)] backdrop-blur-2xl backdrop-saturate-150 dark:border-white/10 dark:bg-zinc-900/65 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.08),0_8px_32px_rgba(0,0,0,0.45)]"
      >
        {TABS.map(({ id, label, Icon }) => (
          <DockTab
            key={id}
            id={id}
            label={label}
            Icon={Icon}
            active={active === id}
            unread={id === 'chats' ? totalUnread : 0}
            onSelect={onChange}
          />
        ))}
      </motion.nav>
    </div>
  )
})

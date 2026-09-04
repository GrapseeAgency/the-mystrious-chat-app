// ─────────────────────────────────────────────────────────────
// Pulse NavBar (R25) — twelve navigation architectures, one file.
// The previous 4-style router (acrylic/rail/edge/radial) is DELETED;
// these twelve are the shipped languages, default = Floating Capsule.
//
// Every variant: spring physics from @/lib/motion, layoutId pills,
// haptics, animated unread pops, Lucide icons only (zero emojis),
// `will-change` GPU isolation, reduced-motion respect.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useState } from 'react'
import { AnimatePresence, motion, useAnimationControls, useReducedMotion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import {
  CircleUserRound,
  Command,
  Flame,
  GripHorizontal,
  MessageCircle,
  Plus,
  Search,
  Settings as SettingsIcon,
  Users,
} from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { haptic } from '@/lib/pulse-settings'
import { ease, spring, stagger } from '@/lib/motion'
import { useNavStyleStore, type NavStyleId, NAV_STYLES } from '@/lib/nav-registry'

export type { NavStyleId }
export type PulseTab = 'chats' | 'hub' | 'contacts' | 'profile'

/** Compat alias — pickers import NAV_STYLE_META. */
export const NAV_STYLE_META = NAV_STYLES

const TABS: Array<{ id: PulseTab; label: string; Icon: typeof MessageCircle }> = [
  { id: 'chats', label: 'Chats', Icon: MessageCircle },
  { id: 'hub', label: 'Hub', Icon: Flame },
  { id: 'contacts', label: 'Contacts', Icon: Users },
  { id: 'profile', label: 'Profile', Icon: CircleUserRound },
]

/** React hook — current nav style + setter (zustand persist).
 *  Primitive + stable-function selectors (never allocate in the selector —
 *  a fresh array here trips useSyncExternalStore's getSnapshot cache). */
export function useNavStyle(): [NavStyleId, (s: NavStyleId) => void] {
  const style = useNavStyleStore((s) => s.style)
  const setStyle = useNavStyleStore((s) => s.setStyle)
  return [style, setStyle]
}

/** Which screen zone a style occupies — the shell uses this for layout. */
export function zoneFor(style: NavStyleId): 'bottom' | 'top' | 'side' | 'overlay' {
  return NAV_STYLES.find((s) => s.id === style)?.zone ?? 'bottom'
}

/** Shared unread counter from the real conversations API. */
function useUnread(me: AppUser): number {
  const { data } = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<{ conversations: ConversationSummary[] }>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    refetchInterval: 25_000,
  })
  return (data ?? []).reduce((sum, c) => sum + c.unreadCount, 0)
}

/** Unread counter — pops (spring) on every value CHANGE via key remount. */
function UnreadBadge({ count, className }: { count: number; className?: string }) {
  const reduced = useReducedMotion()
  if (count <= 0) return null
  return (
    <motion.span
      key={count}
      initial={reduced ? false : { scale: 0.4, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={reduced ? { duration: 0 } : spring.bouncy}
      className={cn(
        'pointer-events-none absolute -right-2.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-gradient-to-br from-emerald-500 to-teal-500 px-1.5 text-[10px] font-bold text-white shadow-[0_4px_12px_-2px_rgba(16,185,129,0.65)] ring-2 ring-white dark:ring-zinc-900',
        className,
      )}
    >
      {count > 99 ? '99+' : count}
    </motion.span>
  )
}

/** One capsule destination: press physics, wobble, active pill, staggered label. */
function CapsuleTab({
  id,
  label,
  Icon,
  active,
  unread,
  onSelect,
  layoutId,
}: {
  id: PulseTab
  label: string
  Icon: typeof MessageCircle
  active: boolean
  unread: number
  onSelect: (tab: PulseTab) => void
  layoutId: string
}) {
  const reduced = useReducedMotion()
  const wobble = useAnimationControls()
  const press = useCallback(() => {
    haptic(12)
    onSelect(id)
    if (!reduced) void wobble.start({ rotate: [0, -8, 6, 0] }, { duration: 0.35, ease: ease.out })
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
          layoutId={layoutId}
          transition={reduced ? { duration: 0 } : spring.snappy}
          className="absolute inset-0 rounded-[22px] bg-gradient-to-b from-emerald-500/20 to-emerald-500/[0.06] shadow-[0_6px_20px_-6px_rgba(16,185,129,0.55)] ring-1 ring-inset ring-emerald-500/30 dark:from-emerald-400/[0.16] dark:to-emerald-400/[0.05] dark:ring-emerald-400/25"
          style={{ willChange: 'transform' }}
        />
      ) : null}
      <motion.span animate={wobble} className="relative">
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

const GLASS_PANEL =
  'border border-zinc-200/70 bg-white/70 shadow-[0_8px_32px_rgba(0,0,0,0.14),inset_0_1px_0_rgba(255,255,255,0.65)] backdrop-blur-2xl backdrop-saturate-150 dark:border-white/10 dark:bg-zinc-900/65 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.08),0_8px_32px_rgba(0,0,0,0.45)]'

// ── 1 · capsule — Floating Capsule Navigation Bar (DEFAULT) ──

function CapsuleNav({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  return (
    <div className="pointer-events-none absolute inset-x-3 bottom-0 z-[45] mb-[calc(env(safe-area-inset-bottom,0px)_+_10px)]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (floating capsule)"
        initial={reduced ? false : { y: 64, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: 64, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto flex items-stretch gap-1 rounded-[28px] p-1.5', GLASS_PANEL)}
      >
        {TABS.map(({ id, label, Icon }) => (
          <CapsuleTab
            key={id}
            id={id}
            label={label}
            Icon={Icon}
            active={active === id}
            unread={id === 'chats' ? unread : 0}
            onSelect={onChange}
            layoutId="nav-capsule-pill"
          />
        ))}
      </motion.nav>
    </div>
  )
}

// ── 2 · floating-top — Floating Top Nav ──────────────────────

function FloatingTopNav({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  return (
    <div className="pointer-events-none absolute inset-x-3 top-2 z-[60]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (floating top)"
        initial={reduced ? false : { y: -56, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: -56, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto flex items-stretch gap-1 rounded-[26px] p-1.5', GLASS_PANEL)}
      >
        {TABS.map(({ id, label, Icon }) => (
          <CapsuleTab
            key={id}
            id={id}
            label={label}
            Icon={Icon}
            active={active === id}
            unread={id === 'chats' ? unread : 0}
            onSelect={onChange}
            layoutId="nav-top-pill"
          />
        ))}
      </motion.nav>
    </div>
  )
}

// ── 3 · floating-dock — Dock with magnifying icons ───────────

function FloatingDock({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-[45] mb-[calc(env(safe-area-inset-bottom,0px)_+_12px)] flex justify-center">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (floating dock)"
        initial={reduced ? false : { y: 64, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: 64, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto flex items-end gap-1 rounded-[24px] px-2.5 py-2', GLASS_PANEL)}
      >
        {TABS.map(({ id, label, Icon }, i) => (
          <div key={id} className="flex items-end gap-1">
            {i > 0 ? <span aria-hidden className="mb-3 size-1 rounded-full bg-zinc-300 dark:bg-zinc-700" /> : null}
            <motion.button
              type="button"
              role="tab"
              aria-selected={active === id}
              aria-label={label}
              onClick={() => {
                haptic(12)
                onChange(id)
              }}
              whileHover={reduced ? undefined : { scale: 1.22, y: -6 }}
              whileTap={reduced ? undefined : { scale: 0.9 }}
              transition={reduced ? { duration: 0 } : spring.bouncy}
              className="relative flex size-12 touch-manipulation items-center justify-center rounded-2xl outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
            >
              {active === id ? (
                <motion.span
                  layoutId="nav-dock-pill"
                  transition={reduced ? { duration: 0 } : spring.snappy}
                  className="absolute inset-0 rounded-2xl bg-gradient-to-b from-emerald-500/25 to-emerald-500/[0.08] ring-1 ring-inset ring-emerald-500/40"
                />
              ) : null}
              <span className="relative">
                <Icon
                  className={cn(
                    'size-6 transition-colors',
                    active === id ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                  )}
                  strokeWidth={active ? 2.2 : 1.8}
                  aria-hidden
                />
                {id === 'chats' ? <UnreadBadge count={unread} /> : null}
              </span>
            </motion.button>
          </div>
        ))}
      </motion.nav>
    </div>
  )
}

// ── 4 · pill — segmented pill with sliding fill ──────────────

function PillNav({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  return (
    <div className="pointer-events-none absolute inset-x-6 bottom-0 z-[45] mb-[calc(env(safe-area-inset-bottom,0px)_+_12px)]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (pill)"
        initial={reduced ? false : { y: 56, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: 56, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto relative flex rounded-full p-1', GLASS_PANEL)}
      >
        {TABS.map(({ id, label, Icon }) => {
          const isActive = active === id
          return (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={isActive}
              aria-label={label}
              onClick={() => {
                haptic(10)
                onChange(id)
              }}
              className={cn(
                'relative flex min-h-[44px] flex-1 touch-manipulation items-center justify-center gap-1.5 rounded-full px-2 text-[11px] font-semibold outline-none transition-colors focus-visible:ring-2 focus-visible:ring-emerald-500/60',
                isActive ? 'text-white' : 'text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-200',
              )}
            >
              {isActive ? (
                <motion.span
                  layoutId="nav-pill-fill"
                  transition={reduced ? { duration: 0 } : spring.snappy}
                  className="absolute inset-0 rounded-full bg-gradient-to-r from-emerald-600 to-teal-600 shadow-[0_6px_18px_-4px_rgba(16,185,129,0.7)]"
                  style={{ willChange: 'transform' }}
                />
              ) : null}
              <span className="relative">
                <Icon className="size-[17px]" strokeWidth={isActive ? 2.3 : 1.9} aria-hidden />
                {id === 'chats' ? <UnreadBadge count={unread} className="-right-2 -top-2 ring-transparent" /> : null}
              </span>
              <span className="relative hidden xs:inline sm:inline">{label}</span>
            </button>
          )
        })}
      </motion.nav>
    </div>
  )
}

// ── 5 · bottom-bar — classic edge-to-edge bar ────────────────

function BottomBar({ active, onChange, unread }: TabProps) {
  return (
    <nav
      role="tablist"
      aria-label="Main navigation (bottom bar)"
      className="absolute inset-x-0 bottom-0 z-[45] flex items-stretch border-t border-zinc-200/80 bg-white/85 pb-[env(safe-area-inset-bottom,0px)] backdrop-blur-xl dark:border-white/10 dark:bg-zinc-950/85"
    >
      {TABS.map(({ id, label, Icon }) => {
        const isActive = active === id
        return (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={isActive}
            aria-label={label}
            onClick={() => {
              haptic(10)
              onChange(id)
            }}
            className={cn(
              'relative flex min-h-[56px] flex-1 touch-manipulation flex-col items-center justify-center gap-1 pt-1.5 text-[10px] font-semibold outline-none transition-colors',
              isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
            )}
          >
            <span className="relative">
              <Icon className="size-[22px]" strokeWidth={isActive ? 2.2 : 1.8} aria-hidden />
              {id === 'chats' ? <UnreadBadge count={unread} /> : null}
            </span>
            {label}
            {isActive ? (
              <motion.span
                layoutId="nav-bottombar-dot"
                transition={spring.snappy}
                className="absolute top-0 h-[3px] w-8 rounded-b-full bg-emerald-500"
                style={{ willChange: 'transform' }}
              />
            ) : null}
          </button>
        )
      })}
    </nav>
  )
}

// ── 6 · tab-bar — iOS-style tinted squircles ─────────────────

function TabBarNav({ active, onChange, unread }: TabProps) {
  return (
    <nav
      role="tablist"
      aria-label="Main navigation (tab bar)"
      className="absolute inset-x-0 bottom-0 z-[45] flex items-stretch gap-1 border-t border-zinc-200/70 bg-white/80 px-2 pb-[env(safe-area-inset-bottom,0px)] pt-1.5 backdrop-blur-xl dark:border-white/10 dark:bg-zinc-950/80"
    >
      {TABS.map(({ id, label, Icon }) => {
        const isActive = active === id
        return (
          <motion.button
            key={id}
            type="button"
            role="tab"
            aria-selected={isActive}
            aria-label={label}
            whileTap={{ scale: 0.92 }}
            transition={spring.bouncy}
            onClick={() => {
              haptic(10)
              onChange(id)
            }}
            className="relative flex min-h-[52px] flex-1 touch-manipulation flex-col items-center justify-center gap-1 text-[10px] font-medium outline-none"
          >
            {isActive ? (
              <motion.span
                layoutId="nav-tabbar-squircle"
                transition={spring.snappy}
                className="absolute inset-x-3 inset-y-1 rounded-2xl bg-emerald-500/15 ring-1 ring-inset ring-emerald-500/25 dark:bg-emerald-400/10"
                style={{ willChange: 'transform' }}
              />
            ) : null}
            <span className="relative">
              <Icon
                className={cn(
                  'size-[21px] transition-colors',
                  isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                )}
                strokeWidth={isActive ? 2.2 : 1.8}
                aria-hidden
              />
              {id === 'chats' ? <UnreadBadge count={unread} /> : null}
            </span>
            <span className={cn('relative', isActive && 'font-semibold text-emerald-600 dark:text-emerald-400')}>
              {label}
            </span>
          </motion.button>
        )
      })}
    </nav>
  )
}

// ── 7 · floating-tab-bar — detached elevated card ────────────

function FloatingTabBar({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  return (
    <div className="pointer-events-none absolute inset-x-4 bottom-0 z-[45] mb-[calc(env(safe-area-inset-bottom,0px)_+_12px)]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (floating tab bar)"
        initial={reduced ? false : { y: 64, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: 64, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto flex items-stretch gap-1.5 rounded-[26px] p-2', GLASS_PANEL)}
      >
        {TABS.map(({ id, label, Icon }) => {
          const isActive = active === id
          return (
            <motion.button
              key={id}
              type="button"
              role="tab"
              aria-selected={isActive}
              aria-label={label}
              animate={{ y: isActive ? -4 : 0, scale: isActive ? 1.02 : 1 }}
              transition={reduced ? { duration: 0 } : spring.bouncy}
              onClick={() => {
                haptic(12)
                onChange(id)
              }}
              className="relative flex min-h-[54px] flex-1 touch-manipulation flex-col items-center justify-center gap-1 rounded-[20px] text-[10px] font-semibold outline-none"
            >
              {isActive ? (
                <motion.span
                  layoutId="nav-ftab-card"
                  transition={reduced ? { duration: 0 } : spring.snappy}
                  className="absolute inset-0 rounded-[20px] bg-gradient-to-b from-white to-zinc-50 shadow-[0_10px_24px_-8px_rgba(16,185,129,0.45),0_2px_6px_rgba(0,0,0,0.08)] ring-1 ring-emerald-500/30 dark:from-zinc-800 dark:to-zinc-900"
                  style={{ willChange: 'transform' }}
                />
              ) : null}
              <span className="relative">
                <Icon
                  className={cn(
                    'size-[21px] transition-colors',
                    isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                  )}
                  strokeWidth={isActive ? 2.2 : 1.8}
                  aria-hidden
                />
                {id === 'chats' ? <UnreadBadge count={unread} /> : null}
              </span>
              <span
                className={cn(
                  'relative transition-colors',
                  isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                )}
              >
                {label}
              </span>
            </motion.button>
          )
        })}
      </motion.nav>
    </div>
  )
}

// ── 8 · command-bar — top text command strip ─────────────────

function CommandBarNav({
  active,
  onChange,
  unread,
  onSearch,
  onSettings,
}: TabProps & { onSearch: () => void; onSettings: () => void }) {
  const reduced = useReducedMotion()
  return (
    <div className="pointer-events-none absolute inset-x-3 top-2 z-[60]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (command bar)"
        initial={reduced ? false : { y: -56, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={reduced ? undefined : { y: -56, opacity: 0 }}
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto flex items-center gap-0.5 rounded-full py-1 pl-2 pr-1', GLASS_PANEL)}
      >
        <motion.button
          type="button"
          aria-label="Search"
          onClick={() => {
            haptic(8)
            onSearch()
          }}
          whileTap={reduced ? undefined : { scale: 0.9 }}
          className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
        >
          <Search className="size-4" aria-hidden />
          <Command className="-ml-2 mt-3 size-2.5 text-zinc-400" aria-hidden />
        </motion.button>
        <span aria-hidden className="h-5 w-px shrink-0 bg-zinc-200 dark:bg-zinc-700" />
        <div className="relative flex flex-1 items-center justify-around">
          {TABS.map(({ id, label }) => {
            const isActive = active === id
            return (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={isActive}
                onClick={() => {
                  haptic(10)
                  onChange(id)
                }}
                className={cn(
                  'relative min-h-[40px] touch-manipulation rounded-full px-3 text-[12px] font-semibold outline-none transition-colors',
                  isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                )}
              >
                {isActive ? (
                  <motion.span
                    layoutId="nav-cmd-underline"
                    transition={reduced ? { duration: 0 } : spring.snappy}
                    className="absolute inset-x-2 -bottom-0.5 h-[2.5px] rounded-full bg-emerald-500"
                    style={{ willChange: 'transform' }}
                  />
                ) : null}
                {label}
              </button>
            )
          })}
        </div>
        <span aria-hidden className="h-5 w-px shrink-0 bg-zinc-200 dark:bg-zinc-700" />
        <motion.button
          type="button"
          aria-label="Settings"
          onClick={() => {
            haptic(8)
            onSettings()
          }}
          whileTap={reduced ? undefined : { scale: 0.9 }}
          className="relative flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
        >
          <SettingsIcon className="size-4" aria-hidden />
          {unread > 0 ? <span aria-hidden className="absolute right-1.5 top-1.5 size-1.5 rounded-full bg-emerald-500" /> : null}
        </motion.button>
      </motion.nav>
    </div>
  )
}

// ── 9 · rail — persistent vertical side rail ─────────────────

function RailNav({ active, onChange, unread }: TabProps) {
  return (
    <nav
      role="tablist"
      aria-label="Main navigation (rail)"
      className="z-[45] flex w-[68px] shrink-0 flex-col items-center gap-1 border-r border-zinc-200/70 bg-white/60 py-3 backdrop-blur-xl dark:border-white/10 dark:bg-zinc-950/60"
    >
      <div className="mb-3 flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400 to-teal-600 text-sm font-black text-white shadow-[0_6px_16px_-4px_rgba(16,185,129,0.7)]">
        P
      </div>
      {TABS.map(({ id, label, Icon }) => {
        const isActive = active === id
        return (
          <motion.button
            key={id}
            type="button"
            role="tab"
            aria-selected={isActive}
            aria-label={label}
            whileTap={{ scale: 0.92 }}
            transition={spring.bouncy}
            onClick={() => {
              haptic(10)
              onChange(id)
            }}
            className={cn(
              'relative flex w-14 flex-col items-center gap-1 rounded-2xl py-2.5 text-[9px] font-semibold outline-none transition-colors',
              isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-200',
            )}
          >
            {isActive ? (
              <motion.span
                layoutId="nav-rail-bar"
                transition={spring.snappy}
                className="absolute -left-[13px] top-1/2 h-7 w-1 -translate-y-1/2 rounded-r-full bg-emerald-500"
                style={{ willChange: 'transform' }}
              />
            ) : null}
            <span className="relative">
              <Icon className="size-5" strokeWidth={isActive ? 2.2 : 1.8} aria-hidden />
              {id === 'chats' ? <UnreadBadge count={unread} /> : null}
            </span>
            {label}
          </motion.button>
        )
      })}
    </nav>
  )
}

// ── 10 · island — dynamic-island expanding pill ──────────────

function IslandNav({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  const [expanded, setExpanded] = useState(false)
  const activeTab = TABS.find((t) => t.id === active) ?? TABS[0]
  useEffect(() => {
    if (!expanded) return
    const t = window.setTimeout(() => setExpanded(false), 4200)
    return () => window.clearTimeout(t)
  }, [expanded])
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-[46] mb-[calc(env(safe-area-inset-bottom,0px)_+_12px)] flex justify-center">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (island)"
        layout
        onClick={() => {
          haptic(10)
          setExpanded((v) => !v)
        }}
        animate={{ width: expanded ? 'min(92%, 380px)' : 148 }}
        transition={reduced ? { duration: 0 } : spring.snappy}
        style={{ willChange: 'width' }}
        className={cn(
          'pointer-events-auto flex min-h-[54px] cursor-pointer items-center justify-center overflow-hidden rounded-full p-1.5',
          GLASS_PANEL,
        )}
      >
        <AnimatePresence mode="popLayout" initial={false}>
          {expanded ? (
            <motion.div
              key="island-open"
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              transition={reduced ? { duration: 0 } : spring.bouncy}
              className="flex w-full items-stretch"
            >
              {TABS.map(({ id, label, Icon }) => (
                <button
                  key={id}
                  type="button"
                  role="tab"
                  aria-selected={active === id}
                  aria-label={label}
                  onClick={(e) => {
                    e.stopPropagation()
                    haptic(12)
                    onChange(id)
                    setExpanded(false)
                  }}
                  className="relative flex min-h-[46px] flex-1 touch-manipulation flex-col items-center justify-center gap-0.5 rounded-3xl text-[9px] font-semibold outline-none"
                >
                  {active === id ? (
                    <motion.span
                      layoutId="nav-island-pill"
                      transition={spring.snappy}
                      className="absolute inset-1 rounded-3xl bg-emerald-500/18 ring-1 ring-inset ring-emerald-500/30"
                    />
                  ) : null}
                  <span className="relative">
                    <Icon
                      className={cn(
                        'size-5',
                        active === id ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                      )}
                      aria-hidden
                    />
                    {id === 'chats' ? <UnreadBadge count={unread} /> : null}
                  </span>
                  <span className="relative text-zinc-600 dark:text-zinc-300">{label}</span>
                </button>
              ))}
            </motion.div>
          ) : (
            <motion.div
              key="island-closed"
              initial={{ opacity: 0, scale: 0.85 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.85 }}
              transition={reduced ? { duration: 0 } : spring.bouncy}
              className="flex items-center gap-2 px-3"
            >
              <span className="relative">
                <activeTab.Icon className="size-[22px] text-emerald-600 dark:text-emerald-400" aria-hidden />
                {active === 'chats' ? <UnreadBadge count={unread} /> : null}
              </span>
              <span className="text-[12px] font-semibold text-zinc-700 dark:text-zinc-200">{activeTab.label}</span>
              <GripHorizontal className="size-4 text-zinc-400" aria-hidden />
            </motion.div>
          )}
        </AnimatePresence>
      </motion.nav>
    </div>
  )
}

// ── 11 · radial — FAB fanning an arc ─────────────────────────

function RadialNav({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  const [open, setOpen] = useState(false)
  const angles = [-124, -90, -56, -158]
  const radius = 96
  return (
    <>
      <AnimatePresence>
        {open ? (
          <motion.div
            key="radial-veil"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="absolute inset-0 z-[84] bg-zinc-950/35 backdrop-blur-[18px]"
            onClick={() => setOpen(false)}
          >
            {TABS.map(({ id, label, Icon }, i) => {
              const rad = (angles[i] * Math.PI) / 180
              return (
                <motion.button
                  key={id}
                  type="button"
                  aria-label={label}
                  initial={{ opacity: 0, x: 0, y: 0, scale: 0.4 }}
                  animate={{ opacity: 1, x: Math.cos(rad) * radius, y: Math.sin(rad) * radius, scale: 1 }}
                  exit={{ opacity: 0, x: 0, y: 0, scale: 0.4 }}
                  transition={reduced ? { duration: 0 } : { ...spring.bouncy, delay: i * 0.03 }}
                  onClick={(e) => {
                    e.stopPropagation()
                    haptic(12)
                    onChange(id)
                    setOpen(false)
                  }}
                  className="absolute bottom-6 left-1/2 flex size-[68px] -translate-x-1/2 flex-col items-center justify-center gap-0.5 rounded-full border border-white/40 bg-white/85 text-[9px] font-bold text-zinc-800 shadow-2xl backdrop-blur-xl dark:border-white/10 dark:bg-zinc-900/90 dark:text-white"
                >
                  <span className="relative">
                    <Icon className={cn('size-5', active === id && 'text-emerald-500')} aria-hidden />
                    {id === 'chats' ? <UnreadBadge count={unread} /> : null}
                  </span>
                  {label}
                </motion.button>
              )
            })}
          </motion.div>
        ) : null}
      </AnimatePresence>
      <div className="pointer-events-none absolute inset-x-0 bottom-0 z-[83] mb-[calc(env(safe-area-inset-bottom,0px)_+_14px)] flex justify-center">
        <motion.button
          type="button"
          aria-label={open ? 'Close radial navigation' : 'Open radial navigation'}
          onClick={() => {
            haptic(14)
            setOpen((v) => !v)
          }}
          whileTap={{ scale: 0.92 }}
          animate={{ rotate: open ? 45 : 0 }}
          transition={spring.snappy}
          className="pointer-events-auto flex size-14 items-center justify-center rounded-full bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-[0_10px_36px_-6px_rgba(16,185,129,0.65)]"
          style={{ willChange: 'transform' }}
        >
          <Plus className="size-6" aria-hidden />
        </motion.button>
      </div>
    </>
  )
}

// ── 12 · gesture — swipe handle + quick switcher ─────────────

function GestureNav({ active, onChange, unread }: TabProps) {
  const reduced = useReducedMotion()
  const [open, setOpen] = useState(false)
  const dragY = useAnimationControls()
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-[46] mb-[calc(env(safe-area-inset-bottom,0px)_+_8px)] flex flex-col items-center">
      <AnimatePresence>
        {open ? (
          <motion.div
            key="gesture-switcher"
            initial={{ opacity: 0, y: 24, scale: 0.92 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 24, scale: 0.92 }}
            transition={reduced ? { duration: 0 } : spring.bouncy}
            className={cn('pointer-events-auto mb-2.5 flex items-center gap-1 rounded-[24px] p-1.5', GLASS_PANEL)}
          >
            {TABS.map(({ id, label, Icon }) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={active === id}
                aria-label={label}
                onClick={() => {
                  haptic(12)
                  onChange(id)
                  setOpen(false)
                }}
                className="relative flex size-14 touch-manipulation flex-col items-center justify-center gap-0.5 rounded-2xl text-[9px] font-semibold outline-none"
              >
                {active === id ? (
                  <motion.span
                    layoutId="nav-gesture-pill"
                    transition={spring.snappy}
                    className="absolute inset-0 rounded-2xl bg-emerald-500/18 ring-1 ring-inset ring-emerald-500/30"
                  />
                ) : null}
                <span className="relative">
                  <Icon
                    className={cn(
                      'size-5',
                      active === id ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                    )}
                    aria-hidden
                  />
                  {id === 'chats' ? <UnreadBadge count={unread} /> : null}
                </span>
                <span className="relative text-zinc-600 dark:text-zinc-300">{label}</span>
              </button>
            ))}
          </motion.div>
        ) : null}
      </AnimatePresence>
      <motion.button
        type="button"
        aria-label={open ? 'Collapse quick switcher' : 'Open quick switcher'}
        animate={dragY}
        onClick={() => {
          haptic(10)
          setOpen((v) => !v)
        }}
        className="pointer-events-auto flex h-9 w-40 touch-manipulation items-end justify-center rounded-full pb-1.5 outline-none"
      >
        <span
          className={cn(
            'h-[5px] rounded-full transition-all duration-300',
            open ? 'w-16 bg-emerald-500' : 'w-24 bg-zinc-400 dark:bg-zinc-600',
          )}
        />
      </motion.button>
    </div>
  )
}

// ── 13 · contextual-dock — adapts to the active tab ──────────

const CONTEXT_ACTION: Record<PulseTab, { label: string; Icon: typeof Plus }> = {
  chats: { label: 'New chat', Icon: Plus },
  hub: { label: 'Search', Icon: Search },
  contacts: { label: 'New group', Icon: Users },
  profile: { label: 'Settings', Icon: SettingsIcon },
}

function ContextualDock({
  active,
  onChange,
  unread,
  onContextAction,
}: TabProps & { onContextAction: (action: 'new-chat' | 'search' | 'new-group' | 'settings') => void }) {
  const reduced = useReducedMotion()
  const ctx = CONTEXT_ACTION[active]
  return (
    <div className="pointer-events-none absolute inset-x-3 bottom-0 z-[45] mb-[calc(env(safe-area-inset-bottom,0px)_+_10px)]">
      <motion.nav
        role="tablist"
        aria-label="Main navigation (contextual dock)"
        layout
        transition={reduced ? { duration: 0 } : spring.soft}
        className={cn('pointer-events-auto flex items-center gap-1 rounded-[26px] p-1.5', GLASS_PANEL)}
      >
        {TABS.map(({ id, label, Icon }) => (
          <CapsuleTab
            key={id}
            id={id}
            label={label}
            Icon={Icon}
            active={active === id}
            unread={id === 'chats' ? unread : 0}
            onSelect={onChange}
            layoutId="nav-ctx-pill"
          />
        ))}
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.button
            key={active}
            type="button"
            aria-label={ctx.label}
            initial={{ opacity: 0, x: 12, scale: 0.8 }}
            animate={{ opacity: 1, x: 0, scale: 1 }}
            exit={{ opacity: 0, x: 12, scale: 0.8 }}
            transition={reduced ? { duration: 0 } : spring.bouncy}
            onClick={() => {
              haptic(14)
              onContextAction(
                active === 'chats' ? 'new-chat' : active === 'hub' ? 'search' : active === 'contacts' ? 'new-group' : 'settings',
              )
            }}
            className="ml-0.5 flex h-[52px] shrink-0 items-center gap-1.5 rounded-[20px] bg-gradient-to-br from-emerald-500 to-teal-600 px-3 text-[11px] font-bold text-white shadow-[0_8px_22px_-6px_rgba(16,185,129,0.7)]"
          >
            <ctx.Icon className="size-4" aria-hidden />
            <span className="hidden sm:inline">{ctx.label}</span>
          </motion.button>
        </AnimatePresence>
      </motion.nav>
    </div>
  )
}

// ── Router ───────────────────────────────────────────────────

interface TabProps {
  active: PulseTab
  onChange: (tab: PulseTab) => void
  unread: number
}

export type NavContextAction = 'new-chat' | 'search' | 'new-group' | 'settings'

/**
 * One-call navigation: reads the persisted style and renders the matching
 * architecture. `onContextAction` feeds the contextual dock's per-tab chip;
 * `onSearch`/`onSettings` feed the command bar's inline triggers.
 */
export function PulseNavBar({
  me,
  active,
  onChange,
  onSearch,
  onSettings,
  onContextAction,
}: {
  me: AppUser
  active: PulseTab
  onChange: (tab: PulseTab) => void
  onSearch: () => void
  onSettings: () => void
  onContextAction: (action: NavContextAction) => void
}) {
  const [style] = useNavStyle()
  const unread = useUnread(me)
  switch (style) {
    case 'floating-top':
      return <FloatingTopNav active={active} onChange={onChange} unread={unread} />
    case 'floating-dock':
      return <FloatingDock active={active} onChange={onChange} unread={unread} />
    case 'pill':
      return <PillNav active={active} onChange={onChange} unread={unread} />
    case 'bottom-bar':
      return <BottomBar active={active} onChange={onChange} unread={unread} />
    case 'tab-bar':
      return <TabBarNav active={active} onChange={onChange} unread={unread} />
    case 'floating-tab-bar':
      return <FloatingTabBar active={active} onChange={onChange} unread={unread} />
    case 'command-bar':
      return <CommandBarNav active={active} onChange={onChange} unread={unread} onSearch={onSearch} onSettings={onSettings} />
    case 'rail':
      return <RailNav active={active} onChange={onChange} unread={unread} />
    case 'island':
      return <IslandNav active={active} onChange={onChange} unread={unread} />
    case 'radial':
      return <RadialNav active={active} onChange={onChange} unread={unread} />
    case 'gesture':
      return <GestureNav active={active} onChange={onChange} unread={unread} />
    case 'contextual-dock':
      return <ContextualDock active={active} onChange={onChange} unread={unread} onContextAction={onContextAction} />
    case 'capsule':
    default:
      return <CapsuleNav active={active} onChange={onChange} unread={unread} />
  }
}

/** Back-compat alias for older import sites. */
export const PulseNav = PulseNavBar

// ─────────────────────────────────────────────────────────────
// Pulse NavRouter — 4 swappable mobile navigation architectures
// (per the master blueprint's matrix column):
//   acrylic · Floating Acrylic Bottom Dock   (default)
//   rail    · Persistent Solid Split Rail
//   edge    · Minimalist Hidden Edge Rail
//   radial  · Volumetric Radial Overlay (blur-20px + 16px float dock)
// Style persists in localStorage `pulse.navStyle`; the Profile tab's
// Navigation panel switches it live via `pulse.navStyle.changed`.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { CircleUserRound, Flame, LayoutGrid, MessageCircle, Users } from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import type { NavStyleId } from '@/lib/hub-catalog'

export type { NavStyleId }

export type PulseTab = 'chats' | 'hub' | 'contacts' | 'profile'

const TABS: Array<{ id: PulseTab; label: string; Icon: typeof MessageCircle }> = [
  { id: 'chats', label: 'Chats', Icon: MessageCircle },
  { id: 'hub', label: 'Hub', Icon: Flame },
  { id: 'contacts', label: 'Contacts', Icon: Users },
  { id: 'profile', label: 'Profile', Icon: CircleUserRound },
]

const STORAGE_KEY = 'pulse.navStyle'
const EVENT = 'pulse.navStyle.changed'

export const NAV_STYLE_META: Array<{ id: NavStyleId; label: string; hint: string }> = [
  { id: 'acrylic', label: 'Acrylic Dock', hint: 'Floating acrylic bottom dock (default)' },
  { id: 'rail', label: 'Solid Rail', hint: 'Persistent solid split rail' },
  { id: 'edge', label: 'Edge Rail', hint: 'Minimalist hidden edge rail' },
  { id: 'radial', label: 'Radial', hint: 'Volumetric radial overlay' },
]

/** Read the persisted style (SSR-safe, defaults to acrylic). */
function readStyle(): NavStyleId {
  if (typeof window === 'undefined') return 'acrylic'
  const stored = window.localStorage.getItem(STORAGE_KEY)
  return stored === 'rail' || stored === 'edge' || stored === 'radial' ? stored : 'acrylic'
}

/** External store: localStorage + custom event (+ cross-tab storage event). */
function subscribeNavStyle(onChange: () => void): () => void {
  window.addEventListener(EVENT, onChange)
  window.addEventListener('storage', onChange)
  return () => {
    window.removeEventListener(EVENT, onChange)
    window.removeEventListener('storage', onChange)
  }
}

function useNavStyle(): [NavStyleId, (s: NavStyleId) => void] {
  const style = useSyncExternalStore(subscribeNavStyle, readStyle, () => 'acrylic' as const)
  const apply = useCallback((s: NavStyleId) => {
    window.localStorage.setItem(STORAGE_KEY, s)
    window.dispatchEvent(new CustomEvent<NavStyleId>(EVENT, { detail: s }))
  }, [])
  return [style, apply]
}

export { useNavStyle }

/** Shared unread badge (sourced from the real conversations API). */
function useUnread(me: AppUser): number {
  const [unread, setUnread] = useState(0)
  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const res = await apiJson<{ conversations: ConversationSummary[] }>(
          `/api/conversations?userId=${encodeURIComponent(me.id)}`,
        )
        if (alive) setUnread(res.conversations.reduce((s, c) => s + c.unreadCount, 0))
      } catch {
        // offline / refetch hiccup — keep last value
      }
    }
    void load()
    const t = window.setInterval(load, 25_000)
    return () => {
      alive = false
      window.clearInterval(t)
    }
  }, [me.id])
  return unread
}

function UnreadBubble({ count }: { count: number }) {
  if (count <= 0) return null
  return (
    <span className="absolute -top-1 -right-2 flex h-4 min-w-4 items-center justify-center rounded-full bg-emerald-500 px-1 text-[9px] font-bold text-white">
      {count > 99 ? '99+' : count}
    </span>
  )
}

// ── Style 1 · acrylic — Floating Acrylic Bottom Dock ─────────

function AcrylicDock({ active, onChange, unread }: { active: PulseTab; onChange: (t: PulseTab) => void; unread: number }) {
  return (
    <div className="pointer-events-none absolute inset-x-3 bottom-3 z-[80]">
      <nav
        aria-label="Main navigation (acrylic dock)"
        className="pointer-events-auto grid grid-cols-4 gap-1 rounded-3xl border border-white/40 bg-white/60 p-1.5 shadow-[0_12px_40px_-8px_rgba(0,0,0,0.35)] backdrop-blur-xl dark:border-white/10 dark:bg-zinc-900/60"
      >
        {TABS.map(({ id, label, Icon }) => {
          const isActive = active === id
          return (
            <button
              key={id}
              type="button"
              onClick={() => onChange(id)}
              aria-current={isActive ? 'page' : undefined}
              className={cn(
                'relative flex touch-manipulation flex-col items-center gap-0.5 rounded-2xl py-2 text-[10px] font-semibold transition-colors active:scale-[0.96]',
                isActive ? 'text-white' : 'text-zinc-600 dark:text-zinc-300',
              )}
            >
              {isActive ? (
                <motion.span
                  layoutId="pulse-nav-pill"
                  transition={{ type: 'spring', stiffness: 480, damping: 34 }}
                  className="absolute inset-0 rounded-2xl bg-emerald-600 shadow-md"
                />
              ) : null}
              <span className="relative">
                <Icon className="size-5" strokeWidth={isActive ? 2.2 : 1.9} aria-hidden />
                {id === 'chats' ? <UnreadBubble count={unread} /> : null}
              </span>
              <span className="relative">{label}</span>
            </button>
          )
        })}
      </nav>
    </div>
  )
}

// ── Style 2 · rail — Persistent Solid Split Rail ─────────────

function SolidRail({ active, onChange, unread }: { active: PulseTab; onChange: (t: PulseTab) => void; unread: number }) {
  return (
    <nav
      aria-label="Main navigation (solid rail)"
      className="z-[80] flex w-16 shrink-0 flex-col items-center gap-1.5 border-r border-zinc-800 bg-zinc-950 py-3 dark:border-zinc-800"
    >
      <div className="mb-2 flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400 to-teal-600 text-sm font-black text-white shadow">
        P
      </div>
      {TABS.map(({ id, label, Icon }) => {
        const isActive = active === id
        return (
          <button
            key={id}
            type="button"
            onClick={() => onChange(id)}
            aria-current={isActive ? 'page' : undefined}
            className={cn(
              'relative flex w-14 flex-col items-center gap-0.5 rounded-xl py-2 text-[9px] font-semibold transition-colors active:scale-[0.96]',
              isActive ? 'bg-emerald-600/15 text-emerald-400' : 'text-zinc-500 hover:text-zinc-300',
            )}
          >
            {isActive ? (
              <motion.span layoutId="pulse-rail-bar" className="absolute -left-3 top-1/2 h-7 w-1 -translate-y-1/2 rounded-r-full bg-emerald-500" />
            ) : null}
            <span className="relative">
              <Icon className="size-5" strokeWidth={isActive ? 2.2 : 1.8} aria-hidden />
              {id === 'chats' ? <UnreadBubble count={unread} /> : null}
            </span>
            {label}
          </button>
        )
      })}
    </nav>
  )
}

// ── Style 3 · edge — Minimalist Hidden Edge Rail ─────────────

function EdgeRail({ active, onChange, unread }: { active: PulseTab; onChange: (t: PulseTab) => void; unread: number }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="absolute right-0 top-1/2 z-[80] -translate-y-1/2">
      <AnimatePresence>
        {open ? (
          <motion.nav
            key="edge-menu"
            aria-label="Main navigation (edge rail)"
            initial={{ opacity: 0, x: 24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 24 }}
            transition={{ type: 'spring', stiffness: 380, damping: 32 }}
            className="mr-2 flex flex-col gap-1 rounded-2xl border border-zinc-200 bg-white/95 p-2 shadow-xl backdrop-blur dark:border-zinc-700 dark:bg-zinc-900/95"
            onMouseLeave={() => setOpen(false)}
          >
            {TABS.map(({ id, label, Icon }) => (
              <button
                key={id}
                type="button"
                onClick={() => {
                  onChange(id)
                  setOpen(false)
                }}
                className={cn(
                  'flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-xs font-semibold transition-colors',
                  active === id ? 'bg-emerald-600 text-white' : 'text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800',
                )}
              >
                <span className="relative">
                  <Icon className="size-4.5" aria-hidden />
                  {id === 'chats' ? <UnreadBubble count={unread} /> : null}
                </span>
                {label}
              </button>
            ))}
          </motion.nav>
        ) : null}
      </AnimatePresence>
      <motion.button
        type="button"
        aria-label="Open edge navigation"
        onClick={() => setOpen((v) => !v)}
        whileTap={{ scale: 0.9 }}
        className={cn(
          'flex h-14 w-5 flex-col items-center justify-center gap-1.5 rounded-l-xl border border-r-0 border-zinc-200 shadow-md transition-colors dark:border-zinc-700',
          open ? 'bg-emerald-600' : 'bg-white/90 dark:bg-zinc-900/90',
        )}
      >
        <span className={cn('h-0.5 w-2 rounded-full', open ? 'bg-white' : 'bg-zinc-500')} />
        <span className={cn('h-0.5 w-2 rounded-full', open ? 'bg-white' : 'bg-zinc-500')} />
        <span className={cn('h-0.5 w-2 rounded-full', open ? 'bg-white' : 'bg-zinc-500')} />
      </motion.button>
    </div>
  )
}

// ── Style 4 · radial — Volumetric Radial Overlay ─────────────

function RadialOverlay({ active, onChange, unread }: { active: PulseTab; onChange: (t: PulseTab) => void; unread: number }) {
  const [open, setOpen] = useState(false)
  // radial geometry per blueprint: fan the 4 destinations around the FAB
  const angles = [-135, -45, 45, 135]
  const radius = 88

  return (
    <>
      <AnimatePresence>
        {open ? (
          <motion.div
            key="radial-veil"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="absolute inset-0 z-[85] bg-zinc-950/40 backdrop-blur-[20px]"
            onClick={() => setOpen(false)}
          >
            {TABS.map(({ id, label, Icon }, i) => {
              const rad = (angles[i] * Math.PI) / 180
              const x = Math.cos(rad) * radius
              const y = Math.sin(rad) * radius
              return (
                <motion.button
                  key={id}
                  type="button"
                  initial={{ opacity: 0, x: 0, y: 0, scale: 0.4 }}
                  animate={{ opacity: 1, x, y, scale: 1 }}
                  exit={{ opacity: 0, x: 0, y: 0, scale: 0.4 }}
                  transition={{ type: 'spring', stiffness: 320, damping: 24, delay: i * 0.03 }}
                  onClick={(e) => {
                    e.stopPropagation()
                    onChange(id)
                    setOpen(false)
                  }}
                  className="absolute left-1/2 top-1/2 flex size-[68px] -translate-x-1/2 -translate-y-1/2 flex-col items-center justify-center gap-0.5 rounded-full border border-white/30 bg-white/80 text-[9px] font-bold text-zinc-800 shadow-2xl backdrop-blur-xl dark:border-white/10 dark:bg-zinc-900/85 dark:text-white"
                >
                  <span className="relative">
                    <Icon className={cn('size-5', active === id && 'text-emerald-500')} aria-hidden />
                    {id === 'chats' ? <UnreadBubble count={unread} /> : null}
                  </span>
                  {label}
                </motion.button>
              )
            })}
          </motion.div>
        ) : null}
      </AnimatePresence>

      <div className="pointer-events-none absolute inset-x-0 bottom-3 z-[80] flex justify-center">
        <motion.button
          type="button"
          aria-label={open ? 'Close radial navigation' : 'Open radial navigation'}
          onClick={() => setOpen((v) => !v)}
          whileTap={{ scale: 0.92 }}
          animate={{ rotate: open ? 45 : 0 }}
          className="pointer-events-auto flex size-14 items-center justify-center rounded-full bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-[0_10px_36px_-6px_rgba(16,185,129,0.65)]"
        >
          <LayoutGrid className="size-6" aria-hidden />
        </motion.button>
      </div>
    </>
  )
}

// ── Router ───────────────────────────────────────────────────

/**
 * Overlay nav for acrylic / edge / radial styles (absolutely positioned
 * inside the nearest `relative` ancestor). The solid rail is rendered
 * by the shell itself via <RailNav> because it owns a layout column.
 */
export function OverlayNav({
  style,
  active,
  onChange,
  unread,
}: {
  style: NavStyleId
  active: PulseTab
  onChange: (t: PulseTab) => void
  unread: number
}) {
  if (style === 'edge') return <EdgeRail active={active} onChange={onChange} unread={unread} />
  if (style === 'radial') return <RadialOverlay active={active} onChange={onChange} unread={unread} />
  return <AcrylicDock active={active} onChange={onChange} unread={unread} />
}

/** Left layout column — Persistent Solid Split Rail. */
export function RailNav({ active, onChange, unread }: { active: PulseTab; onChange: (t: PulseTab) => void; unread: number }) {
  return <SolidRail active={active} onChange={onChange} unread={unread} />
}

/**
 * One-call navigation for the shell: picks the persisted style, wires the
 * live unread badge, and renders rail (layout) or overlay (absolute).
 */
export function PulseNav({ me, active, onChange }: { me: AppUser; active: PulseTab; onChange: (t: PulseTab) => void }) {
  const [style] = useNavStyle()
  const unread = useUnread(me)
  if (style === 'rail') return <RailNav active={active} onChange={onChange} unread={unread} />
  return <OverlayNav style={style} active={active} onChange={onChange} unread={unread} />
}

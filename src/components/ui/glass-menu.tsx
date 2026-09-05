// ─────────────────────────────────────────────────────────────
// Pulse — frosted-glass menu primitives (R26).
// The compact glass action menu from the locked design refs:
// a slim frosted panel (heavy blur, specular top rim, deep soft
// shadow) with icon+label rows, pill-shaped hover highlight and
// hairline group dividers. Replaces ALL oversized action sheets.
// Zero emojis — Lucide icons + framer-motion microinteractions.
// ─────────────────────────────────────────────────────────────
'use client'

import { forwardRef, type ComponentPropsWithoutRef } from 'react'
import { motion, type HTMLMotionProps } from 'framer-motion'
import type { LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { spring } from '@/lib/motion'

/** Entrance/exit motion shared by every glass menu mount. */
export const glassMenuMotion = {
  initial: { opacity: 0, scale: 0.94, y: 6 },
  animate: { opacity: 1, scale: 1, y: 0, transition: spring.snappy },
  exit: { opacity: 0, scale: 0.96, y: 4, transition: { duration: 0.14, ease: 'easeIn' as const } },
}

interface GlassMenuProps extends HTMLMotionProps<'div'> {
  /** snug width for action menus; content grows past it when needed */
  className?: string
}

/**
 * Frosted glass menu panel — 1px specular rim, saturate-boosted blur,
 * soft deep shadow. Compose with <GlassMenuItem> / <GlassMenuSeparator>.
 */
export const GlassMenu = forwardRef<HTMLDivElement, GlassMenuProps>(function GlassMenu(
  { className, children, ...props },
  ref,
) {
  return (
    <motion.div
      ref={ref}
      role="menu"
      {...glassMenuMotion}
      {...props}
      className={cn(
        'glass-menu-panel min-w-[228px] overflow-hidden rounded-2xl p-1.5',
        className,
      )}
    >
      {children}
    </motion.div>
  )
})

interface GlassMenuItemProps extends ComponentPropsWithoutRef<'button'> {
  icon?: LucideIcon
  /** primary row label (falls back to children when omitted) */
  label?: React.ReactNode
  /** trailing short-cut hint or secondary glyph slot */
  trailing?: React.ReactNode
  destructive?: boolean
  active?: boolean
}

/** Icon + label row with the signature pill hover highlight. */
export const GlassMenuItem = forwardRef<HTMLButtonElement, GlassMenuItemProps>(
  function GlassMenuItem(
    { icon: Icon, label, trailing, destructive, active, className, children, ...props },
    ref,
  ) {
    return (
      <button
        ref={ref}
        role="menuitem"
        type="button"
        {...props}
        className={cn(
          'group/menu-item relative flex h-10 w-full items-center gap-3 rounded-xl px-3 text-left',
          'text-[14px] font-medium outline-none transition-colors duration-150',
          'text-zinc-700 hover:bg-zinc-900/[0.055] focus-visible:bg-zinc-900/[0.055] active:bg-zinc-900/[0.08]',
          'dark:text-zinc-200 dark:hover:bg-white/[0.07] dark:focus-visible:bg-white/[0.07] dark:active:bg-white/[0.1]',
          'disabled:pointer-events-none disabled:opacity-40',
          destructive &&
            'text-rose-600 hover:bg-rose-500/10 active:bg-rose-500/15 dark:text-rose-400 dark:hover:bg-rose-500/10',
          active &&
            'bg-zinc-900/[0.06] dark:bg-white/[0.08]',
          className,
        )}
      >
        {Icon ? (
          <Icon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-zinc-500 transition-transform duration-200 group-hover/menu-item:scale-110',
              'dark:text-zinc-400',
              destructive && 'text-rose-500 dark:text-rose-400',
            )}
          />
        ) : null}
        <span className="min-w-0 flex-1 truncate">{label ?? children}</span>
        {trailing ? (
          <span className="shrink-0 text-[11px] font-semibold tabular-nums text-zinc-400 dark:text-zinc-500">
            {trailing}
          </span>
        ) : null}
      </button>
    )
  },
)

/** Hairline group divider — separates logical action clusters. */
export function GlassMenuSeparator({ className }: { className?: string }) {
  return (
    <div
      role="separator"
      aria-hidden
      className={cn('mx-2.5 my-1 h-px bg-zinc-900/[0.07] dark:bg-white/[0.08]', className)}
    />
  )
}

/** Small caps group title inside a menu (e.g. "REACTIONS", "MESSAGE"). */
export function GlassMenuLabel({ className, ...props }: ComponentPropsWithoutRef<'div'>) {
  return (
    <div
      {...props}
      className={cn(
        'px-3 pb-1 pt-2 text-[10px] font-bold uppercase tracking-[0.14em] text-zinc-400 dark:text-zinc-500',
        className,
      )}
    />
  )
}

/**
 * Reaction quick-row — a segmented glass pill strip (the horizontal
 * capsule cluster from the design refs). `children` should be the
 * reaction buttons themselves; keep each ≥36px for touch targets.
 */
export function GlassMenuStrip({ className, ...props }: ComponentPropsWithoutRef<'div'>) {
  return (
    <div
      {...props}
      className={cn(
        'mx-1 mb-1 flex items-center gap-0.5 rounded-full border border-zinc-900/[0.06] bg-white/50 p-1',
        'dark:border-white/[0.07] dark:bg-white/[0.04]',
        className,
      )}
    />
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — profile primitives (R25-b).
// Shared building blocks for the Profile tab and its sub-pickers:
// section cards, chevron rows, switch rows and spring counters.
// One motion language via @/lib/motion — never hand-rolled springs.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect } from 'react'
import { animate, motion, useMotionValue, useReducedMotion, useTransform } from 'framer-motion'
import { ChevronRight, type LucideIcon } from 'lucide-react'
import { Switch } from '@/components/ui/switch'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'

/** Liquid-glass section card used by every profile group. */
export const PROFILE_CARD =
  'rounded-3xl border border-zinc-200/70 bg-white/70 backdrop-blur-2xl backdrop-saturate-150 shadow-[inset_0_1px_0_rgba(255,255,255,0.45),0_14px_40px_-24px_rgba(9,42,31,0.28)] dark:border-white/10 dark:bg-zinc-900/60 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.06),0_14px_40px_-24px_rgba(0,0,0,0.6)]'

/** Section = uppercase micro-title + glass card, entrance on the shared bezier. */
export function ProfileSection({
  title,
  delay = 0,
  className,
  children,
}: {
  title: string
  delay?: number
  className?: string
  children: React.ReactNode
}) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.section
      aria-label={title}
      initial={reducedMotion ? false : { opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.32, ease: ease.out, delay }}
      className={cn('mt-6', className)}
    >
      <h2 className="mb-2 px-1 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
        {title}
      </h2>
      <div className={cn(PROFILE_CARD, 'p-2')}>{children}</div>
    </motion.section>
  )
}

/** Row press: body scales 0.98 while the chevron nudges x+2 (variant propagation). */
export const ROW_VARIANTS = { rest: { scale: 1 }, tap: { scale: 0.98 } } as const
export const CHEVRON_VARIANTS = { rest: { x: 0 }, tap: { x: 2 } } as const

/**
 * Mature chevron row — icon tile, title + optional description, trailing
 * value / control, animated chevron. min-h 56px = comfortable touch target.
 */
export function ChevronRow({
  icon: Icon,
  iconClassName,
  title,
  description,
  trailing,
  onPress,
  destructive = false,
  ariaLabel,
}: {
  icon: LucideIcon
  iconClassName?: string
  title: string
  description?: string
  /** static trailing content (value text, badge) rendered before the chevron */
  trailing?: React.ReactNode
  onPress: () => void
  destructive?: boolean
  ariaLabel?: string
}) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.button
      type="button"
      onClick={() => {
        haptic(8)
        onPress()
      }}
      initial="rest"
      animate="rest"
      whileTap={reducedMotion ? undefined : 'tap'}
      variants={ROW_VARIANTS}
      transition={pressSpring}
      aria-label={ariaLabel}
      className={cn(
        'flex min-h-[56px] w-full items-center gap-3 rounded-2xl px-2.5 py-2 text-left outline-none transition-colors',
        destructive
          ? 'hover:bg-destructive/10 active:bg-destructive/15'
          : 'hover:bg-zinc-100/80 active:bg-zinc-200/60 dark:hover:bg-white/5 dark:active:bg-white/10',
      )}
    >
      <span
        className={cn(
          'flex size-9 shrink-0 items-center justify-center rounded-xl',
          destructive ? 'bg-destructive/10' : 'bg-zinc-900/5 dark:bg-white/10',
          iconClassName,
        )}
      >
        <Icon className={cn('size-4', destructive ? 'text-destructive' : '')} aria-hidden />
      </span>
      <span className="min-w-0 flex-1">
        <span
          className={cn(
            'block truncate text-sm font-semibold',
            destructive ? 'text-destructive' : 'text-zinc-800 dark:text-zinc-100',
          )}
        >
          {title}
        </span>
        {description ? (
          <span className="mt-0.5 block truncate text-[11px] text-zinc-400 dark:text-zinc-500">
            {description}
          </span>
        ) : null}
      </span>
      {trailing ? (
        <span className="shrink-0 text-xs font-medium text-zinc-500 dark:text-zinc-400">{trailing}</span>
      ) : null}
      <motion.span variants={CHEVRON_VARIANTS} className="shrink-0" aria-hidden>
        <ChevronRight
          className={cn('size-4', destructive ? 'text-destructive/70' : 'text-zinc-400 dark:text-zinc-500')}
        />
      </motion.span>
    </motion.button>
  )
}

/** Static preference row with a Switch — label stays a real <label> target. */
export function SwitchRow({
  icon: Icon,
  iconClassName,
  title,
  description,
  checked,
  onCheckedChange,
  disabled,
  ariaLabel,
  children,
}: {
  icon: LucideIcon
  iconClassName?: string
  title: string
  description?: string
  checked: boolean
  onCheckedChange: (v: boolean) => void
  disabled?: boolean
  ariaLabel?: string
  /** optional extra content rendered under the row (quiet-hours time editor) */
  children?: React.ReactNode
}) {
  return (
    <div className="px-2.5 py-1.5">
      <div className="flex min-h-[40px] items-center gap-3">
        <span
          className={cn(
            'flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-900/5 dark:bg-white/10',
            iconClassName,
          )}
        >
          <Icon className="size-4" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">{title}</span>
          {description ? (
            <span className="mt-0.5 block text-[11px] text-zinc-400 dark:text-zinc-500">{description}</span>
          ) : null}
        </span>
        <Switch
          checked={checked}
          onCheckedChange={onCheckedChange}
          disabled={disabled}
          aria-label={ariaLabel ?? title}
          className="data-[state=checked]:bg-[var(--ui-accent,#10b981)]"
        />
      </div>
      {children}
    </div>
  )
}

/**
 * Spring counter — the motion value renders straight to the DOM
 * (no per-frame React state), spring-popping on mount and retargeting on change.
 */
export function CountUp({ value, cap = false }: { value: number; cap?: boolean }) {
  const reducedMotion = useReducedMotion()
  const count = useMotionValue(0)
  const text = useTransform(count, (v) => {
    const n = Math.round(v)
    return cap && n > 99 ? '99+' : n.toLocaleString('en-US')
  })

  useEffect(() => {
    if (reducedMotion) {
      count.set(value)
      return
    }
    const controls = animate(count, value, { type: 'spring', stiffness: 140, damping: 26 })
    return () => controls.stop()
  }, [value, reducedMotion, count])

  return <motion.span>{text}</motion.span>
}

/** One stat tile of the hero stats row (real data only — callers pass values). */
export function StatTile({
  label,
  value,
  delay = 0,
  accent = false,
}: {
  label: string
  value: React.ReactNode
  delay?: number
  accent?: boolean
}) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.div
      initial={reducedMotion ? false : { opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...spring.soft, delay: stagger(delay, 0.05) }}
      className={cn(
        'flex min-w-0 flex-col items-center gap-0.5 rounded-2xl px-1 py-2.5 text-center',
        accent
          ? 'bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_12%,transparent)]'
          : 'bg-zinc-100/70 dark:bg-white/5',
      )}
    >
      <span
        className={cn(
          'text-base font-bold tracking-tight tabular-nums',
          accent
            ? 'text-[var(--ui-accent,#10b981)]'
            : 'text-zinc-800 dark:text-zinc-100',
        )}
      >
        {value}
      </span>
      <span className="truncate text-[10px] font-medium uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
        {label}
      </span>
    </motion.div>
  )
}

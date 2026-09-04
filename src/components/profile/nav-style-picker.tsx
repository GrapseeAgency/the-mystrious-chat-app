// ─────────────────────────────────────────────────────────────
// Pulse — navigation architecture picker (R25-b).
// All 13 swappable navigation languages from NAV_STYLE_META with
// zone badges (Bottom / Top / Side / Overlay), zone filter chips,
// a sliding active pill (layoutId) and live switching through
// useNavStyle().setStyle — the shell re-renders instantly.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import {
  ArrowDownFromLine,
  Check,
  Compass,
  Grip,
  Hand,
  Layers,
  LayoutDashboard,
  LayoutGrid,
  MoonStar,
  PanelBottom,
  PanelBottomOpen,
  PanelRight,
  Terminal,
  Wand2,
  type LucideIcon,
} from 'lucide-react'
import { toast } from 'sonner'
import { NAV_STYLE_META, useNavStyle, type NavStyleId } from '@/components/chat/nav-router'
import type { NavStyleMeta } from '@/lib/nav-registry'
import { cn } from '@/lib/utils'
import { pressSpring, pressTap, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'

/** Icons for the nav architectures (verbatim strings for JIT). */
const NAV_ICONS: Record<NavStyleId, LucideIcon> = {
  capsule: Compass,
  'floating-top': ArrowDownFromLine,
  'floating-dock': LayoutDashboard,
  pill: Grip,
  'bottom-bar': PanelBottom,
  'tab-bar': PanelBottomOpen,
  'floating-tab-bar': Layers,
  'command-bar': Terminal,
  rail: PanelRight,
  island: MoonStar,
  radial: LayoutGrid,
  gesture: Hand,
  'contextual-dock': Wand2,
}

const ZONE_LABEL: Record<NavStyleMeta['zone'], string> = {
  bottom: 'Bottom',
  top: 'Top',
  side: 'Side',
  overlay: 'Overlay',
}

const ZONE_FILTERS: Array<'all' | NavStyleMeta['zone']> = ['all', 'bottom', 'top', 'side', 'overlay']

export function NavStylePicker() {
  const [style, apply] = useNavStyle()
  const [zoneFilter, setZoneFilter] = useState<'all' | NavStyleMeta['zone']>('all')
  const reducedMotion = useReducedMotion()

  const visible = useMemo(
    () => (zoneFilter === 'all' ? NAV_STYLE_META : NAV_STYLE_META.filter((s) => s.zone === zoneFilter)),
    [zoneFilter],
  )

  return (
    <div className="p-1">
      {/* zone filter chips */}
      <div className="mb-2 flex items-center gap-1.5" role="tablist" aria-label="Filter navigation styles by zone">
        {ZONE_FILTERS.map((z) => {
          const active = zoneFilter === z
          return (
            <motion.button
              key={z}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => {
                haptic(6)
                setZoneFilter(z)
              }}
              whileTap={reducedMotion ? undefined : pressTap}
              transition={pressSpring}
              className={cn(
                'relative min-h-[32px] rounded-full px-3 text-[11px] font-bold uppercase tracking-wide outline-none transition-colors',
                active ? 'text-white' : 'text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200',
              )}
            >
              {active ? (
                <motion.span
                  layoutId="nav-zone-chip"
                  transition={pressSpring}
                  className="absolute inset-0 rounded-full bg-[var(--ui-accent,#10b981)]"
                  aria-hidden
                />
              ) : null}
              <span className="relative">{z === 'all' ? 'All' : ZONE_LABEL[z]}</span>
            </motion.button>
          )
        })}
        <span className="ml-auto pr-1 text-[10px] font-medium text-zinc-400 dark:text-zinc-500">
          {visible.length} of {NAV_STYLE_META.length}
        </span>
      </div>

      {/* style cards */}
      <div className="grid grid-cols-2 gap-2">
        <AnimatePresence mode="popLayout" initial={false}>
          {visible.map((opt, i) => {
            const Icon = NAV_ICONS[opt.id] ?? Compass
            const isActive = style === opt.id
            return (
              <motion.button
                key={opt.id}
                type="button"
                aria-pressed={isActive}
                onClick={() => {
                  if (!isActive) {
                    haptic(12)
                    apply(opt.id)
                    toast.success(`${opt.label} navigation active`)
                  }
                }}
                layout
                initial={reducedMotion ? false : { opacity: 0, scale: 0.94 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={reducedMotion ? undefined : { opacity: 0, scale: 0.94 }}
                transition={{ ...spring.snappy, delay: reducedMotion ? 0 : 0.02 * i }}
                whileTap={reducedMotion ? undefined : { scale: 0.97 }}
                className={cn(
                  'relative min-h-[92px] rounded-2xl border p-2.5 text-left outline-none transition-colors',
                  isActive
                    ? 'border-[var(--ui-accent,#10b981)]/70 bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_10%,transparent)]'
                    : 'border-zinc-200/80 hover:border-zinc-300 dark:border-white/10 dark:hover:border-white/20',
                )}
              >
                <span className="flex items-start justify-between gap-1">
                  <span
                    className={cn(
                      'flex size-8 items-center justify-center rounded-xl',
                      isActive
                        ? 'bg-[var(--ui-accent,#10b981)] text-white'
                        : 'bg-zinc-900/5 text-zinc-500 dark:bg-white/10 dark:text-zinc-300',
                    )}
                  >
                    <Icon className="size-4" aria-hidden />
                  </span>
                  <span className="rounded-full bg-zinc-900/5 px-1.5 py-px text-[9px] font-bold uppercase tracking-wide text-zinc-500 dark:bg-white/10 dark:text-zinc-400">
                    {ZONE_LABEL[opt.zone]}
                  </span>
                </span>
                <span className="mt-1.5 flex items-center gap-1 text-[12px] font-bold leading-tight text-zinc-800 dark:text-zinc-100">
                  <span className="truncate">{opt.label}</span>
                  {isActive ? (
                    <motion.span
                      layoutId="nav-style-active-check"
                      transition={pressSpring}
                      className="flex size-3.5 shrink-0 items-center justify-center rounded-full bg-[var(--ui-accent,#10b981)]"
                      aria-hidden
                    >
                      <Check className="size-2.5 text-white" strokeWidth={4} />
                    </motion.span>
                  ) : null}
                </span>
                <span className="mt-0.5 line-clamp-2 block text-[10px] leading-snug text-zinc-500 dark:text-zinc-400">
                  {opt.hint}
                </span>
              </motion.button>
            )
          })}
        </AnimatePresence>
      </div>
    </div>
  )
}

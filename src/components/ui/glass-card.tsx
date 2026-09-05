// ─────────────────────────────────────────────────────────────
// Pulse premium UI (R22) — liquid-glass surface primitive.
// One recipe, everywhere: frosted blur + saturation boost,
// hairline border, inset top highlight (the "glass edge") and a
// soft outer shadow. Use <GlassCard> for full motion support or
// sprinkle the exported `glassSurface` class string onto any
// existing panel that needs the same material.
// ─────────────────────────────────────────────────────────────
'use client'

import { motion, type HTMLMotionProps } from 'framer-motion'
import { cn } from '@/lib/utils'

/**
 * The liquid-glass recipe as a raw class string — for surfaces that
 * can't swap to <GlassCard> (overlays, third-party wrappers, one-off divs).
 * Compose with layout classes via cn(); tailwind-merge resolves the
 * rounded-* / shadow-* overrides in favor of the later class.
 *
 * R32 tuned vs the 5 glass refs: brighter specular top rim + inner ring,
 * lower fill alpha (real see-through), 3-layer shadow (wide ambient +
 * contact + tight). Values mirror --glass-rim/--glass-shadow tokens.
 */
export const glassSurface = cn(
  'relative rounded-3xl',
  'border border-white/30 dark:border-white/[0.12]',
  'bg-white/60 dark:bg-white/[0.07]',
  'backdrop-blur-2xl backdrop-saturate-150',
  // specular top edge + inner ring = light catching the glass edge,
  // then the layered soft shadow stack
  'shadow-[inset_0_1px_0_0_rgba(255,255,255,0.8),inset_0_0_0_1px_rgba(255,255,255,0.16),0_20px_50px_-24px_rgba(9,42,31,0.3),0_44px_110px_-40px_rgba(9,42,31,0.18)]',
  'dark:shadow-[inset_0_1px_0_0_rgba(255,255,255,0.14),inset_0_0_0_1px_rgba(255,255,255,0.06),0_20px_50px_-24px_rgba(0,0,0,0.6),0_48px_120px_-40px_rgba(0,0,0,0.55)]',
)

/** Optional emerald aura that blooms from the top edge of the card. */
const glassGlowEmerald =
  'bg-[radial-gradient(85%_60%_at_50%_0%,rgba(16,185,129,0.16),transparent_70%)]'

export interface GlassCardProps extends Omit<HTMLMotionProps<'div'>, 'children'> {
  /** 'emerald' adds a soft emerald bloom along the top edge. */
  glow?: 'emerald' | 'none'
  children?: React.ReactNode
}

/**
 * GlassCard — the reusable liquid-glass panel.
 *
 * All framer-motion div props pass through, so entrance animations ride the
 * shared motion tokens, e.g.:
 *   <GlassCard initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={spring.soft}>
 */
export function GlassCard({ className, glow = 'none', children, ...rest }: GlassCardProps) {
  return (
    <motion.div className={cn(glassSurface, className)} {...rest}>
      {glow === 'emerald' ? (
        <span
          aria-hidden
          className={cn('pointer-events-none absolute inset-0 rounded-[inherit]', glassGlowEmerald)}
        />
      ) : null}
      {children}
    </motion.div>
  )
}

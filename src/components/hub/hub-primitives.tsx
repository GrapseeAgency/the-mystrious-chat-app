// ─────────────────────────────────────────────────────────────
// Pulse Hub — shared glass primitives for the sub-page system
// (R27-b): accent icon tiles, the glass sub-page header, install
// state badges and the stagger choreography used by both the
// category page and the app page. Glass language per the locked
// R26 refs; zero emojis — Lucide icons + spring motion only.
// ─────────────────────────────────────────────────────────────
'use client'

import { motion, type Variants } from 'framer-motion'
import {
  Briefcase,
  ChevronLeft,
  Hexagon,
  Landmark,
  MessagesSquare,
  Network,
  Orbit,
  Palette,
  Rocket,
  ShieldCheck,
  ShoppingBag,
  type LucideIcon,
} from 'lucide-react'
import { initialsOf, buzz } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { spring } from '@/lib/motion'
import { CATEGORY_META, appAccent, type MatrixApp, type MatrixCategory } from '@/lib/hub-catalog'

// ── Stagger choreography ─────────────────────────────────────

export const staggerParent: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.045 } },
}

export const staggerChild: Variants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { type: 'spring', stiffness: 460, damping: 32 } },
}

// ── Category icons (static map — catalog stays dependency-free) ──

export const CATEGORY_ICONS: Record<MatrixCategory, LucideIcon> = {
  'Dev-Ops / Community Boards': MessagesSquare,
  'Workplace Canvas / Dev-Ops': Briefcase,
  'E-Commerce Showcase': ShoppingBag,
  'E-Commerce / Global FinTech': Landmark,
  'E-Commerce / Hyper-Apps': Rocket,
  'Web3 FinTech / Hyper-Apps': Hexagon,
  'Stark Privacy Minimalist': ShieldCheck,
  'Cross-Server Bridges / Matrix': Network,
  'Spatial 3D Environments': Orbit,
  'Spatial 2D/3D Art': Palette,
}

// ── App icon tile ────────────────────────────────────────────

/**
 * Accent-gradient icon tile — brand-true per-app gradient with a
 * specular top highlight and soft shadow. Sizes keep initials legible.
 */
export function AppIconTile({
  app,
  size = 44,
  className,
}: {
  app: MatrixApp
  size?: number
  className?: string
}) {
  const [from, to] = appAccent(app)
  return (
    <div
      aria-hidden
      className={cn('relative flex shrink-0 items-center justify-center overflow-hidden text-white', className)}
      style={{
        width: size,
        height: size,
        borderRadius: Math.max(10, Math.round(size * 0.3)),
        backgroundImage: `linear-gradient(135deg, ${from}, ${to})`,
        fontSize: Math.max(11, Math.round(size * 0.34)),
        boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.45), inset 0 0 0 1px rgba(255,255,255,0.12), 0 8px 20px -8px rgba(0,0,0,0.5)',
      }}
    >
      <span
        className="pointer-events-none absolute inset-x-0 top-0 h-1/2 opacity-40"
        style={{ backgroundImage: 'linear-gradient(180deg, rgba(255,255,255,0.55), rgba(255,255,255,0))' }}
      />
      <span className="relative font-black leading-none tracking-tight">{initialsOf(app.name)}</span>
    </div>
  )
}

// ── Category accent tile (for the root rails) ────────────────

export function CategoryIconTile({
  category,
  size = 40,
  className,
}: {
  category: MatrixCategory
  size?: number
  className?: string
}) {
  const Icon = CATEGORY_ICONS[category]
  const [from, to] = CATEGORY_META[category].accent
  return (
    <div
      aria-hidden
      className={cn('relative flex shrink-0 items-center justify-center overflow-hidden text-white', className)}
      style={{
        width: size,
        height: size,
        borderRadius: Math.max(10, Math.round(size * 0.3)),
        backgroundImage: `linear-gradient(135deg, ${from}, ${to})`,
        boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.45), inset 0 0 0 1px rgba(255,255,255,0.12), 0 8px 20px -8px rgba(0,0,0,0.5)',
      }}
    >
      <span
        className="pointer-events-none absolute inset-x-0 top-0 h-1/2 opacity-40"
        style={{ backgroundImage: 'linear-gradient(180deg, rgba(255,255,255,0.55), rgba(255,255,255,0))' }}
      />
      <Icon className="relative" style={{ width: size * 0.46, height: size * 0.46 }} />
    </div>
  )
}

// ── Glass sub-page header (settings SectionPage pattern) ─────

export function HubSubHeader({
  title,
  subtitle,
  trailing,
  onBack,
  backLabel,
}: {
  title: string
  subtitle?: string
  /** right-aligned slot (badges, chips) */
  trailing?: React.ReactNode
  onBack: () => void
  backLabel: string
}) {
  return (
    <div className="glass-deep glass-sheen z-10 flex h-14 shrink-0 items-center gap-2 px-2.5">
      <button
        type="button"
        onClick={() => {
          buzz(8)
          onBack()
        }}
        aria-label={backLabel}
        className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-transform duration-150 hover:text-zinc-900 active:scale-90 dark:text-zinc-300 dark:hover:text-white"
      >
        <ChevronLeft className="size-[19px]" aria-hidden />
      </button>
      <div className="min-w-0 flex-1">
        <h2 className="truncate text-[15.5px] font-bold leading-tight tracking-tight text-zinc-900 dark:text-zinc-50">
          {title}
        </h2>
        {subtitle ? (
          <p className="truncate text-[11px] font-medium text-zinc-500">{subtitle}</p>
        ) : null}
      </div>
      {trailing}
    </div>
  )
}

// ── Install state badge (real AppInstall data) ───────────────

export function ConnectedBadge({ appName, className }: { appName: string; className?: string }) {
  return (
    <motion.span
      key="connected"
      initial={{ scale: 0.7, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={spring.bouncy}
      aria-label={`${appName} connected`}
      className={cn(
        'relative inline-flex h-8 shrink-0 items-center gap-1.5 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-3 text-[11px] font-bold text-emerald-700 dark:text-emerald-400',
        className,
      )}
    >
      <span className="relative flex size-1.5">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-500 opacity-60" />
        <span className="relative inline-flex size-1.5 rounded-full bg-emerald-500" />
      </span>
      Connected
    </motion.span>
  )
}

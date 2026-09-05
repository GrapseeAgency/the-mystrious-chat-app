// ─────────────────────────────────────────────────────────────
// Pulse Chat — SettingsScreen (R26-c): section-by-section glass
// settings. Root = a compact grouped section list (NO search, NO
// top overlay); every section opens a full sub-page with its own
// glass sub-header, hash-routed via #/settings/<id> — browser
// back works, deep links land directly inside the section.
//
// Every control is wired to REAL persisted state — zero mocks:
// - Color mode          → next-themes useTheme
// - UI language         → useUiThemeStore ([data-ui] CSS vars)
// - Navigation style    → useNavStyleStore (nav-router swaps live)
// - Alerts/quiet hours  → pulseSettingsStore (gates haptic()/ping)
// - Haptics             → pulseSettingsStore (haptic() gate)
// - Chat prefs          → usePrefs().save (PATCH /api/settings)
// - Realtime state      → usePulseRealtime (live socket status)
// - Drafts / outbox     → pulseDraftsStore / pulseOutboxStore
// - Footprint numbers   → GET /api/users/[id]/stats
// - PWA install         → promptPwaInstall + usePulsePwa
//
// Glass language (locked R26 refs): .glass-deep + .glass-sheen
// shells, .glass-pill segmented pickers, .glass-row-hover rows,
// GlassMenu popups. Zero emojis — Lucide icons only.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion, type Variants } from 'framer-motion'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import { useStore } from 'zustand'
import { toast } from 'sonner'
import {
  Accessibility,
  AppWindow,
  Bell,
  Blend,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  CircleEllipsis,
  CircleSlash,
  CloudOff,
  Columns3,
  Command,
  Component,
  Copy,
  Database,
  Download,
  ExternalLink,
  Eye,
  Feather,
  FileText,
  Github,
  Hand,
  Heart,
  Image as ImageIcon,
  Info,
  MessagesSquare,
  Mic,
  Monitor,
  Moon,
  MoonStar,
  Palette,
  PanelBottom,
  PanelLeft,
  PanelTop,
  Pill,
  Play,
  Radar,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Sparkles,
  SquareStack,
  Star,
  Sun,
  TriangleAlert,
  UserRound,
  UsersRound,
  Vibrate,
  Volume2,
  WandSparkles,
  Waves,
  Wifi,
  Wind,
  Workflow,
  X,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import type { AppUser, UserStats } from '@/lib/types'
import { apiJson, formatMemberSince } from '@/lib/pulse-utils'
import { DEFAULT_WEBGL_MODE, isWebglMode, type WebGLMode } from '@/components/fx/webgl-glow'
import { WEBGL_MODES } from '@/components/fx/webgl-glow'
import { usePrefs, usePrefsValues } from '@/lib/prefs'
import type { PulsePrefs } from '@/lib/prefs-defaults'
import { usePulseSession } from '@/lib/pulse-store'
import { pulseOutboxStore } from '@/lib/pulse-outbox'
import { pulseDraftsStore } from '@/lib/pulse-drafts'
import { promptPwaInstall, usePulsePwa } from '@/lib/pwa-store'
import {
  haptic,
  isQuietHoursNow,
  playIncomingPing,
  pulseSettingsStore,
  type ChatsListFilter,
} from '@/lib/pulse-settings'
import {
  UI_THEMES,
  getUiThemeMeta,
  useUiThemeStore,
  type UiThemeId,
  type UiThemeMeta,
} from '@/lib/ui-theme'
import {
  NAV_STYLES,
  useNavStyleStore,
  type NavStyleId,
  type NavStyleMeta,
} from '@/lib/nav-registry'
import { NAV_STYLE_META } from '@/components/chat/nav-router'
import { useHashNav } from '@/lib/hash-router'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { pressTap, spring } from '@/lib/motion'
import { useMounted } from '@/hooks/use-mounted'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { UserAvatar } from '@/components/chat/user-avatar'
import {
  GlassMenu,
  GlassMenuItem,
  GlassMenuLabel,
  GlassMenuSeparator,
} from '@/components/ui/glass-menu'

/** Build metadata — mirrored by hand from package.json (version is not an import). */
const PULSE_VERSION = '0.2.1'

const GITHUB_URL = 'https://github.com/GrapseeAgency/the-mystrious-chat-app'

// ── section registry ─────────────────────────────────────────

type SectionId =
  | 'account'
  | 'appearance'
  | 'chat'
  | 'notifications'
  | 'privacy'
  | 'realtime'
  | 'accessibility'
  | 'data'
  | 'about'

interface SectionDef {
  id: SectionId
  label: string
  caption: string
  Icon: LucideIcon
}

const SECTION_MAP: Record<SectionId, SectionDef> = {
  account: {
    id: 'account',
    label: 'Account',
    caption: 'Profile, handle and session',
    Icon: UserRound,
  },
  appearance: {
    id: 'appearance',
    label: 'Appearance',
    caption: 'Theme languages, color mode, navigation',
    Icon: Palette,
  },
  chat: {
    id: 'chat',
    label: 'Chat',
    caption: 'Wallpaper, bubbles, drafts and outbox',
    Icon: MessagesSquare,
  },
  notifications: {
    id: 'notifications',
    label: 'Notifications',
    caption: 'Sound, previews, quiet hours',
    Icon: Bell,
  },
  privacy: {
    id: 'privacy',
    label: 'Privacy & Security',
    caption: 'Read receipts and presence',
    Icon: ShieldCheck,
  },
  realtime: {
    id: 'realtime',
    label: 'Real-time & Voice',
    caption: 'Live connection and voice rooms',
    Icon: Radar,
  },
  accessibility: {
    id: 'accessibility',
    label: 'Accessibility',
    caption: 'Motion and haptic feedback',
    Icon: Accessibility,
  },
  data: {
    id: 'data',
    label: 'Data & Storage',
    caption: 'Footprint, local data, install',
    Icon: Database,
  },
  about: {
    id: 'about',
    label: 'About',
    caption: 'Version and project',
    Icon: Info,
  },
}

/** Root groups — the compact section list order. */
const SECTION_GROUPS: Array<{ label: string; ids: SectionId[] }> = [
  { label: 'Personal', ids: ['account', 'appearance', 'chat', 'notifications'] },
  { label: 'System', ids: ['privacy', 'realtime', 'accessibility'] },
  { label: 'Data', ids: ['data'] },
  { label: 'About', ids: ['about'] },
]

// ── real preference data (wallpapers are consumed by chat-room) ──

const WALLPAPERS: Array<{ id: PulsePrefs['wallpaper']; label: string; preview: string }> = [
  { id: 'none', label: 'None', preview: 'bg-zinc-100 dark:bg-zinc-800' },
  {
    id: 'aurora',
    label: 'Aurora',
    preview:
      'bg-gradient-to-br from-emerald-200 via-teal-200 to-emerald-400 dark:from-emerald-900 dark:via-teal-950 dark:to-emerald-700',
  },
  {
    id: 'dusk',
    label: 'Dusk',
    preview:
      'bg-gradient-to-br from-amber-200 via-rose-300 to-zinc-400 dark:from-amber-950 dark:via-rose-950 dark:to-zinc-800',
  },
  {
    id: 'forest',
    label: 'Forest',
    preview:
      'bg-gradient-to-br from-lime-200 via-emerald-300 to-green-500 dark:from-green-950 dark:via-emerald-900 dark:to-green-700',
  },
  {
    id: 'mono',
    label: 'Mono',
    preview: 'bg-gradient-to-br from-zinc-200 to-zinc-400 dark:from-zinc-700 dark:to-zinc-900',
  },
]

/** One Lucide icon per navigation architecture (labels live in the registry). */
const NAV_ICONS: Record<NavStyleId, LucideIcon> = {
  capsule: Component,
  'floating-top': PanelTop,
  'floating-dock': AppWindow,
  pill: Pill,
  'bottom-bar': PanelBottom,
  'tab-bar': Columns3,
  'floating-tab-bar': SquareStack,
  'command-bar': Command,
  rail: PanelLeft,
  island: CircleEllipsis,
  radial: Radar,
  gesture: Hand,
  'contextual-dock': Workflow,
}

/** One Lucide icon per UI theme language. */
const THEME_ICONS: Record<UiThemeId, LucideIcon> = {
  glass: Sparkles,
  kinetic: Zap,
  minimal: Feather,
  dynamic: WandSparkles,
  aero: Wind,
}

// ── entrance variants (reduced-motion aware) ─────────────────

type NavDirection = 'forward' | 'back'

const listVariants: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.04, delayChildren: 0.03 } },
}

const rowVariants: Variants = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: spring.soft },
}

// ── small building blocks ────────────────────────────────────

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="px-1.5 pb-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-zinc-400 dark:text-zinc-500">
      {children}
    </p>
  )
}

/** A labeled glass-deep card of hairline-divided rows (sub-page groups). */
function Group({
  label,
  children,
  className,
}: {
  label: string
  children: React.ReactNode
  className?: string
}) {
  return (
    <motion.section variants={rowVariants} className={cn('pb-5', className)}>
      <SectionLabel>{label}</SectionLabel>
      <div className="glass-deep glass-sheen overflow-hidden rounded-3xl p-1.5">
        <div className="divide-y divide-zinc-200/50 dark:divide-white/[0.05]">{children}</div>
      </div>
    </motion.section>
  )
}

function FooterNote({ children }: { children: React.ReactNode }) {
  return (
    <motion.p
      variants={rowVariants}
      className="px-2 pb-4 text-[11.5px] leading-relaxed text-zinc-400 dark:text-zinc-500"
    >
      {children}
    </motion.p>
  )
}

function IconTile({ Icon }: { Icon: LucideIcon }) {
  return (
    <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 dark:bg-emerald-400/10">
      <Icon className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
    </span>
  )
}

/** Static info row (no control) — always backed by real state in its caption/trailing. */
function StaticRow({
  Icon,
  title,
  caption,
  trailing,
}: {
  Icon: LucideIcon
  title: string
  caption?: string
  trailing?: React.ReactNode
}) {
  return (
    <div className="flex min-h-[56px] items-center gap-3 rounded-2xl px-3 py-2.5">
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
          {title}
        </span>
        {caption ? (
          <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
            {caption}
          </span>
        ) : null}
      </span>
      {trailing}
    </div>
  )
}

/** Real toggle row — `checked`/`onCheckedChange` always bind to a live store. */
function ToggleRow({
  Icon,
  title,
  description,
  checked,
  onCheckedChange,
}: {
  Icon: LucideIcon
  title: string
  description: string
  checked: boolean
  onCheckedChange: (v: boolean) => void
}) {
  const reduced = useReducedMotion()
  return (
    <motion.label
      whileTap={reduced ? undefined : pressTap}
      className="glass-row-hover flex min-h-[56px] cursor-pointer items-center gap-3 rounded-2xl px-3 py-2.5"
    >
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
          {title}
        </span>
        <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
          {description}
        </span>
      </span>
      <Switch
        checked={checked}
        onCheckedChange={(v) => {
          haptic(10)
          onCheckedChange(v)
        }}
        aria-label={title}
      />
    </motion.label>
  )
}

/** Tappable row that opens a picker (inline block or GlassMenu popup). */
function PickerRow({
  Icon,
  title,
  caption,
  value,
  onClick,
}: {
  Icon: LucideIcon
  title: string
  caption?: string
  value: string
  onClick: () => void
}) {
  const reduced = useReducedMotion()
  return (
    <motion.button
      type="button"
      onClick={() => {
        haptic(10)
        onClick()
      }}
      whileTap={reduced ? undefined : pressTap}
      className="glass-row-hover flex min-h-[56px] w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
    >
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
          {title}
        </span>
        {caption ? (
          <span className="block truncate text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
            {caption}
          </span>
        ) : null}
      </span>
      <span className="shrink-0 text-[12px] font-semibold tabular-nums text-zinc-500 dark:text-zinc-400">
        {value}
      </span>
      <ChevronRight className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
    </motion.button>
  )
}

/** Labeled inline picker block (icon + title + caption above the control). */
function PickerBlock({
  Icon,
  title,
  caption,
  children,
}: {
  Icon: LucideIcon
  title: string
  caption?: string
  children: React.ReactNode
}) {
  return (
    <div className="px-3 py-3">
      <div className="flex items-center gap-3 pb-2.5">
        <IconTile Icon={Icon} />
        <span className="min-w-0 flex-1">
          <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
            {title}
          </span>
          {caption ? (
            <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
              {caption}
            </span>
          ) : null}
        </span>
      </div>
      {children}
    </div>
  )
}

/**
 * Glass-pill segmented picker (the locked .glass-pill chip row) with a
 * sliding active fill. Every onChange writes straight to a real store.
 */
function PillPicker<T extends string>({
  layoutId,
  value,
  onChange,
  options,
  ariaLabel,
}: {
  layoutId: string
  value: T
  onChange: (v: T) => void
  options: Array<{ value: T; label: string; Icon?: LucideIcon }>
  ariaLabel: string
}) {
  const reduced = useReducedMotion()
  return (
    <div className="glass-pill flex w-full items-center gap-1 p-1" role="radiogroup" aria-label={ariaLabel}>
      {options.map((o) => {
        const selected = value === o.value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={selected}
            onClick={() => {
              haptic(8)
              onChange(o.value)
            }}
            className={cn(
              'relative flex h-9 min-w-0 flex-1 items-center justify-center rounded-full px-2 text-[12.5px] font-semibold outline-none transition-colors duration-150 focus-visible:ring-2 focus-visible:ring-emerald-500/60',
              selected
                ? 'text-zinc-900 dark:text-white'
                : 'text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100',
            )}
          >
            {selected ? (
              <motion.span
                layoutId={layoutId}
                transition={reduced ? { duration: 0 } : spring.snappy}
                className="absolute inset-0 rounded-full bg-white shadow-sm dark:bg-zinc-600/70"
                aria-hidden
              />
            ) : null}
            <span className="relative z-10 flex min-w-0 items-center gap-1.5">
              {o.Icon ? <o.Icon className="size-3.5 shrink-0" aria-hidden /> : null}
              <span className="truncate">{o.label}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}

/** Row with a real destructive/outline action button (cache clears). */
function ActionRow({
  Icon,
  title,
  caption,
  actionLabel,
  onAction,
  disabled,
}: {
  Icon: LucideIcon
  title: string
  caption: string
  actionLabel: string
  onAction: () => void
  disabled?: boolean
}) {
  return (
    <div className="flex min-h-[56px] items-center gap-3 rounded-2xl px-3 py-2.5">
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
          {title}
        </span>
        <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
          {caption}
        </span>
      </span>
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={disabled}
        onClick={() => {
          haptic(12)
          onAction()
        }}
        className={cn(
          'h-8 shrink-0 rounded-full px-3.5 text-[12px] font-semibold',
          disabled
            ? 'text-zinc-400 dark:text-zinc-500'
            : 'border-rose-500/40 text-rose-600 hover:bg-rose-500/10 hover:text-rose-700 dark:border-rose-400/30 dark:text-rose-400',
        )}
      >
        {actionLabel}
      </Button>
    </div>
  )
}

function StatusBadge({ tone, children }: { tone: 'ok' | 'warn' | 'off' | 'info'; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        'shrink-0 rounded-full px-2.5 py-1 text-[11px] font-bold tabular-nums',
        tone === 'ok' && 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
        tone === 'warn' && 'bg-amber-500/10 text-amber-600 dark:text-amber-400',
        tone === 'off' && 'bg-rose-500/10 text-rose-600 dark:text-rose-400',
        tone === 'info' && 'bg-zinc-900/[0.06] text-zinc-500 dark:bg-white/[0.08] dark:text-zinc-300',
      )}
    >
      {children}
    </span>
  )
}

function StatTile({ Icon, value, label }: { Icon: LucideIcon; value: number | string; label: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-2xl border border-zinc-200/70 bg-white/60 p-3 dark:border-white/[0.06] dark:bg-white/[0.04]">
      <Icon className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
      <p className="text-xl font-bold leading-none tracking-tight tabular-nums text-zinc-900 dark:text-zinc-50">
        {value}
      </p>
      <p className="text-[10.5px] font-medium leading-tight text-zinc-500 dark:text-zinc-400">{label}</p>
    </div>
  )
}

// ── popup plumbing (GlassMenu language) ──────────────────────

type MenuKind = 'ui-theme' | 'nav-style'

function MenuBackdrop({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1, transition: { duration: 0.15 } }}
      exit={{ opacity: 0, transition: { duration: 0.14 } }}
      onClick={onClose}
      className="absolute inset-0 z-40 flex items-center justify-center bg-zinc-950/25 p-6 backdrop-blur-[2px] dark:bg-zinc-950/45"
    >
      <div onClick={(e) => e.stopPropagation()} className="max-h-full">
        {children}
      </div>
    </motion.div>
  )
}

// ── root section row ─────────────────────────────────────────

function RootRow({
  def,
  hint,
  reduced,
  onOpen,
}: {
  def: SectionDef
  hint: string
  reduced: boolean
  onOpen: (id: SectionId) => void
}) {
  return (
    <motion.button
      type="button"
      onClick={() => onOpen(def.id)}
      whileTap={reduced ? undefined : pressTap}
      className="glass-row-hover flex w-full items-center gap-3 rounded-2xl px-2.5 py-2 text-left outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
    >
      <IconTile Icon={def.Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
          {def.label}
        </span>
        <span className="block truncate text-[12px] tabular-nums text-zinc-500 dark:text-zinc-400">
          {hint}
        </span>
      </span>
      <ChevronRight className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
    </motion.button>
  )
}

// ── sub-page shell (glass sub-header + slide-in) ─────────────

function SectionPage({
  def,
  direction,
  reduced,
  onBack,
  children,
}: {
  def: SectionDef
  direction: NavDirection
  reduced: boolean
  onBack: () => void
  children: React.ReactNode
}) {
  return (
    <motion.div
      key={def.id}
      initial={reduced ? { opacity: 0 } : { opacity: 0, x: direction === 'forward' ? 44 : -44 }}
      animate={{ opacity: 1, x: 0, transition: spring.soft }}
      exit={
        reduced
          ? { opacity: 0, transition: { duration: 0.12 } }
          : {
              opacity: 0,
              x: direction === 'forward' ? -32 : 32,
              transition: { duration: 0.18, ease: 'easeIn' },
            }
      }
      className="absolute inset-0 z-10 flex flex-col"
    >
      <div className="glass-deep glass-sheen z-10 flex h-14 shrink-0 items-center gap-2 px-2.5">
        <button
          type="button"
          onClick={onBack}
          aria-label={`Back to settings, from ${def.label}`}
          className="glass-pill flex size-9 shrink-0 items-center justify-center text-zinc-600 outline-none transition-transform duration-150 hover:text-zinc-900 active:scale-90 dark:text-zinc-300 dark:hover:text-white"
        >
          <ChevronLeft className="size-[18px]" aria-hidden />
        </button>
        <IconTile Icon={def.Icon} />
        <h2 className="min-w-0 truncate text-[15.5px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
          {def.label}
        </h2>
      </div>
      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-14 pt-4">
        <motion.div variants={listVariants} initial={reduced ? false : 'hidden'} animate="show" className="flex flex-col">
          {children}
        </motion.div>
      </div>
    </motion.div>
  )
}

// ── shared section context ───────────────────────────────────

interface SectionCtx {
  user: AppUser | null
  prefs: PulsePrefs
  save: (patch: Partial<PulsePrefs>) => void
  reduced: boolean
  mounted: boolean
  stats: UseQueryResult<UserStats>
  openMenu: (kind: MenuKind) => void
  /** closes settings — the Profile tab lives one level below */
  onEditProfile: () => void
  onOpenHub?: () => void
  connected: boolean
  onlineCount: number
  draftCount: number
  queuedCount: number
  installReady: boolean
  onInstall: () => void
}

// ── Account ──────────────────────────────────────────────────

function AccountSection({ ctx }: { ctx: SectionCtx }) {
  const { user } = ctx
  const statusLine =
    user !== null && (user.statusEmoji !== null || user.statusText !== null)
      ? `${user.statusEmoji ?? ''} ${user.statusText ?? ''}`.trim()
      : (user?.about ?? '')

  const copyUserId = async () => {
    if (!user) return
    try {
      await navigator.clipboard.writeText(user.id)
      toast.success('User ID copied')
    } catch {
      toast.error('Copy failed — clipboard unavailable')
    }
  }

  return (
    <>
      <Group label="Profile">
        <div className="p-3.5">
          <div className="flex items-center gap-3.5">
            {user ? (
              <UserAvatar name={user.name} color={user.color} size={52} />
            ) : (
              <span className="size-[52px] rounded-full bg-zinc-200 dark:bg-zinc-700" aria-hidden />
            )}
            <div className="min-w-0 flex-1">
              <p className="flex items-center gap-1.5">
                <span className="truncate text-[15.5px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                  {user?.name ?? 'Signed out'}
                </span>
                {user ? (
                  <span className="shrink-0 rounded-md bg-emerald-500/10 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-emerald-600 dark:text-emerald-400">
                    You
                  </span>
                ) : null}
              </p>
              <p className="truncate text-[12.5px] font-medium text-zinc-500 dark:text-zinc-400">
                {user?.username ? `@${user.username}` : 'No handle yet'}
              </p>
              {statusLine ? (
                <p className="truncate text-[12px] text-zinc-400 dark:text-zinc-500">{statusLine}</p>
              ) : null}
              {user ? (
                <p className="pt-0.5 text-[11px] font-medium uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
                  Member since {formatMemberSince(user.createdAt)}
                </p>
              ) : null}
            </div>
          </div>
        </div>
        <PickerRow
          Icon={UserRound}
          title="Edit profile"
          caption="Name, handle, status and avatar — in the Profile tab"
          value="Open"
          onClick={ctx.onEditProfile}
        />
      </Group>

      <Group label="Session">
        <div className="flex min-h-[56px] items-center gap-3 rounded-2xl px-3 py-2.5">
          <IconTile Icon={Copy} />
          <span className="min-w-0 flex-1">
            <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
              User ID
            </span>
            <span className="block truncate font-mono text-[11.5px] text-zinc-500 dark:text-zinc-400">
              {user?.id ?? '—'}
            </span>
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!user}
            onClick={() => void copyUserId()}
            className="h-8 shrink-0 rounded-full px-3.5 text-[12px] font-semibold"
          >
            Copy
          </Button>
        </div>
        <StaticRow
          Icon={Smartphone}
          title="Session scope"
          caption="Signed in on this browser tab only (sessionStorage) — other tabs can hold a different account."
          trailing={<StatusBadge tone={user ? 'ok' : 'off'}>{user ? 'Active' : 'None'}</StatusBadge>}
        />
      </Group>
    </>
  )
}

// ── Appearance — color mode, UI languages, navigation ────────

function AppearanceSection({ ctx }: { ctx: SectionCtx }) {
  const { theme, setTheme } = useTheme()
  const themeValue = theme === 'light' || theme === 'dark' ? theme : 'system'
  const uiTheme = useUiThemeStore((s) => s.theme)
  const navStyle = useNavStyleStore((s) => s.style)
  const themeMeta = getUiThemeMeta(uiTheme)
  const navMeta = NAV_STYLES.find((s) => s.id === navStyle)
  const { prefs, save } = ctx

  return (
    <>
      <Group label="Color mode">
        <PickerBlock
          Icon={themeValue === 'dark' ? Moon : themeValue === 'light' ? Sun : Monitor}
          title="Light / dark"
          caption="System follows your device setting."
        >
          <PillPicker
            layoutId="pp-colormode"
            ariaLabel="Color mode"
            value={themeValue}
            onChange={setTheme}
            options={[
              { value: 'light', label: 'Light', Icon: Sun },
              { value: 'dark', label: 'Dark', Icon: Moon },
              { value: 'system', label: 'System', Icon: Monitor },
            ]}
          />
        </PickerBlock>
      </Group>

      <Group label="UI language">
        <PickerRow
          Icon={THEME_ICONS[uiTheme]}
          title="Design language"
          caption={themeMeta.detail}
          value={themeMeta.label}
          onClick={() => ctx.openMenu('ui-theme')}
        />
        <FooterNote>
          Running <span className="font-semibold text-zinc-500 dark:text-zinc-400">{themeMeta.label}</span>{' '}
          with {themeMeta.motion} motion — each language restyles every surface through its own
          design tokens, instantly.
        </FooterNote>
      </Group>

      <Group label="Navigation">
        <PickerRow
          Icon={NAV_ICONS[navStyle]}
          title="Navigation style"
          caption={navMeta ? `${navMeta.hint} — ${navMeta.zone} zone` : undefined}
          value={navMeta?.label ?? 'Floating Capsule'}
          onClick={() => ctx.openMenu('nav-style')}
        />
        <FooterNote>
          Thirteen architectures are available — switching applies to the shell navigation
          immediately.
        </FooterNote>
      </Group>
      <Group label="Ambient field">
        <PickerBlock
          Icon={Sparkles}
          title="WebGL ambience"
          caption="A living GPU shader field behind every surface — pauses when hidden, honors reduced motion."
        >
          <WebglModePicker value={prefs['fx.webglMode']} onChange={(m) => save({ 'fx.webglMode': m })} />
        </PickerBlock>
        <FooterNote>
          Five realtime shader modes — off keeps the DOM particle layer instead. Each mode is a
          different ambient world: aurora bands, glass caustics, gradient mesh, star drift.
        </FooterNote>
      </Group>
    </>
  )
}

/** Segmented picker for the WebGL ambient field (off + 4 shader modes). */
function WebglModePicker({
  value,
  onChange,
}: {
  value: string | undefined
  onChange: (mode: WebGLMode) => void
}) {
  const current: WebGLMode = isWebglMode(value) ? value : DEFAULT_WEBGL_MODE
  const MODE_ICONS: Record<WebGLMode, LucideIcon> = {
    off: CircleSlash,
    aurora: Sparkles,
    caustics: Waves,
    mesh: Blend,
    stars: Star,
  }
  return (
    <PillPicker
      layoutId="pp-webgl"
      ariaLabel="WebGL ambience mode"
      value={current}
      onChange={onChange}
      options={WEBGL_MODES.map((m) => ({
        value: m,
        label: m === 'off' ? 'Off' : m.charAt(0).toUpperCase() + m.slice(1),
        Icon: MODE_ICONS[m],
      }))}
    />
  )
}

function ChatSection({ ctx }: { ctx: SectionCtx }) {
  const { prefs, save } = ctx
  const listFilter = pulseSettingsStore((s) => s.listFilter)
  const setListFilter = pulseSettingsStore((s) => s.setListFilter)
  const wallpaperLabel = WALLPAPERS.find((w) => w.id === prefs.wallpaper)?.label ?? 'None'

  return (
    <>
      <Group label="Message look">
        <PickerBlock
          Icon={ImageIcon}
          title="Chat wallpaper"
          caption={`Background behind every chat room — currently ${wallpaperLabel}.`}
        >
          <div className="grid grid-cols-5 gap-2 pb-1" role="radiogroup" aria-label="Chat wallpaper">
            {WALLPAPERS.map((w) => {
              const selected = prefs.wallpaper === w.id
              return (
                <button
                  key={w.id}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  aria-label={`${w.label} wallpaper`}
                  onClick={() => {
                    haptic(8)
                    save({ wallpaper: w.id })
                  }}
                  className="flex min-h-[44px] flex-col items-center gap-1.5 rounded-xl p-1 outline-none transition-transform active:scale-95 focus-visible:ring-2 focus-visible:ring-emerald-500/60"
                >
                  <span
                    className={cn(
                      'relative block aspect-square w-full rounded-lg border shadow-sm',
                      w.preview,
                      selected
                        ? 'border-emerald-500 ring-2 ring-emerald-500/60'
                        : 'border-zinc-200/80 dark:border-white/10',
                    )}
                  >
                    {selected ? (
                      <span className="absolute inset-0 flex items-center justify-center">
                        <Check className="size-4 text-emerald-600 drop-shadow dark:text-emerald-300" aria-hidden />
                      </span>
                    ) : null}
                  </span>
                  <span
                    className={cn(
                      'text-[10px] font-semibold',
                      selected ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                    )}
                  >
                    {w.label}
                  </span>
                </button>
              )
            })}
          </div>
        </PickerBlock>
        <PickerBlock Icon={MessagesSquare} title="Bubble corners" caption="Corner radius of message bubbles.">
          <PillPicker
            layoutId="pp-bubble"
            ariaLabel="Bubble corner radius"
            value={prefs.bubbleRadius}
            onChange={(v) => save({ bubbleRadius: v })}
            options={[
              { value: 'md', label: 'Medium' },
              { value: 'lg', label: 'Large' },
              { value: 'pill', label: 'Pill' },
            ]}
          />
        </PickerBlock>
        <PickerBlock Icon={Columns3} title="Message density" caption="Row spacing in the message list.">
          <PillPicker
            layoutId="pp-density"
            ariaLabel="Message density"
            value={prefs.density}
            onChange={(v) => save({ density: v })}
            options={[
              { value: 'cozy', label: 'Cozy' },
              { value: 'compact', label: 'Compact' },
            ]}
          />
        </PickerBlock>
      </Group>

      <Group label="Chats list">
        <PickerBlock
          Icon={Eye}
          title="Default list filter"
          caption="Applied to the Chats tab when you open it."
        >
          <PillPicker
            layoutId="pp-filter"
            ariaLabel="Default chats list filter"
            value={listFilter}
            onChange={(v: ChatsListFilter) => setListFilter(v)}
            options={[
              { value: 'all', label: 'All' },
              { value: 'unread', label: 'Unread' },
              { value: 'groups', label: 'Groups' },
            ]}
          />
        </PickerBlock>
      </Group>

      <Group label="Drafts & outbox">
        <StaticRow
          Icon={FileText}
          title="Saved drafts"
          caption="Per-conversation composer drafts stored on this device."
          trailing={<StatusBadge tone="info">{ctx.draftCount}</StatusBadge>}
        />
        <StaticRow
          Icon={CloudOff}
          title="Offline queue"
          caption={
            ctx.mounted && ctx.queuedCount > 0
              ? `${ctx.queuedCount} ${ctx.queuedCount === 1 ? 'message' : 'messages'} waiting to send when you're back online.`
              : 'Empty — every composed message has been delivered.'
          }
          trailing={
            ctx.mounted && ctx.queuedCount > 0 ? (
              <StatusBadge tone="warn">{ctx.queuedCount}</StatusBadge>
            ) : (
              <Check className="size-4 shrink-0 text-emerald-500" aria-hidden />
            )
          }
        />
      </Group>
    </>
  )
}

// ── Notifications — real alert gates + quiet hours ───────────

function NotificationsSection({ ctx }: { ctx: SectionCtx }) {
  const { prefs, save } = ctx
  const soundOn = pulseSettingsStore((s) => s.soundOn)
  const setSoundOn = pulseSettingsStore((s) => s.setSoundOn)
  const quietHoursOn = pulseSettingsStore((s) => s.quietHoursOn)
  const setQuietHoursOn = pulseSettingsStore((s) => s.setQuietHoursOn)
  const quietStart = pulseSettingsStore((s) => s.quietStart)
  const setQuietStart = pulseSettingsStore((s) => s.setQuietStart)
  const quietEnd = pulseSettingsStore((s) => s.quietEnd)
  const setQuietEnd = pulseSettingsStore((s) => s.setQuietEnd)

  const quietNow = isQuietHoursNow({ quietHoursOn, quietStart, quietEnd })

  const timeInputClass =
    'h-11 w-full rounded-xl border border-zinc-200/80 bg-white/60 px-3 text-[13.5px] font-semibold text-zinc-800 outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60 [color-scheme:light] dark:border-white/10 dark:bg-zinc-800/60 dark:text-zinc-100 dark:[color-scheme:dark]'

  return (
    <>
      <Group label="Alerts">
        <ToggleRow
          Icon={Volume2}
          title="Incoming sound"
          description="Master ding for new messages on this device."
          checked={soundOn}
          onCheckedChange={setSoundOn}
        />
        <ToggleRow
          Icon={Eye}
          title="Message previews"
          description="Show message text in notification banners."
          checked={prefs.notifPreviews}
          onCheckedChange={(v) => save({ notifPreviews: v })}
        />
        <ToggleRow
          Icon={Bell}
          title="Message pop"
          description="Per-account pop sound for incoming messages."
          checked={prefs.notifSound}
          onCheckedChange={(v) => save({ notifSound: v })}
        />
        <ToggleRow
          Icon={Smartphone}
          title="Vibration"
          description="Buzz on incoming messages, where the device supports it."
          checked={prefs.notifVibrate}
          onCheckedChange={(v) => save({ notifVibrate: v })}
        />
      </Group>

      <Group label="Quiet hours">
        <ToggleRow
          Icon={MoonStar}
          title="Quiet hours"
          description="Silence sounds and vibration inside the window."
          checked={quietHoursOn}
          onCheckedChange={setQuietHoursOn}
        />
        {quietHoursOn ? (
          <>
            <div className="flex flex-wrap items-end gap-3 px-3 pb-3 pt-1">
              <label className="flex min-h-[44px] flex-1 flex-col justify-center gap-1" style={{ minWidth: 120 }}>
                <span className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-zinc-400 dark:text-zinc-500">
                  From
                </span>
                <input
                  type="time"
                  value={quietStart}
                  onChange={(e) => setQuietStart(e.target.value)}
                  aria-label="Quiet hours start time"
                  className={timeInputClass}
                />
              </label>
              <label className="flex min-h-[44px] flex-1 flex-col justify-center gap-1" style={{ minWidth: 120 }}>
                <span className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-zinc-400 dark:text-zinc-500">
                  Until
                </span>
                <input
                  type="time"
                  value={quietEnd}
                  onChange={(e) => setQuietEnd(e.target.value)}
                  aria-label="Quiet hours end time"
                  className={timeInputClass}
                />
              </label>
            </div>
            <StaticRow
              Icon={MoonStar}
              title="Window status"
              caption={`${quietStart} → ${quietEnd} — overnight windows are supported.`}
              trailing={<StatusBadge tone={quietNow ? 'warn' : 'info'}>{quietNow ? 'Active now' : 'Idle'}</StatusBadge>}
            />
          </>
        ) : null}
      </Group>

      <Group label="Test">
        <div className="px-3 py-3">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              haptic(30)
              playIncomingPing()
            }}
            className="h-11 w-full gap-2 rounded-xl border-zinc-200/80 text-[13px] font-semibold text-zinc-700 hover:bg-zinc-50 dark:border-white/10 dark:text-zinc-200 dark:hover:bg-white/[0.06]"
          >
            <Play className="size-4" aria-hidden />
            Preview alert
          </Button>
          <p className="pt-1.5 text-[11.5px] leading-snug text-zinc-400 dark:text-zinc-500">
            Plays the real two-note ding and fires a haptic buzz, honoring the toggles above.
          </p>
        </div>
      </Group>
    </>
  )
}

// ── Privacy & Security — the real synced controls ────────────

function PrivacySection({ ctx }: { ctx: SectionCtx }) {
  const { prefs, save } = ctx
  return (
    <>
      <Group label="Visibility">
        <ToggleRow
          Icon={Eye}
          title="Last seen & online"
          description="Let people see when you were last active on Pulse."
          checked={prefs.lastSeenVisible}
          onCheckedChange={(v) => save({ lastSeenVisible: v })}
        />
        <ToggleRow
          Icon={CheckCheck}
          title="Read receipts"
          description="Show others when you've read their messages."
          checked={prefs.readReceipts}
          onCheckedChange={(v) => save({ readReceipts: v })}
        />
      </Group>
      <FooterNote>
        These sync to your Pulse account. Typing-indicator hiding and blocked accounts are not
        configurable yet — no fake switches are shown for them.
      </FooterNote>
    </>
  )
}

// ── Real-time & Voice — live socket state ────────────────────

function RealtimeSection({ ctx }: { ctx: SectionCtx }) {
  const [deviceOnline, setDeviceOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine,
  )

  useEffect(() => {
    const up = () => setDeviceOnline(true)
    const down = () => setDeviceOnline(false)
    window.addEventListener('online', up)
    window.addEventListener('offline', down)
    return () => {
      window.removeEventListener('online', up)
      window.removeEventListener('offline', down)
    }
  }, [])

  return (
    <>
      <Group label="Connection">
        <StaticRow
          Icon={ctx.connected ? ShieldCheck : TriangleAlert}
          title={ctx.connected ? 'Realtime socket' : 'Reconnecting'}
          caption={
            ctx.connected
              ? 'Connected — messages, presence and typing stream live.'
              : 'Socket offline — outgoing messages queue in the offline outbox.'
          }
          trailing={<StatusBadge tone={ctx.connected ? 'ok' : 'off'}>{ctx.connected ? 'Live' : 'Down'}</StatusBadge>}
        />
        <StaticRow
          Icon={UsersRound}
          title="People online now"
          caption="Live presence snapshot from the socket server."
          trailing={<StatusBadge tone="info">{ctx.onlineCount}</StatusBadge>}
        />
        <StaticRow
          Icon={deviceOnline ? Wifi : CloudOff}
          title="Device network"
          caption={
            deviceOnline
              ? 'This device is online — delivery is instant.'
              : 'This device is offline — messages wait in the queue.'
          }
          trailing={<StatusBadge tone={deviceOnline ? 'ok' : 'warn'}>{deviceOnline ? 'Online' : 'Offline'}</StatusBadge>}
        />
      </Group>

      <Group label="Voice">
        <StaticRow
          Icon={Mic}
          title="Voice rooms"
          caption="Studio capture with echo cancellation, noise suppression and auto gain. Quality presets are not configurable yet."
        />
      </Group>
    </>
  )
}

// ── Accessibility — motion + haptics (real state) ────────────

function AccessibilitySection({ ctx }: { ctx: SectionCtx }) {
  const [sysReduced, setSysReduced] = useState(false)

  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const sync = () => setSysReduced(mq.matches)
    sync()
    mq.addEventListener?.('change', sync)
    return () => mq.removeEventListener?.('change', sync)
  }, [])

  return (
    <>
      <Group label="Motion">
        <ToggleRow
          Icon={Accessibility}
          title="Reduced motion"
          description="Calm the interface — instant transitions, no parallax or message effects."
          checked={ctx.prefs.reducedMotion}
          onCheckedChange={(v) => ctx.save({ reducedMotion: v })}
        />
        <StaticRow
          Icon={Monitor}
          title="System preference"
          caption={
            sysReduced
              ? 'Your OS asks apps to reduce motion.'
              : 'Your OS has no motion restriction set.'
          }
          trailing={<StatusBadge tone={sysReduced ? 'info' : 'ok'}>{sysReduced ? 'Reduce' : 'Full'}</StatusBadge>}
        />
      </Group>

      <Group label="Touch feedback">
        <ToggleRow
          Icon={Vibrate}
          title="Haptics"
          description="Vibration on taps, sends and incoming alerts — where the device supports it."
          checked={pulseSettingsStore((s) => s.hapticsOn)}
          onCheckedChange={pulseSettingsStore((s) => s.setHapticsOn)}
        />
      </Group>
    </>
  )
}

// ── Data & Storage — footprint, cache actions, install ───────

function DataSection({ ctx }: { ctx: SectionCtx }) {
  const { stats } = ctx

  const clearAllDrafts = () => {
    const ids = Object.keys(pulseDraftsStore.getState().drafts)
    if (ids.length === 0) return
    for (const id of ids) pulseDraftsStore.getState().clearDraft(id)
    toast.success(`Cleared ${ids.length} ${ids.length === 1 ? 'draft' : 'drafts'}`)
  }

  const clearOutbox = () => {
    const ids = pulseOutboxStore.getState().queue.map((q) => q.clientId)
    if (ids.length === 0) return
    for (const id of ids) pulseOutboxStore.getState().remove(id)
    toast.success(`Discarded ${ids.length} queued ${ids.length === 1 ? 'message' : 'messages'}`)
  }

  return (
    <>
      <motion.section variants={rowVariants} className="pb-5">
        <SectionLabel>Your footprint</SectionLabel>
        <div className="glass-deep glass-sheen rounded-3xl p-3">
          <div className="flex items-center gap-3 px-1 pb-3 pt-1">
            <IconTile Icon={Database} />
            <span className="min-w-0 flex-1">
              <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
                Live counts
              </span>
              <span className="block text-[12px] text-zinc-500 dark:text-zinc-400">
                Straight from the Pulse database.
              </span>
            </span>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Refresh stats"
              onClick={() => void stats.refetch()}
              className="size-9 rounded-full text-zinc-400 hover:bg-zinc-100 dark:hover:bg-white/[0.06]"
            >
              <RefreshCw className={cn('size-4', stats.isFetching && 'animate-spin')} aria-hidden />
            </Button>
          </div>
          {!ctx.user ? (
            <div className="flex items-center gap-3 px-1 pb-1">
              <TriangleAlert className="size-5 shrink-0 text-amber-500" aria-hidden />
              <p className="text-[13px] font-medium text-zinc-600 dark:text-zinc-300">
                Sign in to see your stats.
              </p>
            </div>
          ) : stats.isPending ? (
            <div className="grid grid-cols-3 gap-2" role="status" aria-label="Loading stats">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-[92px] rounded-2xl" />
              ))}
            </div>
          ) : stats.isError || !stats.data ? (
            <div className="flex flex-col items-start gap-2 px-1 pb-1">
              <p className="flex items-center gap-2 text-[13px] font-medium text-zinc-600 dark:text-zinc-300">
                <TriangleAlert className="size-4 text-amber-500" aria-hidden />
                Couldn&apos;t load your stats.
              </p>
              <Button size="sm" variant="outline" onClick={() => void stats.refetch()} className="gap-1.5 rounded-xl">
                <RefreshCw className="size-3.5" aria-hidden />
                Try again
              </Button>
            </div>
          ) : (
            <div className="grid grid-cols-3 gap-2">
              <StatTile Icon={MessagesSquare} value={stats.data.messages} label="Messages sent" />
              <StatTile Icon={ImageIcon} value={stats.data.photos} label="Photos" />
              <StatTile Icon={Mic} value={stats.data.voiceNotes} label="Voice notes" />
              <StatTile Icon={UserRound} value={stats.data.chats} label="Chats" />
              <StatTile Icon={UsersRound} value={stats.data.groups} label="Groups" />
              <StatTile Icon={CalendarDays} value={stats.data.days} label="Days active" />
            </div>
          )}
        </div>
      </motion.section>

      <Group label="Local data">
        <ActionRow
          Icon={FileText}
          title="Composer drafts"
          caption={`${ctx.draftCount} saved on this device — clearing frees their storage.`}
          actionLabel="Clear"
          onAction={clearAllDrafts}
          disabled={ctx.draftCount === 0}
        />
        <ActionRow
          Icon={CloudOff}
          title="Offline queue"
          caption={
            ctx.queuedCount > 0
              ? `${ctx.queuedCount} waiting — discarding drops them without sending.`
              : 'Empty — nothing queued right now.'
          }
          actionLabel="Discard"
          onAction={clearOutbox}
          disabled={ctx.queuedCount === 0}
        />
      </Group>

      <Group label="Install">
        {ctx.installReady ? (
          <PickerRow
            Icon={Download}
            title="Install Pulse"
            caption="Add to your home screen — opens instantly, works offline."
            value="Install"
            onClick={ctx.onInstall}
          />
        ) : (
          <StaticRow
            Icon={Smartphone}
            title="Install Pulse"
            caption="Your browser hasn't offered the install prompt yet — check its menu (Share → Add to Home Screen)."
          />
        )}
      </Group>

      {ctx.onOpenHub ? (
        <Group label="Explore">
          <PickerRow
            Icon={Info}
            title="The Hub"
            caption="Wallet · Tasks · Market · Swap · Apps · Logs"
            value="Open"
            onClick={ctx.onOpenHub}
          />
        </Group>
      ) : null}
    </>
  )
}

// ── About — honest build info + project link ─────────────────

function AboutSection() {
  return (
    <>
      <motion.section variants={rowVariants} className="pb-5">
        <div className="glass-deep glass-sheen rounded-3xl p-4">
          <div className="flex items-center gap-3">
            <span className="flex size-11 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-md shadow-emerald-500/25">
              <Sparkles className="size-5" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">Pulse</p>
              <p className="text-[12px] text-zinc-500 dark:text-zinc-400">
                Real-time chat with a built-in economy
              </p>
            </div>
            <StatusBadge tone="info">v{PULSE_VERSION}</StatusBadge>
          </div>
          <p className="pt-3 text-[12.5px] leading-relaxed text-zinc-500 dark:text-zinc-400">
            Chats, groups, topics, threads, polls, games, red packets, voice rooms, stages and the
            Hub economy all run on the real Pulse API with zero mock data — and every control in
            these settings is backed by live state.
          </p>
        </div>
      </motion.section>

      <Group label="Build">
        <StaticRow Icon={Info} title="Version" caption={PULSE_VERSION} trailing={<StatusBadge tone="info">Stable</StatusBadge>} />
        <StaticRow
          Icon={Component}
          title="Framework"
          caption="Next.js 16 · App Router · TypeScript strict"
        />
        <StaticRow
          Icon={Command}
          title="Realtime"
          caption="socket.io mini service on port 3003"
        />
        <StaticRow
          Icon={Database}
          title="Data"
          caption="Prisma ORM + SQLite, zero mock data"
        />
      </Group>

      <Group label="Project">
        <a
          href={GITHUB_URL}
          target="_blank"
          rel="noreferrer"
          className="glass-row-hover flex min-h-[56px] w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
        >
          <IconTile Icon={Github} />
          <span className="min-w-0 flex-1">
            <span className="block text-[14px] font-semibold text-zinc-900 dark:text-zinc-100">
              GitHub repository
            </span>
            <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">
              GrapseeAgency/the-mystrious-chat-app
            </span>
          </span>
          <ExternalLink className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
        </a>
      </Group>

      <motion.footer variants={rowVariants} className="flex flex-col items-center gap-1.5 pb-2 pt-1">
        <Heart className="size-4 fill-emerald-500 text-emerald-500" aria-hidden />
        <p className="text-[13px] font-semibold text-zinc-600 dark:text-zinc-300">Made with Pulse</p>
        <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
          Version {PULSE_VERSION} · chats, hub economy and settings sync live
        </p>
      </motion.footer>
    </>
  )
}

// ── main component ───────────────────────────────────────────

export function SettingsScreen({
  open,
  onClose,
  me,
  onOpenHub,
}: {
  open: boolean
  onClose: () => void
  /** session user (falls back to the pulse session store when omitted) */
  me?: AppUser
  /** closes settings and switches the shell to the Hub tab */
  onOpenHub?: () => void
}) {
  const sessionUser = usePulseSession((s) => s.user)
  const user = me ?? sessionUser
  const prefs = usePrefsValues()
  const save = usePrefs((s) => s.save)
  const reducedFx = useReducedMotion()
  const mounted = useMounted()

  // hash sub-page routing — #/settings/<id> with working browser back
  const { path, navigate, back } = useHashNav()
  const { isConnected, onlineIds } = usePulseRealtime()

  // real registries — selection applies to the whole app instantly
  const uiTheme = useUiThemeStore((s) => s.theme)
  const setUiTheme = useUiThemeStore((s) => s.setTheme)
  const navStyle = useNavStyleStore((s) => s.style)
  const setNavStyle = useNavStyleStore((s) => s.setStyle)

  // real device-side stores
  const soundOn = pulseSettingsStore((s) => s.soundOn)
  const quietHoursOn = pulseSettingsStore((s) => s.quietHoursOn)
  const quietStart = pulseSettingsStore((s) => s.quietStart)
  const quietEnd = pulseSettingsStore((s) => s.quietEnd)
  const draftCount = useStore(pulseDraftsStore, (s) => Object.keys(s.drafts).length)
  const queuedCount = useStore(pulseOutboxStore, (s) => s.queue.length)
  const installEvent = usePulsePwa((s) => s.installEvent)

  const [menu, setMenu] = useState<MenuKind | null>(null)

  // direction-aware section navigation (root → section = forward, back otherwise)
  const [nav, setNav] = useState<{ prev: SectionId | null; dir: NavDirection }>({
    prev: null,
    dir: 'forward',
  })

  const active: SectionId | null = (() => {
    if (!open || !path.startsWith('/settings/')) return null
    const id = path.slice('/settings/'.length)
    return id in SECTION_MAP ? (id as SectionId) : null
  })()

  if (nav.prev !== active) {
    // documented React pattern: adjust state during render when a prop changes
    setNav({ prev: active, dir: nav.prev === null ? 'forward' : 'back' })
  }
  const direction = nav.dir

  const reduced = Boolean(reducedFx) || prefs.reducedMotion

  // real footprint numbers from the live DB — fetched while settings is open
  const stats = useQuery({
    queryKey: ['user-stats', user?.id ?? '-'],
    enabled: open && !!user,
    staleTime: 15_000,
    queryFn: async (): Promise<UserStats> => {
      const res = await apiJson<{ stats: UserStats }>(
        `/api/users/${encodeURIComponent(user?.id ?? '')}/stats`,
      )
      return res.stats
    },
  })

  if (!open) return null

  const openSection = (id: SectionId) => {
    navigate(`/settings/${id}`)
  }

  const closeAll = () => {
    haptic(10)
    onClose()
    if (typeof window !== 'undefined' && window.location.hash.startsWith('#/settings')) {
      navigate('/')
    }
  }

  const goBack = () => {
    haptic(8)
    // a section's back lands on the settings ROOT — never exits the tree
    back('/settings')
  }

  const handleInstall = async () => {
    haptic(12)
    const outcome = await promptPwaInstall()
    if (outcome === 'accepted') {
      toast.success('Installing Pulse…', { description: 'Find it on your home screen.' })
    } else if (outcome === 'unavailable') {
      toast.error('Install is not available right now')
    }
  }

  const wallpaperLabel = WALLPAPERS.find((w) => w.id === prefs.wallpaper)?.label ?? 'None'
  const hints: Record<SectionId, string> = {
    account: user ? (user.username ? `@${user.username}` : user.name) : 'Signed out',
    appearance: getUiThemeMeta(uiTheme).label,
    chat: `${prefs.density === 'cozy' ? 'Cozy' : 'Compact'} · ${wallpaperLabel}`,
    notifications: quietHoursOn ? `Quiet ${quietStart}–${quietEnd}` : soundOn ? 'Alerts on' : 'Alerts off',
    privacy: prefs.readReceipts ? 'Read receipts on' : 'Read receipts off',
    realtime: isConnected ? `${onlineIds.size} online` : 'Offline',
    accessibility: prefs.reducedMotion ? 'Reduced motion' : 'Full motion',
    data: mounted ? `${draftCount} drafts · ${queuedCount} queued` : '···',
    about: `v${PULSE_VERSION}`,
  }

  const ctx: SectionCtx = {
    user,
    prefs,
    save,
    reduced,
    mounted,
    stats,
    openMenu: setMenu,
    onEditProfile: onClose,
    onOpenHub,
    connected: isConnected,
    onlineCount: onlineIds.size,
    draftCount,
    queuedCount,
    installReady: mounted && Boolean(installEvent),
    onInstall: () => void handleInstall(),
  }

  const renderSection = (id: SectionId) => {
    switch (id) {
      case 'account':
        return <AccountSection ctx={ctx} />
      case 'appearance':
        return <AppearanceSection ctx={ctx} />
      case 'chat':
        return <ChatSection ctx={ctx} />
      case 'notifications':
        return <NotificationsSection ctx={ctx} />
      case 'privacy':
        return <PrivacySection ctx={ctx} />
      case 'realtime':
        return <RealtimeSection ctx={ctx} />
      case 'accessibility':
        return <AccessibilitySection ctx={ctx} />
      case 'data':
        return <DataSection ctx={ctx} />
      case 'about':
        return <AboutSection />
    }
  }

  const def = active !== null ? SECTION_MAP[active] : null

  return (
    <motion.div
      initial={reduced ? { opacity: 0 } : { opacity: 0, y: 24, scale: 0.99 }}
      animate={{ opacity: 1, y: 0, scale: 1, transition: spring.soft }}
      exit={{ opacity: 0, y: 16, transition: { duration: 0.14 } }}
      className="absolute inset-0 z-[70]"
      style={{ background: 'var(--ui-page-bg, #09090b)' }}
      role="dialog"
      aria-modal="true"
      aria-label="Settings"
    >
      <AnimatePresence initial={false}>
        {def === null ? (
          // ── root: compact grouped section list (no search, no overlay) ──
          <motion.div
            key="root"
            initial={reduced ? { opacity: 0 } : { opacity: 0, x: direction === 'forward' ? -28 : 28 }}
            animate={{ opacity: 1, x: 0, transition: spring.soft }}
            exit={
              reduced
                ? { opacity: 0, transition: { duration: 0.12 } }
                : {
                    opacity: 0,
                    x: direction === 'forward' ? -20 : 20,
                    transition: { duration: 0.18, ease: 'easeIn' },
                  }
            }
            className="absolute inset-0 z-10 flex flex-col"
          >
            <div className="flex h-14 shrink-0 items-center px-3">
              <div className="min-w-0 pl-1">
                <p className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-zinc-400 dark:text-zinc-500">
                  Pulse
                </p>
                <h2 className="text-[17px] font-bold leading-tight tracking-tight text-zinc-900 dark:text-zinc-50">
                  Settings
                </h2>
              </div>
              <div className="flex-1" />
              <Button
                variant="ghost"
                size="icon"
                aria-label="Close settings"
                onClick={closeAll}
                className="size-10 rounded-full text-zinc-500 hover:bg-zinc-100 active:scale-95 dark:hover:bg-zinc-800"
              >
                <X className="size-[18px]" aria-hidden />
              </Button>
            </div>

            <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-12 pt-1">
              <motion.div
                variants={listVariants}
                initial={reduced ? false : 'hidden'}
                animate="show"
                className="flex flex-col"
              >
                {SECTION_GROUPS.map((group) => (
                  <motion.section key={group.label} variants={rowVariants} className="pb-5">
                    <SectionLabel>{group.label}</SectionLabel>
                    <div className="glass-deep glass-sheen overflow-hidden rounded-3xl p-1.5">
                      <div className="divide-y divide-zinc-200/50 dark:divide-white/[0.05]">
                        {group.ids.map((id) => (
                          <RootRow
                            key={id}
                            def={SECTION_MAP[id]}
                            hint={hints[id]}
                            reduced={reduced}
                            onOpen={openSection}
                          />
                        ))}
                      </div>
                    </div>
                  </motion.section>
                ))}
                <p className="pb-2 pt-1 text-center text-[11px] text-zinc-400 dark:text-zinc-500">
                  Pulse v{PULSE_VERSION} — every control here is live.
                </p>
              </motion.div>
            </div>
          </motion.div>
        ) : (
          // ── section sub-page: glass sub-header + direction-aware slide ──
          <SectionPage key={def.id} def={def} direction={direction} reduced={reduced} onBack={goBack}>
            {renderSection(def.id)}
          </SectionPage>
        )}
      </AnimatePresence>

      {/* GlassMenu popups (UI language / navigation style pickers) */}
      <AnimatePresence>
        {menu === 'ui-theme' ? (
          <MenuBackdrop onClose={() => setMenu(null)}>
            <GlassMenu className="w-[272px]">
              <GlassMenuLabel>UI language</GlassMenuLabel>
              {UI_THEMES.map((t) => (
                <GlassMenuItem
                  key={t.id}
                  icon={THEME_ICONS[t.id]}
                  active={uiTheme === t.id}
                  onClick={() => {
                    haptic(10)
                    setUiTheme(t.id)
                    setMenu(null)
                  }}
                  trailing={
                    uiTheme === t.id ? (
                      <Check className="size-4 text-emerald-600 dark:text-emerald-300" aria-hidden />
                    ) : undefined
                  }
                >
                  {t.label}
                </GlassMenuItem>
              ))}
              <GlassMenuSeparator />
              <p className="px-3 pb-2 pt-1 text-[11px] leading-snug text-zinc-500 dark:text-zinc-400">
                {getUiThemeMeta(uiTheme).detail}
              </p>
            </GlassMenu>
          </MenuBackdrop>
        ) : null}
        {menu === 'nav-style' ? (
          <MenuBackdrop onClose={() => setMenu(null)}>
            <GlassMenu className="max-h-[400px] w-[288px] overflow-y-auto">
              <GlassMenuLabel>Navigation style</GlassMenuLabel>
              {NAV_STYLE_META.map((s) => (
                <GlassMenuItem
                  key={s.id}
                  icon={NAV_ICONS[s.id]}
                  active={navStyle === s.id}
                  onClick={() => {
                    haptic(10)
                    setNavStyle(s.id)
                    setMenu(null)
                  }}
                  trailing={
                    navStyle === s.id ? (
                      <Check className="size-4 text-emerald-600 dark:text-emerald-300" aria-hidden />
                    ) : (
                      <span className="uppercase">{s.zone}</span>
                    )
                  }
                >
                  {s.label}
                </GlassMenuItem>
              ))}
            </GlassMenu>
          </MenuBackdrop>
        ) : null}
      </AnimatePresence>
    </motion.div>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse Chat — SettingsScreen (R25-c): a full, deep settings tree.
// Single full-screen page with real search filtering and nine
// sections: Account · Appearance · Navigation · Notifications ·
// Chats · Privacy & Security · Data & Storage · System · About.
//
// Every control is wired to a REAL store — zero mocks:
// - UI theme languages  → useUiThemeStore  ([data-ui] CSS vars)
// - Navigation styles   → useNavStyleStore (nav-router swaps live)
// - Alerts/quiet hours  → pulseSettingsStore (gates haptic()/ping)
// - Account chat prefs  → usePrefs().save (PATCH /api/settings)
// - Footprint numbers   → GET /api/users/[id]/stats
// - Offline queue       → pulseOutboxStore
// - PWA install         → promptPwaInstall + usePulsePwa
//
// Visual language is theme-aware: section cards render through the
// shared `.ui-panel` utility (var(--ui-panel-bg/border/radius)) so
// the screen itself restyles with the selected design language.
// ─────────────────────────────────────────────────────────────
'use client'

import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion, type Variants } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import { useStore } from 'zustand'
import { toast } from 'sonner'
import {
  Accessibility,
  AppWindow,
  Bell,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronRight,
  CircleEllipsis,
  CloudOff,
  Columns3,
  Command,
  Component,
  Compass,
  Database,
  Download,
  Eye,
  Feather,
  Hand,
  Heart,
  Image as ImageIcon,
  Info,
  MessagesSquare,
  Mic,
  Monitor,
  Moon,
  MoonStar,
  PanelBottom,
  PanelLeft,
  PanelTop,
  Pill,
  Play,
  Radar,
  RefreshCw,
  Search,
  SearchX,
  ShieldCheck,
  Smartphone,
  Sparkles,
  SquareStack,
  Sun,
  TriangleAlert,
  UserRound,
  UsersRound,
  Vibrate,
  Volume2,
  WandSparkles,
  Wind,
  Workflow,
  X,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import type { AppUser, UserStats } from '@/lib/types'
import { apiJson, formatMemberSince } from '@/lib/pulse-utils'
import { usePrefs, usePrefsValues } from '@/lib/prefs'
import type { PulsePrefs } from '@/lib/prefs-defaults'
import { usePulseSession } from '@/lib/pulse-store'
import { pulseOutboxStore } from '@/lib/pulse-outbox'
import { promptPwaInstall, usePulsePwa } from '@/lib/pwa-store'
import {
  pulseSettingsStore,
  haptic,
  playIncomingPing,
  type ChatsListFilter,
} from '@/lib/pulse-settings'
import { useUiThemeStore, UI_THEMES, type UiThemeId, type UiThemeMeta } from '@/lib/ui-theme'
import { NAV_STYLE_META, type NavStyleId } from '@/components/chat/nav-router'
import { useNavStyleStore, type NavStyleMeta } from '@/lib/nav-registry'
import { pressTap, spring } from '@/lib/motion'
import { useMounted } from '@/hooks/use-mounted'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { UserAvatar } from '@/components/chat/user-avatar'

/** Build metadata — mirrored by hand from package.json (version is not an import). */
const PULSE_VERSION = '0.2.1'

// ── theme-aware primitives ───────────────────────────────────

/** Section cards ride the shared .ui-panel utility → they restyle with the active UI language. */
const PANEL = 'ui-panel overflow-hidden'

/** Hairline dividers between rows inside a panel. */
const DIVIDE = 'divide-y divide-zinc-200/70 dark:divide-zinc-700/50'

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

const ZONE_BADGE: Record<NavStyleMeta['zone'], string> = {
  bottom: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  top: 'bg-amber-500/10 text-amber-700 dark:text-amber-300',
  side: 'bg-violet-500/10 text-violet-700 dark:text-violet-300',
  overlay: 'bg-rose-500/10 text-rose-700 dark:text-rose-300',
}

// ── search plumbing ──────────────────────────────────────────

/** True when the query is empty or ANY of the haystacks matches it. */
function hit(query: string, ...haystacks: Array<string | null | undefined>): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return haystacks
    .filter((t): t is string => typeof t === 'string' && t.length > 0)
    .some((t) => t.toLowerCase().includes(q))
}

// ── entrance variants (reduced-motion aware) ─────────────────

const listVariants: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.035, delayChildren: 0.04 } },
}

const rowVariants: Variants = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: spring.soft },
}

// ── search context (avoids threading `query` through every row) ──

const SearchContext = createContext<string>('')
function useSearchQuery(): string {
  return useContext(SearchContext)
}

// ── small building blocks ────────────────────────────────────

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="px-1 pb-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-zinc-400 dark:text-zinc-500">
      {children}
    </p>
  )
}

function Section({
  label,
  show,
  children,
}: {
  label: string
  show: boolean
  children: React.ReactNode
}) {
  if (!show) return null
  return (
    <motion.section variants={rowVariants} className="pb-5">
      <SectionLabel>{label}</SectionLabel>
      <div className="flex flex-col gap-2.5">{children}</div>
    </motion.section>
  )
}

function IconTile({ Icon }: { Icon: LucideIcon }) {
  return (
    <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 dark:bg-emerald-400/10">
      <Icon className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
    </span>
  )
}

function StaticRow({
  Icon,
  title,
  caption,
  trailing,
  search,
}: {
  Icon: LucideIcon
  title: string
  caption?: string
  trailing?: React.ReactNode
  /** extra invisible keywords for settings search */
  search?: string
}) {
  const q = useSearchQuery()
  if (!hit(q, title, caption, search)) return null
  return (
    <div className="flex min-h-[56px] items-center gap-3 px-4 py-2.5">
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14.5px] font-semibold text-zinc-900 dark:text-zinc-100">{title}</span>
        {caption ? (
          <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">{caption}</span>
        ) : null}
      </span>
      {trailing}
    </div>
  )
}

function ToggleRow({
  Icon,
  title,
  description,
  checked,
  onCheckedChange,
  search,
}: {
  Icon: LucideIcon
  title: string
  description: string
  checked: boolean
  onCheckedChange: (v: boolean) => void
  search?: string
}) {
  const q = useSearchQuery()
  const reduced = useReducedMotion()
  if (!hit(q, title, description, search)) return null
  return (
    <motion.label
      whileTap={reduced ? undefined : pressTap}
      className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4 py-2.5"
    >
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14.5px] font-semibold text-zinc-900 dark:text-zinc-100">{title}</span>
        <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">{description}</span>
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

function Segmented<T extends string>({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: T
  onChange: (v: T) => void
  options: Array<{ value: T; label: string; Icon?: LucideIcon }>
  ariaLabel: string
}) {
  return (
    <Tabs value={value} onValueChange={(v) => onChange(v as T)}>
      <TabsList
        aria-label={ariaLabel}
        className="grid h-10 w-full auto-cols-fr grid-flow-col rounded-xl bg-zinc-100 p-1 dark:bg-zinc-800"
      >
        {options.map((o) => (
          <TabsTrigger
            key={o.value}
            value={o.value}
            className="h-8 gap-1.5 rounded-lg text-[12.5px] font-semibold text-zinc-500 data-[state=active]:bg-white data-[state=active]:text-zinc-900 data-[state=active]:shadow-sm dark:text-zinc-400 dark:data-[state=active]:bg-zinc-600/80 dark:data-[state=active]:text-white"
          >
            {o.Icon ? <o.Icon className="size-3.5" aria-hidden /> : null}
            {o.label}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
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

  // real registries — selection applies to the whole app instantly
  const uiTheme = useUiThemeStore((s) => s.theme)
  const setUiTheme = useUiThemeStore((s) => s.setTheme)
  const navStyle = useNavStyleStore((s) => s.style)
  const setNavStyle = useNavStyleStore((s) => s.setStyle)

  const [query, setQuery] = useState('')

  const reduced = Boolean(reducedFx) || prefs.reducedMotion
  const sheetSpring = reduced ? { duration: 0 } : spring.soft

  // real footprint numbers from the live DB — fetched while settings is open
  const stats = useQuery({
    queryKey: ['user-stats', user?.id ?? '-'],
    enabled: open && !!user,
    staleTime: 15_000,
    queryFn: async (): Promise<UserStats> => {
      const res = await apiJson<{ stats: UserStats }>(`/api/users/${encodeURIComponent(user?.id ?? '')}/stats`)
      return res.stats
    },
  })

  const themeMeta = useMemo(() => UI_THEMES.find((t) => t.id === uiTheme) ?? UI_THEMES[0], [uiTheme])
  const navMeta = useMemo(() => NAV_STYLE_META.find((m) => m.id === navStyle), [navStyle])

  if (!open) return null

  const hasQuery = query.trim().length > 0
  const anyResults = !hasQuery || sectionRegistry.some((k) => hit(query, k.label, k.keywords))

  return (
    <motion.div
      initial={reduced ? { opacity: 0 } : { opacity: 0, y: 28, scale: 0.99 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: 18, transition: { duration: 0.14 } }}
      transition={sheetSpring}
      className="absolute inset-0 z-[70]"
      style={{ background: 'var(--ui-page-bg, #09090b)' }}
      role="dialog"
      aria-modal="true"
      aria-label="Settings"
    >
      <SearchContext.Provider value={query}>
        <div className="flex h-full flex-col">
          {/* header */}
          <div className="flex h-14 shrink-0 items-center gap-1 border-b border-zinc-200/70 px-2 dark:border-zinc-800/70">
            <span className="ml-1 flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 dark:bg-emerald-400/10">
              <Sparkles className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
            </span>
            <h2 className="pl-2 text-[17px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">Settings</h2>
            <div className="flex-1" />
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close settings"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="size-10 rounded-full text-zinc-500 hover:bg-zinc-100 active:scale-95 dark:hover:bg-zinc-800"
            >
              <X className="size-[18px]" aria-hidden />
            </Button>
          </div>

          {/* search — filters every row below in real time */}
          <div className="shrink-0 px-4 pb-1 pt-3">
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-zinc-400 dark:text-zinc-500"
                aria-hidden
              />
              <input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Escape' && query) {
                    e.stopPropagation()
                    setQuery('')
                  }
                }}
                placeholder="Search settings"
                aria-label="Search settings"
                className={cn(
                  'h-11 w-full rounded-2xl border border-zinc-200/80 bg-white/70 pl-10 pr-10 text-[14px] font-medium text-zinc-900 outline-none',
                  'placeholder:text-zinc-400 focus-visible:border-emerald-500/60 focus-visible:ring-2 focus-visible:ring-emerald-500/40',
                  'dark:border-zinc-700/60 dark:bg-zinc-900/60 dark:text-zinc-100 dark:placeholder:text-zinc-500',
                  '[&::-webkit-search-cancel-button]:hidden',
                )}
              />
              {hasQuery ? (
                <button
                  type="button"
                  aria-label="Clear search"
                  onClick={() => setQuery('')}
                  className="absolute right-2 top-1/2 flex size-8 -translate-y-1/2 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
                >
                  <X className="size-4" aria-hidden />
                </button>
              ) : null}
            </div>
          </div>

          {/* the tree */}
          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-12 pt-3">
            {anyResults ? (
              <motion.div
                variants={listVariants}
                initial={reduced ? false : 'hidden'}
                animate="show"
                className="flex flex-col"
              >
                <AccountSection user={user} onClose={onClose} />

                <AppearanceSection
                  uiTheme={uiTheme}
                  setUiTheme={setUiTheme}
                  themeMeta={themeMeta}
                />

                <NavigationSection navStyle={navStyle} setNavStyle={setNavStyle} navMeta={navMeta} />

                <NotificationsSection prefs={prefs} save={save} />

                <ChatsSection prefs={prefs} save={save} />

                <PrivacySection prefs={prefs} save={save} />

                <DataStorageSection
                  user={user}
                  stats={stats}
                  mounted={mounted}
                  reduced={reduced}
                  onOpenHub={onOpenHub}
                />

                <SystemSection prefs={prefs} save={save} />

                <AboutSection />
              </motion.div>
            ) : (
              <EmptyState query={query} onClear={() => setQuery('')} />
            )}
          </div>
        </div>
      </SearchContext.Provider>
    </motion.div>
  )
}

/** Section keyword registry — powers the "no results" empty state. */
const sectionRegistry: Array<{ label: string; keywords: string }> = [
  { label: 'Account', keywords: 'account profile handle username status avatar member since' },
  { label: 'Appearance', keywords: 'appearance theme ui language design language dark mode light mode motion glass kinetic minimal dynamic aero' },
  { label: 'Navigation', keywords: 'navigation nav dock bar tabs rail gesture island radial pill capsule command' },
  { label: 'Notifications', keywords: 'notifications sound haptics vibration quiet hours do not disturb previews alert ping' },
  { label: 'Chats', keywords: 'chats list filter unread groups wallpaper bubble corners density' },
  { label: 'Privacy & Security', keywords: 'privacy security last seen online read receipts visibility' },
  { label: 'Data & Storage', keywords: 'data storage stats footprint offline queue install pwa app messages photos' },
  { label: 'System', keywords: 'system reduced motion accessibility connection online offline version' },
  { label: 'About', keywords: 'about version credits framework next.js prisma socket.io build made' },
]

function EmptyState({ query, onClear }: { query: string; onClear: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 pb-10 pt-16 text-center">
      <span className="flex size-14 items-center justify-center rounded-2xl bg-zinc-900/[0.05] dark:bg-white/[0.06]">
        <SearchX className="size-6 text-zinc-400 dark:text-zinc-500" aria-hidden />
      </span>
      <p className="text-[14.5px] font-semibold text-zinc-700 dark:text-zinc-200">
        No matches for &ldquo;{query.trim()}&rdquo;
      </p>
      <p className="max-w-[280px] text-[12.5px] leading-snug text-zinc-400 dark:text-zinc-500">
        Try &ldquo;theme&rdquo;, &ldquo;sound&rdquo;, &ldquo;navigation&rdquo; or &ldquo;storage&rdquo; — or clear the search to see every setting.
      </p>
      <Button
        variant="outline"
        onClick={onClear}
        className="h-10 gap-2 rounded-xl border-zinc-200/80 text-[13px] font-semibold dark:border-zinc-700/60"
      >
        <X className="size-3.5" aria-hidden />
        Clear search
      </Button>
    </div>
  )
}

// ── Account ──────────────────────────────────────────────────

function AccountSection({ user, onClose }: { user: AppUser | null; onClose: () => void }) {
  const q = useSearchQuery()
  const reduced = useReducedMotion()
  const sectionVisible = hit(
    q,
    'account',
    'profile',
    'handle',
    'username',
    'status',
    'avatar',
    'member since',
    user?.name,
    user?.username,
    user?.about,
  )
  if (!sectionVisible) return null

  const statusLine =
    user !== null && (user.statusEmoji !== null || user.statusText !== null)
      ? `${user.statusEmoji ?? ''} ${user.statusText ?? ''}`.trim()
      : (user?.about ?? '')

  return (
    <Section label="Account" show>
      <div className={cn(PANEL, 'p-4')}>
        <div className="flex items-center gap-3.5">
          {user ? (
            <UserAvatar name={user.name} color={user.color} size={56} />
          ) : (
            <span className="size-14 rounded-full bg-zinc-200 dark:bg-zinc-700" aria-hidden />
          )}
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5">
              <span className="truncate text-[16px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
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
        <motion.button
          type="button"
          onClick={() => {
            haptic(10)
            onClose()
          }}
          whileTap={reduced ? undefined : pressTap}
          className="mt-3 flex min-h-[44px] w-full items-center justify-center gap-2 rounded-xl bg-zinc-900/[0.04] text-[13px] font-semibold text-zinc-600 outline-none transition-colors hover:bg-zinc-900/[0.07] focus-visible:ring-2 focus-visible:ring-emerald-500/60 dark:bg-white/[0.06] dark:text-zinc-300 dark:hover:bg-white/[0.1]"
        >
          <UserRound className="size-4" aria-hidden />
          Edit name, handle or status in the Profile tab
        </motion.button>
      </div>
    </Section>
  )
}

// ── Appearance — the five UI theme languages ─────────────────

function AppearanceSection({
  uiTheme,
  setUiTheme,
  themeMeta,
}: {
  uiTheme: UiThemeId
  setUiTheme: (t: UiThemeId) => void
  themeMeta: UiThemeMeta
}) {
  const q = useSearchQuery()
  const { theme, setTheme } = useTheme()
  const themeValue = theme === 'light' || theme === 'dark' ? theme : 'system'
  const reduced = useReducedMotion()

  const cardsVisible = UI_THEMES.some((t) =>
    hit(q, 'appearance', 'theme', 'ui language', 'design language', 'motion', t.label, t.tagline, t.detail, t.motion),
  )
  const modeVisible = hit(q, 'appearance', 'theme', 'dark mode', 'light mode', 'system theme', 'color scheme')
  if (!cardsVisible && !modeVisible) return null

  return (
    <Section label="Appearance" show>
      {cardsVisible ? (
        <div className={cn(PANEL, 'flex flex-col gap-1 p-2.5')} role="radiogroup" aria-label="UI theme language">
          {UI_THEMES.map((t) => {
            const selected = uiTheme === t.id
            if (
              !hit(
                q,
                'appearance',
                'theme',
                'ui language',
                'design language',
                'motion',
                t.label,
                t.tagline,
                t.detail,
                t.motion,
              )
            )
              return null
            const ThemeIcon = THEME_ICONS[t.id]
            return (
              <motion.button
                key={t.id}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => {
                  haptic(12)
                  setUiTheme(t.id)
                }}
                whileTap={reduced ? undefined : pressTap}
                className={cn(
                  'flex min-h-[72px] w-full items-start gap-3 rounded-2xl border border-transparent p-3 text-left outline-none',
                  'transition-colors hover:bg-zinc-50/80 focus-visible:ring-2 focus-visible:ring-emerald-500/60 dark:hover:bg-zinc-800/40',
                  selected && 'bg-zinc-900/[0.035] dark:bg-white/[0.05]',
                )}
                style={
                  selected
                    ? { borderColor: 'var(--ui-accent, #10b981)', boxShadow: '0 0 0 1px var(--ui-accent, #10b981)' }
                    : undefined
                }
              >
                <span
                  className="relative mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-xl"
                  style={{
                    background: `linear-gradient(135deg, ${t.swatch[0]}, ${t.swatch[1]})`,
                    boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.35), 0 2px 8px -2px rgba(0,0,0,0.35)',
                  }}
                  aria-hidden
                >
                  <ThemeIcon className="size-[18px] text-white/90" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="text-[14.5px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                      {t.label}
                    </span>
                    <span className="rounded-md bg-zinc-900/[0.06] px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-zinc-500 dark:bg-white/10 dark:text-zinc-300">
                      {t.tagline}
                    </span>
                  </span>
                  <span className="mt-0.5 block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
                    {t.detail}
                  </span>
                  <span className="mt-1 inline-flex items-center gap-1 text-[10.5px] font-semibold uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
                    <Zap className="size-3" aria-hidden />
                    {t.motion} motion
                  </span>
                </span>
                <AnimatePresence initial={false}>
                  {selected ? (
                    <motion.span
                      key="check"
                      initial={reduced ? { opacity: 0 } : { scale: 0, opacity: 0, rotate: -30 }}
                      animate={reduced ? { opacity: 1 } : { scale: 1, opacity: 1, rotate: 0 }}
                      exit={reduced ? { opacity: 0 } : { scale: 0, opacity: 0 }}
                      transition={reduced ? { duration: 0 } : spring.bouncy}
                      className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full shadow-sm"
                      style={{ background: 'var(--ui-accent, #10b981)' }}
                    >
                      <Check className="size-3.5 text-white dark:text-zinc-950" aria-hidden />
                    </motion.span>
                  ) : null}
                </AnimatePresence>
              </motion.button>
            )
          })}
          <p className="px-2 pb-1 pt-2 text-[11.5px] leading-snug text-zinc-400 dark:text-zinc-500">
            Each language restyles every surface through its own design tokens — you are running{' '}
            <span className="font-semibold text-zinc-500 dark:text-zinc-400">{themeMeta.label}</span> with{' '}
            {themeMeta.motion} motion. Switching is instant, app-wide.
          </p>
        </div>
      ) : null}

      {modeVisible ? (
        <div className={cn(PANEL, 'p-3')}>
          <StaticRow
            Icon={Monitor}
            title="Light / dark mode"
            caption="Color scheme for the whole app — System follows your device."
            search="dark mode light mode system color scheme"
          />
          <div className="p-3 pt-2">
            <Segmented
              ariaLabel="Color theme"
              value={themeValue}
              onChange={setTheme}
              options={[
                { value: 'light', label: 'Light', Icon: Sun },
                { value: 'dark', label: 'Dark', Icon: Moon },
                { value: 'system', label: 'System', Icon: Monitor },
              ]}
            />
          </div>
        </div>
      ) : null}
    </Section>
  )
}

// ── Navigation — all thirteen architectures ──────────────────

function NavigationSection({
  navStyle,
  setNavStyle,
  navMeta,
}: {
  navStyle: NavStyleId
  setNavStyle: (s: NavStyleId) => void
  navMeta: NavStyleMeta | undefined
}) {
  const q = useSearchQuery()
  const reduced = useReducedMotion()

  const sectionVisible = hit(
    q,
    'navigation',
    'nav style',
    'dock',
    'tab bar',
    'bottom bar',
    'rail',
    'gesture',
    'island',
    'radial',
    'pill',
    'capsule',
    'command bar',
    ...NAV_STYLE_META.map((s) => `${s.label} ${s.hint} ${s.zone}`),
  )
  if (!sectionVisible) return null

  return (
    <Section label="Navigation" show>
      <div className={cn(PANEL, 'p-2.5')} role="radiogroup" aria-label="Navigation style">
        {NAV_STYLE_META.map((s) => {
          const selected = navStyle === s.id
          if (!hit(q, 'navigation', 'nav style', s.label, s.hint, s.zone)) return null
          const Icon = NAV_ICONS[s.id]
          return (
            <motion.button
              key={s.id}
              type="button"
              role="radio"
              aria-checked={selected}
              onClick={() => {
                haptic(10)
                setNavStyle(s.id)
              }}
              whileTap={reduced ? undefined : pressTap}
              className={cn(
                'flex min-h-[56px] w-full items-center gap-3 rounded-2xl border border-transparent px-3 py-2.5 text-left outline-none',
                'transition-colors hover:bg-zinc-50/80 focus-visible:ring-2 focus-visible:ring-emerald-500/60 dark:hover:bg-zinc-800/40',
                selected && 'bg-zinc-900/[0.035] dark:bg-white/[0.05]',
              )}
              style={
                selected
                  ? { borderColor: 'var(--ui-accent, #10b981)', boxShadow: '0 0 0 1px var(--ui-accent, #10b981)' }
                  : undefined
              }
            >
              <IconTile Icon={Icon} />
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-2">
                  <span className="truncate text-[14.5px] font-semibold text-zinc-900 dark:text-zinc-100">
                    {s.label}
                  </span>
                  <span
                    className={cn(
                      'shrink-0 rounded-md px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide',
                      ZONE_BADGE[s.zone],
                    )}
                  >
                    {s.zone}
                  </span>
                </span>
                <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">{s.hint}</span>
              </span>
              <AnimatePresence initial={false}>
                {selected ? (
                  <motion.span
                    key="check"
                    initial={reduced ? { opacity: 0 } : { scale: 0, opacity: 0 }}
                    animate={reduced ? { opacity: 1 } : { scale: 1, opacity: 1 }}
                    exit={reduced ? { opacity: 0 } : { scale: 0, opacity: 0 }}
                    transition={reduced ? { duration: 0 } : spring.bouncy}
                    className="flex size-5 shrink-0 items-center justify-center rounded-full"
                    style={{ background: 'var(--ui-accent, #10b981)' }}
                  >
                    <Check className="size-3 text-white dark:text-zinc-950" aria-hidden />
                  </motion.span>
                ) : null}
              </AnimatePresence>
            </motion.button>
          )
        })}
      </div>
      <p className="px-1 text-[11.5px] leading-snug text-zinc-400 dark:text-zinc-500">
        Currently active:{' '}
        <span className="font-semibold text-zinc-500 dark:text-zinc-400">
          {navMeta?.label ?? 'Floating Capsule'}
        </span>{' '}
        — switching applies instantly to the shell navigation.
      </p>
    </Section>
  )
}

// ── Notifications — real alert gates + quiet hours ───────────

function NotificationsSection({ prefs, save }: { prefs: PulsePrefs; save: (patch: Partial<PulsePrefs>) => void }) {
  const q = useSearchQuery()

  const soundOn = pulseSettingsStore((s) => s.soundOn)
  const hapticsOn = pulseSettingsStore((s) => s.hapticsOn)
  const quietHoursOn = pulseSettingsStore((s) => s.quietHoursOn)
  const quietStart = pulseSettingsStore((s) => s.quietStart)
  const quietEnd = pulseSettingsStore((s) => s.quietEnd)
  const setSoundOn = pulseSettingsStore((s) => s.setSoundOn)
  const setHapticsOn = pulseSettingsStore((s) => s.setHapticsOn)
  const setQuietHoursOn = pulseSettingsStore((s) => s.setQuietHoursOn)
  const setQuietStart = pulseSettingsStore((s) => s.setQuietStart)
  const setQuietEnd = pulseSettingsStore((s) => s.setQuietEnd)

  const alertsVisible = hit(q, 'notifications', 'sound', 'haptics', 'vibration', 'quiet hours', 'do not disturb', 'ding', 'alert', 'test')
  const inappVisible = hit(q, 'notifications', 'previews', 'message previews', 'pop', 'vibrate', 'banner')
  if (!alertsVisible && !inappVisible) return null

  const timeInputClass =
    'h-11 w-full rounded-xl border border-zinc-200/80 bg-white/60 px-3 text-[13.5px] font-semibold text-zinc-800 outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60 [color-scheme:light] dark:border-zinc-700/60 dark:bg-zinc-800/60 dark:text-zinc-100 dark:[color-scheme:dark]'

  return (
    <Section label="Notifications" show>
      {alertsVisible ? (
        <div className={cn(PANEL, DIVIDE)}>
          <ToggleRow
            Icon={Volume2}
            title="Incoming sound"
            description="Master ding for new messages on this device."
            checked={soundOn}
            onCheckedChange={setSoundOn}
            search="sound ding alert audio ping"
          />
          <ToggleRow
            Icon={Vibrate}
            title="Haptics"
            description="Vibration feedback on taps and incoming messages."
            checked={hapticsOn}
            onCheckedChange={setHapticsOn}
            search="haptics vibration buzz"
          />
          <ToggleRow
            Icon={MoonStar}
            title="Quiet hours"
            description="Silence sounds and vibration inside the window."
            checked={quietHoursOn}
            onCheckedChange={setQuietHoursOn}
            search="quiet hours do not disturb dnd silence schedule overnight"
          />
          {quietHoursOn ? (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={prefs.reducedMotion ? { duration: 0 } : undefined}
              className="flex flex-wrap items-end gap-3 px-4 pb-4 pt-1"
            >
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
            </motion.div>
          ) : null}
          <div className="px-4 pb-4 pt-0.5">
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                haptic(30)
                playIncomingPing()
              }}
              className="h-11 w-full gap-2 rounded-xl border-zinc-200/80 text-[13px] font-semibold text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700/60 dark:text-zinc-200 dark:hover:bg-zinc-800/60"
            >
              <Play className="size-4" aria-hidden />
              Preview alert
            </Button>
            <p className="pt-1.5 text-[11.5px] leading-snug text-zinc-400 dark:text-zinc-500">
              Plays the real two-note ding and fires a haptic buzz. Overnight windows (22:00 → 07:00) are supported.
            </p>
          </div>
        </div>
      ) : null}

      {inappVisible ? (
        <div className={cn(PANEL, DIVIDE)}>
          <ToggleRow
            Icon={Eye}
            title="Message previews"
            description="Show message text in notification banners."
            checked={prefs.notifPreviews}
            onCheckedChange={(v) => save({ notifPreviews: v })}
            search="previews banner text privacy lock screen"
          />
          <ToggleRow
            Icon={Bell}
            title="Message pop"
            description="Per-account pop sound for incoming messages."
            checked={prefs.notifSound}
            onCheckedChange={(v) => save({ notifSound: v })}
            search="pop sound per account"
          />
          <ToggleRow
            Icon={Smartphone}
            title="Vibration"
            description="Buzz on incoming messages, where the device supports it."
            checked={prefs.notifVibrate}
            onCheckedChange={(v) => save({ notifVibrate: v })}
            search="vibrate buzz device"
          />
        </div>
      ) : null}
    </Section>
  )
}

// ── Chats — list filter + per-account chat prefs ─────────────

function ChatsSection({ prefs, save }: { prefs: PulsePrefs; save: (patch: Partial<PulsePrefs>) => void }) {
  const q = useSearchQuery()

  const listFilter = pulseSettingsStore((s) => s.listFilter)
  const setListFilter = pulseSettingsStore((s) => s.setListFilter)

  const filterVisible = hit(q, 'chats', 'list filter', 'unread', 'groups', 'folders', 'default filter')
  const lookVisible = hit(q, 'chats', 'wallpaper', 'bubble corners', 'density', 'compact', 'cozy', 'radius', 'background')
  if (!filterVisible && !lookVisible) return null

  const wallpaperLabel = WALLPAPERS.find((w) => w.id === prefs.wallpaper)?.label ?? 'None'

  return (
    <Section label="Chats" show>
      {filterVisible ? (
        <div className={cn(PANEL, 'p-3')}>
          <StaticRow
            Icon={MessagesSquare}
            title="Default list filter"
            caption="Applied to the chats list when you open the Chats tab."
            search="filter unread groups all folders"
            trailing={
              <span className="text-[11px] font-bold uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
                {listFilter}
              </span>
            }
          />
          <div className="p-3 pt-2">
            <Segmented
              ariaLabel="Default chats list filter"
              value={listFilter}
              onChange={(v) => {
                haptic(8)
                setListFilter(v as ChatsListFilter)
              }}
              options={[
                { value: 'all', label: 'All' },
                { value: 'unread', label: 'Unread' },
                { value: 'groups', label: 'Groups' },
              ]}
            />
          </div>
        </div>
      ) : null}

      {lookVisible ? (
        <div className={cn(PANEL, 'p-3')}>
          <StaticRow
            Icon={ImageIcon}
            title="Chat wallpaper"
            caption={`Background behind every chat room — currently ${wallpaperLabel}.`}
            search="wallpaper background aurora dusk forest mono"
          />
          <div className="grid grid-cols-5 gap-2 p-3 pt-1" role="radiogroup" aria-label="Chat wallpaper">
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
                        : 'border-zinc-200/80 dark:border-zinc-700/60',
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

          <div className="px-3 pb-1">
            <StaticRow
              Icon={MessagesSquare}
              title="Bubble corners"
              caption="Corner radius of your outgoing message bubbles."
              search="bubble radius corners pill round"
            />
            <div className="pb-3 pt-2">
              <Segmented
                ariaLabel="Bubble corner radius"
                value={prefs.bubbleRadius}
                onChange={(v) => save({ bubbleRadius: v })}
                options={[
                  { value: 'md', label: 'Medium' },
                  { value: 'lg', label: 'Large' },
                  { value: 'pill', label: 'Pill' },
                ]}
              />
            </div>

            <StaticRow
              Icon={Columns3}
              title="Message density"
              caption="Row spacing in the message list."
              search="density compact cozy spacing"
            />
            <div className="pt-2">
              <Segmented
                ariaLabel="Message density"
                value={prefs.density}
                onChange={(v) => save({ density: v })}
                options={[
                  { value: 'cozy', label: 'Cozy' },
                  { value: 'compact', label: 'Compact' },
                ]}
              />
            </div>
          </div>
        </div>
      ) : null}
    </Section>
  )
}

// ── Privacy & Security — the real synced controls ────────────

function PrivacySection({ prefs, save }: { prefs: PulsePrefs; save: (patch: Partial<PulsePrefs>) => void }) {
  const q = useSearchQuery()
  const visible = hit(q, 'privacy', 'security', 'last seen', 'online', 'read receipts', 'visibility', 'presence')
  if (!visible) return null
  return (
    <Section label="Privacy & Security" show>
      <div className={cn(PANEL, DIVIDE)}>
        <ToggleRow
          Icon={Eye}
          title="Last seen & online"
          description="Let people see when you were last active on Pulse."
          checked={prefs.lastSeenVisible}
          onCheckedChange={(v) => save({ lastSeenVisible: v })}
          search="last seen online presence visibility"
        />
        <ToggleRow
          Icon={CheckCheck}
          title="Read receipts"
          description="Show others when you've read their messages."
          checked={prefs.readReceipts}
          onCheckedChange={(v) => save({ readReceipts: v })}
          search="read receipts seen ticks double check"
        />
      </div>
      <p className="px-1 text-[11.5px] leading-snug text-zinc-400 dark:text-zinc-500">
        These are the privacy controls Pulse syncs to your account today. End-to-end encryption and
        per-chat locks are on the roadmap — nothing else is configurable yet.
      </p>
    </Section>
  )
}

// ── Data & Storage — real counts, outbox, install ────────────

function StatTile({ Icon, value, label }: { Icon: LucideIcon; value: number | string; label: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-2xl border border-zinc-200/70 bg-white/60 p-3 dark:border-zinc-700/50 dark:bg-zinc-900/50">
      <Icon className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
      <p className="text-xl font-bold leading-none tracking-tight text-zinc-900 dark:text-zinc-50">{value}</p>
      <p className="text-[10.5px] font-medium leading-tight text-zinc-500 dark:text-zinc-400">{label}</p>
    </div>
  )
}

function DataStorageSection({
  user,
  stats,
  mounted,
  reduced,
  onOpenHub,
}: {
  user: AppUser | null
  stats: ReturnType<typeof useQuery<UserStats>>
  mounted: boolean
  reduced: boolean
  onOpenHub?: () => void
}) {
  const q = useSearchQuery()
  const installEvent = usePulsePwa((s) => s.installEvent)
  const queuedCount = useStore(pulseOutboxStore, (s) => s.queue.length)

  const statsVisible = hit(q, 'data', 'storage', 'stats', 'footprint', 'messages', 'photos', 'voice notes', 'chats', 'groups', 'days', 'activity')
  const offlineVisible = hit(q, 'offline', 'queue', 'outbox', 'waiting', 'connection')
  const installVisible = hit(q, 'install', 'pwa', 'home screen', 'app')
  const hubVisible = hit(q, 'hub', 'wallet', 'market', 'economy')
  if (!statsVisible && !offlineVisible && !installVisible && !hubVisible) return null

  const handleInstall = async () => {
    haptic(12)
    const outcome = await promptPwaInstall()
    if (outcome === 'accepted') {
      toast.success('Installing Pulse…', { description: 'Find it on your home screen.' })
    } else if (outcome === 'unavailable') {
      toast.error('Install is not available right now')
    }
  }

  return (
    <Section label="Data & Storage" show>
      {statsVisible ? (
        <div className={cn(PANEL, 'p-3')}>
          <StaticRow
            Icon={Database}
            title="Your footprint"
            caption="Live counts straight from the Pulse database."
            search="stats footprint messages photos activity"
            trailing={
              <Button
                variant="ghost"
                size="icon"
                aria-label="Refresh stats"
                onClick={() => void stats.refetch()}
                className="size-9 rounded-full text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800"
              >
                <RefreshCw className={cn('size-4', stats.isFetching && 'animate-spin')} aria-hidden />
              </Button>
            }
          />
          {!user ? (
            <div className="flex items-center gap-3 px-4 pb-4">
              <TriangleAlert className="size-5 shrink-0 text-amber-500" aria-hidden />
              <p className="text-[13px] font-medium text-zinc-600 dark:text-zinc-300">Sign in to see your stats.</p>
            </div>
          ) : stats.isPending ? (
            <div className="grid grid-cols-3 gap-2 p-3 pt-1" role="status" aria-label="Loading stats">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-[92px] rounded-2xl" />
              ))}
            </div>
          ) : stats.isError || !stats.data ? (
            <div className="flex flex-col items-start gap-2 px-4 pb-4">
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
            <div className="grid grid-cols-3 gap-2 p-3 pt-1">
              <StatTile Icon={MessagesSquare} value={stats.data.messages} label="Messages sent" />
              <StatTile Icon={ImageIcon} value={stats.data.photos} label="Photos" />
              <StatTile Icon={Mic} value={stats.data.voiceNotes} label="Voice notes" />
              <StatTile Icon={UserRound} value={stats.data.chats} label="Chats" />
              <StatTile Icon={UsersRound} value={stats.data.groups} label="Groups" />
              <StatTile Icon={CalendarDays} value={stats.data.days} label="Days active" />
            </div>
          )}
        </div>
      ) : null}

      {offlineVisible ? (
        <div className={cn(PANEL)}>
          <StaticRow
            Icon={CloudOff}
            title="Offline queue"
            caption={
              mounted && queuedCount > 0
                ? `${queuedCount} ${queuedCount === 1 ? 'message' : 'messages'} waiting to send when you're back online.`
                : 'Empty — every composed message has been delivered.'
            }
            search="offline queue outbox waiting messages sync"
            trailing={
              mounted && queuedCount > 0 ? (
                <span className="shrink-0 rounded-full bg-amber-500/10 px-2.5 py-1 text-[11px] font-bold text-amber-600 dark:text-amber-400">
                  {queuedCount}
                </span>
              ) : (
                <Check className="size-4 shrink-0 text-emerald-500" aria-hidden />
              )
            }
          />
        </div>
      ) : null}

      {installVisible ? (
        <div className={cn(PANEL)}>
          {mounted && installEvent ? (
            <motion.button
              type="button"
              onClick={() => void handleInstall()}
              whileTap={reduced ? undefined : pressTap}
              className="flex min-h-[56px] w-full items-center gap-3 px-4 py-2.5 text-left outline-none transition-colors hover:bg-zinc-50/80 active:bg-zinc-100 dark:hover:bg-zinc-800/40 dark:active:bg-zinc-800/70"
            >
              <IconTile Icon={Download} />
              <span className="min-w-0 flex-1">
                <span className="block text-[14.5px] font-semibold text-zinc-900 dark:text-zinc-100">
                  Install Pulse
                </span>
                <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">
                  Add to your home screen — opens instantly, works offline.
                </span>
              </span>
              <span className="shrink-0 rounded-full bg-emerald-600 px-3.5 py-1.5 text-[12px] font-bold text-white shadow-sm shadow-emerald-600/25">
                Install
              </span>
            </motion.button>
          ) : (
            <StaticRow
              Icon={Smartphone}
              title="Install Pulse"
              caption="Your browser hasn't offered the install prompt yet — check its menu (Share → Add to Home Screen)."
              search="install pwa home screen add"
            />
          )}
        </div>
      ) : null}

      {hubVisible && onOpenHub ? (
        <div className={cn(PANEL)}>
          <NavHintRow
            Icon={Compass}
            title="The Hub"
            caption="Wallet · Tasks · Market · Swap · Apps · Logs"
            onClick={onOpenHub}
            search="hub wallet economy market tasks"
          />
        </div>
      ) : null}
    </Section>
  )
}

/** Tappable hint row (used sparingly — most rows are static or toggles). */
function NavHintRow({
  Icon,
  title,
  caption,
  onClick,
  search,
}: {
  Icon: LucideIcon
  title: string
  caption?: string
  onClick: () => void
  search?: string
}) {
  const q = useSearchQuery()
  const reduced = useReducedMotion()
  if (!hit(q, title, caption, search)) return null
  return (
    <motion.button
      type="button"
      onClick={() => {
        haptic(10)
        onClick()
      }}
      whileTap={reduced ? undefined : pressTap}
      className="flex min-h-[56px] w-full items-center gap-3 px-4 py-2.5 text-left outline-none transition-colors hover:bg-zinc-50/80 active:bg-zinc-100 dark:hover:bg-zinc-800/40 dark:active:bg-zinc-800/70"
    >
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14.5px] font-semibold text-zinc-900 dark:text-zinc-100">{title}</span>
        {caption ? (
          <span className="block truncate text-[12px] text-zinc-500 dark:text-zinc-400">{caption}</span>
        ) : null}
      </span>
      <ChevronRight className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
    </motion.button>
  )
}

// ── System ───────────────────────────────────────────────────

function SystemSection({ prefs, save }: { prefs: PulsePrefs; save: (patch: Partial<PulsePrefs>) => void }) {
  const q = useSearchQuery()
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine,
  )

  useEffect(() => {
    const up = () => setOnline(true)
    const down = () => setOnline(false)
    window.addEventListener('online', up)
    window.addEventListener('offline', down)
    return () => {
      window.removeEventListener('online', up)
      window.removeEventListener('offline', down)
    }
  }, [])

  const motionVisible = hit(q, 'system', 'reduced motion', 'accessibility', 'animation', 'calm')
  const connectionVisible = hit(q, 'system', 'connection', 'online', 'offline', 'network', 'realtime')
  const versionVisible = hit(q, 'system', 'version', 'build', 'update')
  if (!motionVisible && !connectionVisible && !versionVisible) return null

  return (
    <Section label="System" show>
      {motionVisible ? (
        <div className={cn(PANEL)}>
          <ToggleRow
            Icon={Accessibility}
            title="Reduced motion"
            description="Calm the interface down — instant transitions, no bounces."
            checked={prefs.reducedMotion}
            onCheckedChange={(v) => save({ reducedMotion: v })}
            search="reduced motion accessibility calm animations parallax"
          />
        </div>
      ) : null}
      {connectionVisible ? (
        <div className={cn(PANEL)}>
          <StaticRow
            Icon={online ? ShieldCheck : TriangleAlert}
            title={online ? 'Connected' : 'Offline'}
            caption={
              online
                ? 'Realtime socket and API reachable — messages send instantly.'
                : 'You are offline — outgoing messages queue in the offline outbox.'
            }
            search="connection online offline network socket realtime status"
            trailing={
              <span
                className={cn(
                  'shrink-0 rounded-full px-2.5 py-1 text-[11px] font-bold',
                  online
                    ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                    : 'bg-rose-500/10 text-rose-600 dark:text-rose-400',
                )}
              >
                {online ? 'Online' : 'Offline'}
              </span>
            }
          />
        </div>
      ) : null}
      {versionVisible ? (
        <div className={cn(PANEL)}>
          <StaticRow
            Icon={Info}
            title="Version"
            caption={`Pulse ${PULSE_VERSION} — see About below for build details.`}
            search="version build release"
          />
        </div>
      ) : null}
    </Section>
  )
}

// ── About ────────────────────────────────────────────────────

function AboutSection() {
  const q = useSearchQuery()
  const visible = hit(
    q,
    'about',
    'version',
    'credits',
    'framework',
    'next.js',
    'prisma',
    'socket.io',
    'made',
    'pulse',
    'build',
  )
  if (!visible) return null
  return (
    <Section label="About" show>
      <div className={cn(PANEL, 'p-4')}>
        <div className="flex items-center gap-3">
          <span className="flex size-11 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-md shadow-emerald-500/25">
            <Sparkles className="size-5" aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-[15px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">Pulse</p>
            <p className="text-[12px] text-zinc-500 dark:text-zinc-400">Real-time chat with a built-in economy</p>
          </div>
        </div>
        <p className="pt-3 text-[12.5px] leading-relaxed text-zinc-500 dark:text-zinc-400">
          Chats, wallet, market, games, topics, stages and more — every feature runs on the real
          Pulse API with zero mock data. Pick a UI language and a navigation style above; the whole
          app follows your choice instantly.
        </p>
      </div>

      <div className={cn(PANEL, DIVIDE)}>
        <StaticRow Icon={Info} title="Version" caption={PULSE_VERSION} search="version release build number" />
        <StaticRow
          Icon={Component}
          title="Framework"
          caption="Next.js 16 · App Router · TypeScript"
          search="framework next.js typescript app router"
        />
        <StaticRow
          Icon={Command}
          title="Realtime"
          caption="socket.io mini service on port 3003"
          search="realtime socket.io websocket service"
        />
        <StaticRow
          Icon={Database}
          title="Data"
          caption="Prisma ORM + SQLite, zero mock data"
          search="data prisma sqlite database"
        />
      </div>

      <div className="flex flex-col items-center gap-1.5 pb-2 pt-2">
        <Heart className="size-4 fill-emerald-500 text-emerald-500" aria-hidden />
        <p className="text-[13px] font-semibold text-zinc-600 dark:text-zinc-300">Made with Pulse</p>
        <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
          Version {PULSE_VERSION} · chats, hub economy and settings sync live
        </p>
      </div>
    </Section>
  )
}

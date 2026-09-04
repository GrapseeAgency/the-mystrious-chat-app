// ─────────────────────────────────────────────────────────────
// Pulse Chat — SettingsScreen: Telegram-style liquid-glass
// settings tree with Linear-grade rows and spring level slides.
// Every control persists INSTANTLY through usePrefs().save
// (optimistic zustand + debounced PATCH /api/settings → the REAL
// User.preferences JSON in SQLite). Storage numbers come from
// GET /api/users/[id]/stats — real counts only.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useState } from 'react'
import { AnimatePresence, motion, type Variants } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import {
  Accessibility,
  ArrowLeftRight,
  Bell,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Coins,
  Compass,
  Database,
  Eye,
  Flame,
  Heart,
  Image as ImageIcon,
  Info,
  LayoutGrid,
  ListTodo,
  MessageSquare,
  MessagesSquare,
  Mic,
  Monitor,
  Moon,
  Palette,
  RefreshCw,
  ScrollText,
  ShieldCheck,
  Store,
  Sun,
  TriangleAlert,
  UsersRound,
  Vibrate,
  Volume2,
  X,
  type LucideIcon,
} from 'lucide-react'
import type { AppUser, UserStats } from '@/lib/types'
import { apiJson, formatListStamp, formatMemberSince } from '@/lib/pulse-utils'
import { usePrefs, usePrefsValues } from '@/lib/prefs'
import type { PulsePrefs } from '@/lib/prefs-defaults'
import { usePulseSession } from '@/lib/pulse-store'
import { NAV_STYLE_META, useNavStyle } from '@/components/chat/nav-router'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { UserAvatar } from '@/components/chat/user-avatar'

/** Build metadata — mirrored by hand from package.json (version is not an import). */
const PULSE_VERSION = '0.2.1'

type SettingsLevel = 'root' | 'appearance' | 'notifications' | 'privacy' | 'storage' | 'hub' | 'about'

const LEVEL_TITLE: Record<SettingsLevel, string> = {
  root: 'Settings',
  appearance: 'Appearance',
  notifications: 'Notifications',
  privacy: 'Privacy',
  storage: 'Storage & Activity',
  hub: 'The Hub',
  about: 'About',
}

const LEVEL_DEPTH: Record<SettingsLevel, number> = {
  root: 0,
  appearance: 1,
  notifications: 1,
  privacy: 1,
  storage: 1,
  hub: 1,
  about: 1,
}

const GLASS =
  'rounded-2xl border border-zinc-200/80 bg-white/70 shadow-sm backdrop-blur-xl dark:border-zinc-700/60 dark:bg-zinc-900/60'

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

// ── level slide variants (push / pop by depth direction) ────

const levelVariants: Variants = {
  enter: (d: number) => ({ x: d * 36, opacity: 0 }),
  center: { x: 0, opacity: 1 },
  exit: (d: number) => ({ x: d * -28, opacity: 0 }),
}

// ── small building blocks ────────────────────────────────────

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="px-1 pb-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-zinc-400 dark:text-zinc-500">
      {children}
    </p>
  )
}

function FieldBlock({
  title,
  hint,
  children,
}: {
  title: string
  hint?: string
  children: React.ReactNode
}) {
  return (
    <section className="pb-5">
      <SectionLabel>{title}</SectionLabel>
      <div className={cn(GLASS, 'p-3')}>{children}</div>
      {hint ? <p className="px-1 pt-1.5 text-[11.5px] leading-snug text-zinc-400 dark:text-zinc-500">{hint}</p> : null}
    </section>
  )
}

function IconTile({ Icon }: { Icon: LucideIcon }) {
  return (
    <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10 dark:bg-emerald-400/10">
      <Icon className="size-[18px] text-emerald-600 dark:text-emerald-400" aria-hidden />
    </span>
  )
}

function NavRow({
  Icon,
  title,
  caption,
  onClick,
}: {
  Icon: LucideIcon
  title: string
  caption?: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
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
    </button>
  )
}

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
}: {
  Icon: LucideIcon
  title: string
  description: string
  checked: boolean
  onCheckedChange: (v: boolean) => void
}) {
  return (
    <label className="flex min-h-[56px] cursor-pointer items-center gap-3 px-4 py-2.5">
      <IconTile Icon={Icon} />
      <span className="min-w-0 flex-1">
        <span className="block text-[14.5px] font-semibold text-zinc-900 dark:text-zinc-100">{title}</span>
        <span className="block text-[12px] leading-snug text-zinc-500 dark:text-zinc-400">{description}</span>
      </span>
      <Switch checked={checked} onCheckedChange={onCheckedChange} aria-label={title} />
    </label>
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
  const { theme, setTheme } = useTheme()
  const [navStyle] = useNavStyle()

  const [level, setLevel] = useState<SettingsLevel>('root')
  const [dir, setDir] = useState(1)

  const reduced = prefs.reducedMotion
  const spring = reduced ? { duration: 0 } : { type: 'spring' as const, stiffness: 420, damping: 36 }

  const go = (next: SettingsLevel) => {
    setDir(LEVEL_DEPTH[next] >= LEVEL_DEPTH[level] ? 1 : -1)
    setLevel(next)
  }

  // real counts from the live DB — fetched only while the panel is open
  const stats = useQuery({
    queryKey: ['user-stats', user?.id ?? '-'],
    enabled: open && level === 'storage' && !!user,
    staleTime: 15_000,
    queryFn: async (): Promise<UserStats> => {
      const res = await apiJson<{ stats: UserStats }>(`/api/users/${encodeURIComponent(user?.id ?? '')}/stats`)
      return res.stats
    },
  })

  const navMeta = useMemo(() => NAV_STYLE_META.find((m) => m.id === navStyle), [navStyle])

  const themeValue = theme === 'light' || theme === 'dark' ? theme : 'system'

  if (!open) return null

  const statusLine =
    user !== null && (user.statusEmoji !== null || user.statusText !== null)
      ? `${user.statusEmoji ?? ''} ${user.statusText ?? ''}`.trim()
      : (user?.about ?? '')

  const notifCaption = `Sound ${prefs.notifSound ? 'on' : 'off'} · previews ${prefs.notifPreviews ? 'on' : 'off'}`
  const privacyCaption = `Last seen ${prefs.lastSeenVisible ? 'visible' : 'hidden'} · receipts ${prefs.readReceipts ? 'on' : 'off'}`
  const themeLabel = themeValue === 'system' ? 'System' : themeValue === 'dark' ? 'Dark' : 'Light'
  const wallpaperLabel = WALLPAPERS.find((w) => w.id === prefs.wallpaper)?.label ?? 'None'
  const appearanceCaption = `${themeLabel} · ${wallpaperLabel} wallpaper`

  return (
    <motion.div
      initial={reduced ? { opacity: 0 } : { opacity: 0, y: 28, scale: 0.99 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: 18, transition: { duration: 0.14 } }}
      transition={spring}
      className="absolute inset-0 z-[70] bg-background"
      role="dialog"
      aria-modal="true"
      aria-label="Settings"
    >
      <div className="flex h-full flex-col">
        {/* header */}
        <div className="flex h-14 shrink-0 items-center gap-1 border-b border-zinc-200/70 px-2 dark:border-zinc-800/70">
          {level === 'root' ? (
            <>
              <h2 className="pl-3 text-[17px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">Settings</h2>
              <div className="flex-1" />
              <Button
                variant="ghost"
                size="icon"
                aria-label="Close settings"
                onClick={onClose}
                className="size-10 rounded-full text-zinc-500 hover:bg-zinc-100 active:scale-95 dark:hover:bg-zinc-800"
              >
                <X className="size-[18px]" aria-hidden />
              </Button>
            </>
          ) : (
            <>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Back to settings"
                onClick={() => go('root')}
                className="size-10 rounded-full text-zinc-500 hover:bg-zinc-100 active:scale-95 dark:hover:bg-zinc-800"
              >
                <ChevronLeft className="size-5" aria-hidden />
              </Button>
              <h2 className="text-[16px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                {LEVEL_TITLE[level]}
              </h2>
            </>
          )}
        </div>

        {/* levels */}
        <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-12 pt-4">
          <AnimatePresence mode="wait" custom={dir} initial={false}>
            <motion.div
              key={level}
              custom={dir}
              variants={levelVariants}
              initial="enter"
              animate="center"
              exit="exit"
              transition={spring}
              className="min-h-full"
            >
              {level === 'root' ? (
                <RootLevel
                  user={user}
                  statusLine={statusLine}
                  appearanceCaption={appearanceCaption}
                  notifCaption={notifCaption}
                  privacyCaption={privacyCaption}
                  onOpenLevel={go}
                />
              ) : null}

              {level === 'appearance' ? (
                <AppearanceLevel
                  prefs={prefs}
                  save={save}
                  themeValue={themeValue}
                  setTheme={setTheme}
                  navMeta={navMeta}
                />
              ) : null}

              {level === 'notifications' ? (
                <div className={cn(GLASS, 'overflow-hidden')}>
                  <ToggleRow
                    Icon={Eye}
                    title="Message previews"
                    description="Show message text in notification banners."
                    checked={prefs.notifPreviews}
                    onCheckedChange={(v) => save({ notifPreviews: v })}
                  />
                  <ToggleRow
                    Icon={Volume2}
                    title="Notification sound"
                    description="Play a soft pop for incoming messages."
                    checked={prefs.notifSound}
                    onCheckedChange={(v) => save({ notifSound: v })}
                  />
                  <ToggleRow
                    Icon={Vibrate}
                    title="Vibration"
                    description="Buzz on incoming messages, where the device supports it."
                    checked={prefs.notifVibrate}
                    onCheckedChange={(v) => save({ notifVibrate: v })}
                  />
                </div>
              ) : null}

              {level === 'privacy' ? (
                <div className={cn(GLASS, 'overflow-hidden')}>
                  <ToggleRow
                    Icon={Clock3}
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
                </div>
              ) : null}

              {level === 'storage' ? <StorageLevel user={user} stats={stats} /> : null}

              {level === 'hub' ? <HubLevel onOpenHub={onOpenHub} /> : null}

              {level === 'about' ? <AboutLevel /> : null}
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </motion.div>
  )
}

// ── root list ────────────────────────────────────────────────

function RootLevel({
  user,
  statusLine,
  appearanceCaption,
  notifCaption,
  privacyCaption,
  onOpenLevel,
}: {
  user: AppUser | null
  statusLine: string
  appearanceCaption: string
  notifCaption: string
  privacyCaption: string
  onOpenLevel: (l: SettingsLevel) => void
}) {
  return (
    <div className="flex flex-col gap-5">
      {/* account summary — read-only here; edit it in Profile */}
      <section>
        <SectionLabel>Account</SectionLabel>
        <div className={cn(GLASS, 'flex items-center gap-3.5 p-4')}>
          {user ? (
            <UserAvatar name={user.name} color={user.color} size={56} />
          ) : (
            <span className="size-14 rounded-full bg-zinc-200 dark:bg-zinc-700" aria-hidden />
          )}
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5">
              <span className="truncate text-[16px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                {user?.name ?? '…'}
              </span>
              <span className="shrink-0 rounded-md bg-emerald-500/10 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-emerald-600 dark:text-emerald-400">
                You
              </span>
            </p>
            <p className="truncate text-[12.5px] font-medium text-zinc-500 dark:text-zinc-400">
              {user?.username ? `@${user.username}` : 'No handle yet'}
            </p>
            {statusLine ? (
              <p className="truncate text-[12px] text-zinc-400 dark:text-zinc-500">{statusLine}</p>
            ) : null}
          </div>
        </div>
      </section>

      <section>
        <SectionLabel>Preferences</SectionLabel>
        <div className={cn(GLASS, 'divide-y divide-zinc-200/70 overflow-hidden dark:divide-zinc-700/50')}>
          <NavRow Icon={Palette} title="Appearance" caption={appearanceCaption} onClick={() => onOpenLevel('appearance')} />
          <NavRow Icon={Bell} title="Notifications" caption={notifCaption} onClick={() => onOpenLevel('notifications')} />
          <NavRow Icon={ShieldCheck} title="Privacy" caption={privacyCaption} onClick={() => onOpenLevel('privacy')} />
        </div>
      </section>

      <section>
        <SectionLabel>Data</SectionLabel>
        <div className={cn(GLASS, 'divide-y divide-zinc-200/70 overflow-hidden dark:divide-zinc-700/50')}>
          <NavRow
            Icon={Database}
            title="Storage & Activity"
            caption="Messages, media and your Pulse footprint"
            onClick={() => onOpenLevel('storage')}
          />
          <NavRow
            Icon={Flame}
            title="The Hub"
            caption="Wallet · Tasks · Market · Swap · Apps"
            onClick={() => onOpenLevel('hub')}
          />
        </div>
      </section>

      <section>
        <SectionLabel>System</SectionLabel>
        <div className={cn(GLASS, 'overflow-hidden')}>
          <NavRow Icon={Info} title="About" caption={`Version ${PULSE_VERSION}`} onClick={() => onOpenLevel('about')} />
        </div>
      </section>
    </div>
  )
}

// ── appearance ───────────────────────────────────────────────

function AppearanceLevel({
  prefs,
  save,
  themeValue,
  setTheme,
  navMeta,
}: {
  prefs: PulsePrefs
  save: (patch: Partial<PulsePrefs>) => void
  themeValue: 'light' | 'dark' | 'system'
  setTheme: (t: 'light' | 'dark' | 'system') => void
  navMeta: (typeof NAV_STYLE_META)[number] | undefined
}) {
  return (
    <div>
      <FieldBlock title="Theme">
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
      </FieldBlock>

      <FieldBlock title="Chat wallpaper" hint="Applied behind the message list in every chat room.">
        <div className="grid grid-cols-5 gap-2" role="radiogroup" aria-label="Chat wallpaper">
          {WALLPAPERS.map((w) => {
            const selected = prefs.wallpaper === w.id
            return (
              <button
                key={w.id}
                type="button"
                role="radio"
                aria-checked={selected}
                aria-label={`${w.label} wallpaper`}
                onClick={() => save({ wallpaper: w.id })}
                className={cn(
                  'flex flex-col items-center gap-1.5 rounded-xl p-1 outline-none transition-transform active:scale-95',
                  'focus-visible:ring-2 focus-visible:ring-emerald-500/60',
                )}
              >
                <span
                  className={cn(
                    'relative block aspect-square w-full rounded-lg border shadow-sm',
                    w.preview,
                    selected ? 'border-emerald-500 ring-2 ring-emerald-500/60' : 'border-zinc-200/80 dark:border-zinc-700/60',
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
      </FieldBlock>

      <FieldBlock title="Bubble corners">
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
      </FieldBlock>

      <FieldBlock title="Message density">
        <Segmented
          ariaLabel="Message density"
          value={prefs.density}
          onChange={(v) => save({ density: v })}
          options={[
            { value: 'cozy', label: 'Cozy' },
            { value: 'compact', label: 'Compact' },
          ]}
        />
      </FieldBlock>

      <FieldBlock title="Motion" hint="Springs, parallax and other non-essential animation app-wide.">
        <div className={cn(GLASS, '-m-1 overflow-hidden')}>
          <ToggleRow
            Icon={Accessibility}
            title="Reduced motion"
            description="Calm the interface down — instant transitions, no bounces."
            checked={prefs.reducedMotion}
            onCheckedChange={(v) => save({ reducedMotion: v })}
          />
        </div>
      </FieldBlock>

      <FieldBlock title="Navigation" hint="Change under Profile → Navigation.">
        <StaticRow
          Icon={Compass}
          title={navMeta?.label ?? 'Acrylic Dock'}
          caption={navMeta?.hint ?? 'Floating acrylic bottom dock (default)'}
        />
      </FieldBlock>
    </div>
  )
}

// ── storage (real stats only) ────────────────────────────────

function StatTile({ Icon, value, label }: { Icon: LucideIcon; value: number | string; label: string }) {
  return (
    <div className={cn(GLASS, 'flex flex-col gap-1 p-3')}>
      <Icon className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
      <p className="text-xl font-bold leading-none tracking-tight text-zinc-900 dark:text-zinc-50">{value}</p>
      <p className="text-[10.5px] font-medium leading-tight text-zinc-500 dark:text-zinc-400">{label}</p>
    </div>
  )
}

function StorageLevel({
  user,
  stats,
}: {
  user: AppUser | null
  stats: ReturnType<typeof useQuery<UserStats>>
}) {
  if (!user) {
    return (
      <div className={cn(GLASS, 'flex items-center gap-3 p-4')}>
        <TriangleAlert className="size-5 text-amber-500" aria-hidden />
        <p className="text-[13px] font-medium text-zinc-600 dark:text-zinc-300">Sign in to see your stats.</p>
      </div>
    )
  }

  if (stats.isPending) {
    return (
      <div className="grid grid-cols-3 gap-2" role="status" aria-label="Loading stats">
        {Array.from({ length: 6 }).map((_, i) => (
          <Skeleton key={i} className="h-[92px] rounded-2xl" />
        ))}
      </div>
    )
  }

  if (stats.isError || !stats.data) {
    return (
      <div className={cn(GLASS, 'flex flex-col items-start gap-2 p-4')}>
        <p className="flex items-center gap-2 text-[13px] font-medium text-zinc-600 dark:text-zinc-300">
          <TriangleAlert className="size-4 text-amber-500" aria-hidden />
          Couldn&apos;t load your stats.
        </p>
        <Button size="sm" variant="outline" onClick={() => void stats.refetch()} className="gap-1.5 rounded-xl">
          <RefreshCw className="size-3.5" aria-hidden />
          Try again
        </Button>
      </div>
    )
  }

  const s = stats.data
  return (
    <div className="flex flex-col gap-5">
      <div>
        <SectionLabel>Your footprint</SectionLabel>
        <div className="grid grid-cols-3 gap-2">
          <StatTile Icon={MessageSquare} value={s.messages} label="Messages sent" />
          <StatTile Icon={ImageIcon} value={s.photos} label="Photos" />
          <StatTile Icon={Mic} value={s.voiceNotes} label="Voice notes" />
          <StatTile Icon={MessagesSquare} value={s.chats} label="Chats" />
          <StatTile Icon={UsersRound} value={s.groups} label="Groups" />
          <StatTile Icon={CalendarDays} value={s.days} label="Days active" />
        </div>
      </div>

      <div>
        <SectionLabel>Account age</SectionLabel>
        <div className={cn(GLASS, 'overflow-hidden')}>
          <StaticRow
            Icon={CalendarDays}
            title={`Member since ${formatMemberSince(s.joinedAt)}`}
            caption={`Day ${s.days + 1} of your Pulse journey`}
          />
          <StaticRow Icon={Clock3} title="Last active" caption={formatListStamp(s.lastSeenAt)} />
        </div>
      </div>
    </div>
  )
}

// ── hub ──────────────────────────────────────────────────────

const HUB_PANELS: Array<{ Icon: LucideIcon; title: string; caption: string }> = [
  { Icon: Coins, title: 'Wallet', caption: 'PC & GEM balances, transfers, daily check-in streaks' },
  { Icon: ListTodo, title: 'Tasks', caption: 'A personal kanban board (to do · doing · done)' },
  { Icon: Store, title: 'Market', caption: 'Buy and sell listings with other Pulse users' },
  { Icon: ArrowLeftRight, title: 'Swap', caption: 'Exchange Pulse Coins ⇄ Gems at live rates' },
  { Icon: LayoutGrid, title: 'Apps', caption: 'The 100-app catalog with per-app nav styles' },
  { Icon: ScrollText, title: 'Logs', caption: 'A live terminal stream of economy events' },
]

function HubLevel({ onOpenHub }: { onOpenHub?: () => void }) {
  return (
    <div className="flex flex-col gap-5">
      <div className={cn(GLASS, 'p-4')}>
        <div className="flex items-center gap-3">
          <span className="flex size-11 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400 to-teal-600 text-white shadow-md shadow-emerald-500/25">
            <Flame className="size-5" aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-[15px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">The Hub</p>
            <p className="text-[12px] text-zinc-500 dark:text-zinc-400">Pulse&apos;s built-in economy & tools space</p>
          </div>
        </div>
        <p className="pt-3 text-[12.5px] leading-relaxed text-zinc-500 dark:text-zinc-400">
          Six panels live inside the Hub tab — a real wallet with ledgered transactions, a task board, a peer
          marketplace, a currency swap, the full app catalog and the streaming economy log. Everything below is
          powered by your actual account.
        </p>
      </div>

      <section>
        <SectionLabel>Panels</SectionLabel>
        <div className={cn(GLASS, 'divide-y divide-zinc-200/70 overflow-hidden dark:divide-zinc-700/50')}>
          {HUB_PANELS.map(({ Icon, title, caption }) => (
            <StaticRow key={title} Icon={Icon} title={title} caption={caption} />
          ))}
        </div>
      </section>

      {onOpenHub ? (
        <Button
          onClick={onOpenHub}
          className="h-11 w-full gap-2 rounded-2xl bg-emerald-600 text-[14px] font-semibold text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-700 active:scale-[0.98]"
        >
          <Flame className="size-4" aria-hidden />
          Open the Hub
        </Button>
      ) : (
        <p className="px-1 text-center text-[12px] text-zinc-400 dark:text-zinc-500">
          Find the Hub in the bottom navigation — the flame tab.
        </p>
      )}
    </div>
  )
}

// ── about ────────────────────────────────────────────────────

function AboutLevel() {
  return (
    <div className="flex flex-col gap-5">
      <section>
        <SectionLabel>Build</SectionLabel>
        <div className={cn(GLASS, 'divide-y divide-zinc-200/70 overflow-hidden dark:divide-zinc-700/50')}>
          <StaticRow Icon={Info} title="Pulse version" caption={PULSE_VERSION} />
          <StaticRow Icon={Palette} title="Framework" caption="Next.js 16 · App Router · TypeScript" />
          <StaticRow Icon={MessageSquare} title="Realtime" caption="socket.io mini service on port 3003" />
          <StaticRow Icon={Database} title="Data" caption="Prisma ORM + SQLite, zero mock data" />
        </div>
      </section>

      <div className="flex flex-col items-center gap-1.5 pb-2 pt-6">
        <Heart className="size-4 fill-emerald-500 text-emerald-500" aria-hidden />
        <p className="text-[13px] font-semibold text-zinc-600 dark:text-zinc-300">Made with Pulse</p>
        <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
          Version {PULSE_VERSION} · chats, hub economy and settings sync live
        </p>
      </div>
    </div>
  )
}

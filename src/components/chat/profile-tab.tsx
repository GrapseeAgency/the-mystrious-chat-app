// ─────────────────────────────────────────────────────────────
// Pulse — Profile tab (R25-b): social-media-grade profile.
//
// Structure: full-bleed hero (theme-accent cover + overlapping
// presence avatar + name/handle/badge + status & bio), real stats
// row (messages · rooms · coins · member-since), Customize editor,
// Appearance (dark mode + five UI languages), Navigation (13
// architectures), Library (saved messages), Preferences (sound /
// haptics / quiet hours), Data & Storage (PWA install + real
// browser storage), Account, About (real app version), Session
// (sign out with confirm).
//
// Real data only: /api/users/[id]/stats, /api/hub/wallet,
// PATCH /api/users/[id], /api/users/check-username,
// /api/users/[id]/saved, PWA install prompt, navigator.storage.
// Zero emojis — Lucide icons + framer-motion microinteractions.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import {
  AtSign,
  BadgeCheck,
  CalendarDays,
  Check,
  Coins,
  Copy,
  Database,
  Fingerprint,
  Image as ImageIcon,
  Info,
  LoaderCircle,
  LogOut,
  Mic,
  Moon,
  MoonStar,
  Pencil,
  Settings,
  Smartphone,
  Star,
  Sun,
  Volume2,
  Vibrate,
} from 'lucide-react'
import { toast } from 'sonner'
import { version as APP_VERSION } from '../../../package.json'
import type { AppUser, SavedItem, UserStats } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import {
  AVATAR_GRADIENTS,
  PULSE_COLORS,
  apiJson,
  formatMemberSince,
  type AvatarColor,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring } from '@/lib/motion'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { UserAvatar } from '@/components/chat/user-avatar'
import { SettingsScreen } from '@/components/chat/settings-screen'
import { pulseSettingsStore, haptic, isQuietHoursNow, primeSound } from '@/lib/pulse-settings'
import { promptPwaInstall, usePulsePwa } from '@/lib/pwa-store'
import { useMounted } from '@/hooks/use-mounted'
import { ChevronRow, CountUp, ProfileSection, StatTile, SwitchRow } from '@/components/profile/profile-primitives'
import { STATUS_GLYPH_CHOICES, StatusGlyph } from '@/components/profile/status-glyph'
import { HandleEditorDialog } from '@/components/profile/handle-editor'
import { UiLanguagePicker } from '@/components/profile/ui-language-picker'
import { NavStylePicker } from '@/components/profile/nav-style-picker'

const NAME_MAX = 32
const ABOUT_MAX = 140

interface UsersResponse {
  user: AppUser
}

function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(n) / Math.log(1024)))
  return `${(n / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

export function ProfileTab({
  me,
  onOpenSavedMessage,
}: {
  me: AppUser
  /** saved-library row tap → open that chat and flash the message */
  onOpenSavedMessage?: (conversationId: string, messageId: string) => void
}) {
  // remount the editor whenever the underlying identity changes,
  // which re-initializes all local field state (no sync effects)
  const identityKey = `${me.id}|${me.name}|${me.about}|${me.color}|${me.username}`
  return <ProfileEditor key={identityKey} me={me} onOpenSavedMessage={onOpenSavedMessage} />
}

function ProfileEditor({
  me,
  onOpenSavedMessage,
}: {
  me: AppUser
  onOpenSavedMessage?: (conversationId: string, messageId: string) => void
}) {
  const queryClient = useQueryClient()
  const setUser = usePulseSession((s) => s.setUser)
  const reducedMotion = useReducedMotion()
  const { resolvedTheme, setTheme } = useTheme()
  const { onlineIds } = usePulseRealtime()
  const soundOn = pulseSettingsStore((s) => s.soundOn)
  const hapticsOn = pulseSettingsStore((s) => s.hapticsOn)
  const setSoundOn = pulseSettingsStore((s) => s.setSoundOn)
  const setHapticsOn = pulseSettingsStore((s) => s.setHapticsOn)
  const quietHoursOn = pulseSettingsStore((s) => s.quietHoursOn)
  const quietStart = pulseSettingsStore((s) => s.quietStart)
  const quietEnd = pulseSettingsStore((s) => s.quietEnd)
  const setQuietHoursOn = pulseSettingsStore((s) => s.setQuietHoursOn)
  const setQuietStart = pulseSettingsStore((s) => s.setQuietStart)
  const setQuietEnd = pulseSettingsStore((s) => s.setQuietEnd)
  const installEvent = usePulsePwa((s) => s.installEvent)
  const mounted = useMounted()

  const [name, setName] = useState(me.name)
  const [about, setAbout] = useState(me.about)
  const [color, setColor] = useState<AvatarColor>((me.color as AvatarColor) ?? 'emerald')
  const [statusEmoji, setStatusEmoji] = useState(me.statusEmoji ?? '')
  const [statusText, setStatusText] = useState(me.statusText ?? '')
  const [savedOpen, setSavedOpen] = useState(false)
  const [switchOpen, setSwitchOpen] = useState(false)
  const [handleOpen, setHandleOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  /** real browser storage footprint (Data & Storage section) */
  const [storage, setStorage] = useState<{ usage: number; quota: number; caches: number } | null>(null)

  const customizeRef = useRef<HTMLDivElement>(null)

  // ── real data: profile stats + hub wallet ──────────────────
  const statsQ = useQuery({
    queryKey: ['user-stats', me.id],
    queryFn: async (): Promise<UserStats> => {
      const res = await apiJson<{ stats: UserStats }>(`/api/users/${encodeURIComponent(me.id)}/stats`)
      return res.stats
    },
    staleTime: 30_000,
  })

  const walletQ = useQuery({
    queryKey: ['hub-wallet', me.id],
    queryFn: async () => {
      return apiJson<{ wallet: { coins: number } }>(
        `/api/hub/wallet?userId=${encodeURIComponent(me.id)}`,
      )
    },
    select: (d) => d.wallet,
    staleTime: 30_000,
  })

  // real browser storage estimate + offline cache count (no mock data)
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const est = await navigator.storage?.estimate?.()
        let cacheCount = 0
        try {
          if (typeof caches !== 'undefined') cacheCount = (await caches.keys()).length
        } catch {
          cacheCount = 0
        }
        if (!cancelled && est) {
          setStorage({ usage: est.usage ?? 0, quota: est.quota ?? 0, caches: cacheCount })
        }
      } catch {
        // storage estimates unavailable on this browser — row shows fallback
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [])

  const iAmOnline = onlineIds.has(me.id)

  const handleInstall = async () => {
    const outcome = await promptPwaInstall()
    if (outcome === 'accepted') {
      toast.success('Installing Pulse', { description: 'Find it on your home screen.' })
    } else if (outcome === 'unavailable') {
      toast.error('Install is not available right now')
    }
  }

  const dirty =
    name.trim() !== me.name.trim() ||
    about.trim() !== me.about.trim() ||
    color !== me.color ||
    statusEmoji.trim() !== (me.statusEmoji ?? '') ||
    statusText.trim() !== (me.statusText ?? '')

  const saveProfile = useMutation({
    mutationFn: async () => {
      return apiJson<UsersResponse>(`/api/users/${encodeURIComponent(me.id)}`, {
        method: 'PATCH',
        body: JSON.stringify({
          name: name.trim(),
          about: about.trim(),
          color,
          statusEmoji: statusEmoji.trim(),
          statusText: statusText.trim(),
        }),
      })
    },
    onMutate: async () => {
      // optimistic update across session store + query caches
      const optimistic: AppUser = {
        ...me,
        name: name.trim(),
        about: about.trim(),
        color,
        statusEmoji: statusEmoji.trim() || null,
        statusText: statusText.trim() || null,
      }
      setUser(optimistic)
      queryClient.setQueryData(['me', me.id], optimistic)
      queryClient.setQueryData<AppUser[]>(['users'], (old) =>
        old?.map((u) => (u.id === me.id ? optimistic : u)),
      )
      return null
    },
    onSuccess: (data) => {
      setUser(data.user)
      queryClient.setQueryData(['me', me.id], data.user)
      queryClient.setQueryData<AppUser[]>(['users'], (old) =>
        old?.map((u) => (u.id === me.id ? data.user : u)),
      )
      toast.success('Profile updated')
    },
    onError: (error: Error) => {
      // rollback to the server-known snapshot
      setUser(me)
      queryClient.setQueryData(['me', me.id], me)
      queryClient.setQueryData<AppUser[]>(['users'], (old) =>
        old?.map((u) => (u.id === me.id ? me : u)),
      )
      toast.error(error.message || 'Could not save your profile')
    },
  })

  const copyId = async () => {
    try {
      await navigator.clipboard.writeText(me.id)
      toast.success('Account ID copied')
    } catch {
      toast.error('Clipboard is unavailable here')
    }
  }

  const copyHandle = async () => {
    if (!me.username) return
    try {
      await navigator.clipboard.writeText(`@${me.username}`)
      toast.success('Handle copied')
    } catch {
      toast.error('Clipboard is unavailable here')
    }
  }

  /** Telegram-style saved/starred library */
  const savedQuery = useQuery({
    queryKey: ['saved', me.id],
    queryFn: async (): Promise<SavedItem[]> => {
      const res = await apiJson<{ items: SavedItem[] }>(`/api/users/${encodeURIComponent(me.id)}/saved`)
      return res.items
    },
    enabled: savedOpen,
  })

  const toggleDarkMode = (checked: boolean) => setTheme(checked ? 'dark' : 'light')
  const signOut = () => {
    usePulseSession.getState().clear()
    window.location.reload()
  }

  const scrollToCustomize = () => {
    haptic(8)
    customizeRef.current?.scrollIntoView({
      behavior: reducedMotion ? 'auto' : 'smooth',
      block: 'start',
    })
  }

  const memberSinceShort = useMemo(() => {
    const d = new Date(me.createdAt)
    return Number.isNaN(d.getTime())
      ? ''
      : d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
  }, [me.createdAt])

  const accent = 'var(--ui-accent, #10b981)'
  const accent2 = 'var(--ui-accent-2, #14b8a6)'

  return (
    <div className="absolute inset-0 flex flex-col">
      {/* scroll body — generous bottom clearance for the floating capsule nav */}
      <div className="pulse-scroll relative min-h-0 flex-1 overflow-y-auto overscroll-contain pb-[calc(104px+env(safe-area-inset-bottom))]">
        {/* ── HERO ──────────────────────────────────────────── */}
        <section aria-label="Profile" className="relative">
          {/* cover — paints with the active UI language accent tokens */}
          <div
            className="relative h-36 overflow-hidden rounded-b-[28px] sm:h-44"
            style={{ background: `linear-gradient(118deg, ${accent}, ${accent2})` }}
          >
            <span
              aria-hidden
              className="absolute inset-0 bg-[radial-gradient(80%_90%_at_18%_0%,rgba(255,255,255,0.32),transparent_55%),radial-gradient(70%_80%_at_88%_18%,rgba(255,255,255,0.16),transparent_50%)]"
            />
            <motion.span
              aria-hidden
              className="absolute -right-10 -top-14 size-44 rounded-full bg-white/15 blur-2xl"
              animate={reducedMotion ? undefined : { scale: [1, 1.12, 1], opacity: [0.7, 1, 0.7] }}
              transition={{ duration: 9, repeat: Infinity, ease: 'easeInOut' }}
            />
            <motion.span
              aria-hidden
              className="absolute -left-12 -bottom-8 size-36 rounded-full bg-black/10 blur-2xl"
              animate={reducedMotion ? undefined : { scale: [1, 1.18, 1], opacity: [0.5, 0.8, 0.5] }}
              transition={{ duration: 11, delay: 1.2, repeat: Infinity, ease: 'easeInOut' }}
            />
          </div>

          <div className="relative px-4">
            {/* overlapping avatar with gradient presence ring */}
            <motion.div
              initial={reducedMotion ? false : { scale: 0.8, opacity: 0, y: 10 }}
              animate={{ scale: 1, opacity: 1, y: 0 }}
              transition={spring.bouncy}
              className="relative z-10 -mt-12 w-fit rounded-full p-[3px]"
              style={{ background: `linear-gradient(135deg, ${accent}, ${accent2})` }}
            >
              <div className="rounded-full bg-white p-[3px] dark:bg-zinc-900">
                <UserAvatar name={name || me.name} color={color} size={92} showPresence online={iAmOnline} />
              </div>
            </motion.div>

            {/* name + badge + handle */}
            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.05 }}
              className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1"
            >
              <h1 className="text-2xl font-black tracking-tight text-zinc-900 dark:text-zinc-50">
                {name.trim() || me.name}
              </h1>
              <span
                title="Registered member"
                className="flex size-5 items-center justify-center rounded-full"
                aria-label="Registered member"
              >
                <BadgeCheck className="size-5 fill-[var(--ui-accent,#10b981)] text-white dark:text-zinc-900" aria-hidden />
              </span>
            </motion.div>

            {/* @handle chip — tap to edit, copy icon to copy */}
            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.09 }}
              className="mt-1.5 flex items-center gap-1"
            >
              <button
                type="button"
                onClick={() => {
                  haptic(8)
                  setHandleOpen(true)
                }}
                aria-label={me.username ? `Change your handle, currently @${me.username}` : 'Set your handle'}
                className="flex min-h-[32px] items-center gap-1 rounded-full bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_12%,transparent)] py-1 pl-2.5 pr-2.5 outline-none ring-1 ring-[var(--ui-accent,#10b981)]/25 transition-transform active:scale-95"
              >
                <AtSign className="size-3.5 text-[var(--ui-accent,#10b981)]" aria-hidden />
                <span className="text-xs font-bold text-[var(--ui-accent,#10b981)]">
                  {me.username ? `@${me.username}` : 'Set your handle'}
                </span>
                <Pencil className="size-3 text-[var(--ui-accent,#10b981)]/70" aria-hidden />
              </button>
              {me.username ? (
                <motion.button
                  type="button"
                  whileTap={reducedMotion ? undefined : pressTap}
                  transition={pressSpring}
                  onClick={copyHandle}
                  aria-label="Copy your handle"
                  className="flex size-8 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-600 dark:text-zinc-500 dark:hover:bg-white/10 dark:hover:text-zinc-300"
                >
                  <Copy className="size-3.5" aria-hidden />
                </motion.button>
              ) : null}
            </motion.div>

            {/* status + about (committed values — edit below) */}
            {me.statusEmoji || me.statusText ? (
              <motion.p
                initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, ease: ease.out, delay: 0.12 }}
                className="mt-2 flex items-center gap-1.5 text-[13px] font-semibold text-zinc-700 dark:text-zinc-200"
              >
                {me.statusEmoji ? <StatusGlyph value={me.statusEmoji} className="size-4 text-[var(--ui-accent,#10b981)]" /> : null}
                {me.statusText}
              </motion.p>
            ) : null}
            <motion.p
              initial={reducedMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.15 }}
              className="mt-1 max-w-md text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400"
            >
              {me.about}
            </motion.p>

            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.18 }}
              className="mt-3"
            >
              <Button
                variant="outline"
                size="sm"
                onClick={scrollToCustomize}
                className="h-9 gap-1.5 rounded-full border-zinc-200 bg-white/70 px-4 text-xs font-bold text-zinc-700 backdrop-blur-xl hover:bg-white active:scale-95 dark:border-white/10 dark:bg-zinc-900/60 dark:text-zinc-200 dark:hover:bg-zinc-900"
              >
                <Pencil className="size-3.5" aria-hidden />
                Edit profile
              </Button>
            </motion.div>
          </div>
        </section>

        {/* ── STATS — real data only ────────────────────────── */}
        <section aria-label="Your activity" className="mt-5 px-4">
          <div className={cn('grid grid-cols-4 gap-1.5 rounded-3xl border border-zinc-200/70 bg-white/70 p-2 backdrop-blur-2xl dark:border-white/10 dark:bg-zinc-900/60')}>
            {statsQ.isPending ? (
              Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-[58px] rounded-2xl" />
              ))
            ) : (
              <>
                <StatTile delay={0} label="Messages" value={statsQ.isError ? '—' : <CountUp value={statsQ.data?.messages ?? 0} />} />
                <StatTile delay={1} label="Rooms" value={statsQ.isError ? '—' : <CountUp value={statsQ.data?.chats ?? 0} />} />
                <StatTile
                  delay={2}
                  accent
                  label="Coins"
                  value={
                    walletQ.isPending ? (
                      <Skeleton className="h-5 w-10 rounded-md" />
                    ) : walletQ.isError ? (
                      '—'
                    ) : (
                      <span className="inline-flex items-center gap-1">
                        <Coins className="size-3.5" aria-hidden />
                        <CountUp value={walletQ.data?.coins ?? 0} />
                      </span>
                    )
                  }
                />
                <StatTile delay={3} label="Member since" value={memberSinceShort || '—'} />
              </>
            )}
          </div>
        </section>

        {/* ── CUSTOMIZE ─────────────────────────────────────── */}
        <div ref={customizeRef} className="scroll-mt-3 px-4">
          <ProfileSection title="Customize" delay={0.02}>
            <div className="space-y-3 p-1.5">
              <div className="space-y-1.5">
                <Label htmlFor="profile-name" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                  Display name
                </Label>
                <Input
                  id="profile-name"
                  value={name}
                  maxLength={NAME_MAX}
                  onChange={(e) => setName(e.target.value.slice(0, NAME_MAX))}
                  autoComplete="off"
                  className="h-11 rounded-xl border-zinc-200 bg-zinc-50 text-[15px] focus-visible:ring-[var(--ui-accent,#10b981)]/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="profile-about" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                  Bio
                </Label>
                <Textarea
                  id="profile-about"
                  value={about}
                  rows={2}
                  maxLength={ABOUT_MAX}
                  placeholder="Hey there! I'm using Pulse."
                  onChange={(e) => setAbout(e.target.value.slice(0, ABOUT_MAX))}
                  className="resize-none rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-[var(--ui-accent,#10b981)]/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
              </div>

              {/* @handle editor row */}
              <ChevronRow
                icon={AtSign}
                iconClassName="bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_12%,transparent)] text-[var(--ui-accent,#10b981)]"
                title="Handle"
                description={me.username ? `@${me.username}` : 'Claim yours — friends can find you by it'}
                onPress={() => setHandleOpen(true)}
                aria-label={me.username ? `Change your handle, currently @${me.username}` : 'Set your handle'}
              />

              {/* status glyph picker — Lucide glyphs, values persisted verbatim */}
              <div className="space-y-2 py-1">
                <Label htmlFor="profile-status" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                  Status
                </Label>
                <div role="radiogroup" aria-label="Status glyph" className="flex flex-wrap items-center gap-1.5">
                  {STATUS_GLYPH_CHOICES.map((g) => {
                    const selected = statusEmoji === g.value
                    const Icon = g.icon
                    return (
                      <motion.button
                        key={g.value}
                        type="button"
                        role="radio"
                        aria-checked={selected}
                        aria-label={g.label}
                        title={g.label}
                        onClick={() => {
                          haptic(6)
                          setStatusEmoji(selected ? '' : g.value)
                        }}
                        whileTap={reducedMotion ? undefined : pressTap}
                        transition={pressSpring}
                        className={cn(
                          'flex size-11 items-center justify-center rounded-2xl outline-none transition-all',
                          selected
                            ? 'scale-105 bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_16%,transparent)] ring-2 ring-[var(--ui-accent,#10b981)]'
                            : 'bg-zinc-100 text-zinc-500 hover:bg-zinc-200 dark:bg-zinc-800 dark:text-zinc-300 dark:hover:bg-zinc-700',
                        )}
                      >
                        <Icon className="size-4.5" aria-hidden />
                      </motion.button>
                    )
                  })}
                </div>
                <Input
                  id="profile-status"
                  value={statusText}
                  maxLength={48}
                  placeholder="What's happening? (optional)"
                  onChange={(e) => setStatusText(e.target.value.slice(0, 48))}
                  autoComplete="off"
                  className="h-10 rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-[var(--ui-accent,#10b981)]/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
              </div>

              {/* avatar color */}
              <div className="space-y-2 py-1">
                <Label className="text-xs font-medium text-zinc-600 dark:text-zinc-300">Avatar color</Label>
                <div role="radiogroup" aria-label="Avatar color" className="flex items-center justify-between px-0.5">
                  {PULSE_COLORS.map((c) => {
                    const selected = c === color
                    return (
                      <motion.button
                        key={c}
                        type="button"
                        role="radio"
                        aria-checked={selected}
                        aria-label={`${c} avatar`}
                        onClick={() => {
                          haptic(6)
                          setColor(c)
                        }}
                        whileTap={reducedMotion ? undefined : pressTap}
                        transition={pressSpring}
                        className={cn(
                          'flex size-11 items-center justify-center rounded-full bg-gradient-to-br shadow-sm outline-none transition-transform',
                          AVATAR_GRADIENTS[c],
                          selected
                            ? 'scale-105 ring-2 ring-[var(--ui-accent,#10b981)] ring-offset-2 ring-offset-white dark:ring-offset-zinc-900'
                            : 'hover:scale-105',
                        )}
                      >
                        {selected ? <Check className="size-4 text-white" strokeWidth={3} /> : null}
                      </motion.button>
                    )
                  })}
                </div>
              </div>

              <AnimatePresence>
                {dirty ? (
                  <motion.div
                    initial={{ opacity: 0, y: -6 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -6 }}
                    transition={{ duration: 0.18 }}
                  >
                    <Button
                      onClick={() => saveProfile.mutate()}
                      disabled={saveProfile.isPending || name.trim().length === 0}
                      className="h-11 w-full rounded-xl bg-[var(--ui-accent,#10b981)] text-sm font-bold text-white shadow-lg shadow-black/10 hover:opacity-90 active:scale-[0.98]"
                    >
                      {saveProfile.isPending ? (
                        <>
                          <LoaderCircle className="size-4 animate-spin" aria-hidden />
                          Saving
                        </>
                      ) : (
                        <>
                          <Check className="size-4" strokeWidth={3} aria-hidden />
                          Save changes
                        </>
                      )}
                    </Button>
                  </motion.div>
                ) : null}
              </AnimatePresence>
            </div>
          </ProfileSection>
        </div>

        {/* ── APPEARANCE ────────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Appearance" delay={0.06}>
            <div className="p-1">
              <SwitchRow
                icon={mounted && resolvedTheme === 'dark' ? Moon : Sun}
                iconClassName="text-[var(--ui-accent,#10b981)]"
                title="Dark mode"
                description="Comfort in low light — applies app-wide"
                checked={mounted && resolvedTheme === 'dark'}
                onCheckedChange={toggleDarkMode}
                disabled={!mounted}
                ariaLabel="Toggle dark mode"
              />
              <Separator className="my-1.5 opacity-60" />
              <p className="px-2.5 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
                UI language
              </p>
              <UiLanguagePicker />
            </div>
          </ProfileSection>
        </div>

        {/* ── NAVIGATION ────────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Navigation" delay={0.1}>
            <NavStylePicker />
          </ProfileSection>
        </div>

        {/* ── LIBRARY ───────────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Library" delay={0.14}>
            <ChevronRow
              icon={Star}
              iconClassName="bg-amber-500/12 text-amber-500"
              title="Saved messages"
              description="Long-press any message in a chat, then Save"
              onPress={() => setSavedOpen(true)}
            />
          </ProfileSection>
        </div>

        {/* ── PREFERENCES ───────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Preferences" delay={0.18}>
            <div className="p-1">
              <SwitchRow
                icon={Volume2}
                iconClassName="text-[var(--ui-accent,#10b981)]"
                title="In-app sounds"
                description="Gentle pings for incoming messages"
                checked={soundOn}
                onCheckedChange={(on) => {
                  setSoundOn(on)
                  if (on) primeSound()
                }}
                ariaLabel="Toggle notification sounds"
              />
              <SwitchRow
                icon={Vibrate}
                iconClassName="text-[var(--ui-accent,#10b981)]"
                title="Haptic feedback"
                description="Tactile ticks on key interactions"
                checked={hapticsOn}
                onCheckedChange={(on) => {
                  setHapticsOn(on)
                  if (on) haptic(15)
                }}
                ariaLabel="Toggle haptic feedback"
              />
              <SwitchRow
                icon={MoonStar}
                iconClassName={cn(quietHoursOn ? 'text-[var(--ui-accent,#10b981)]' : 'text-zinc-400 dark:text-zinc-500')}
                title="Quiet hours"
                description={
                  quietHoursOn && !mounted
                    ? undefined
                    : quietHoursOn
                      ? mounted && isQuietHoursNow({ quietHoursOn, quietStart, quietEnd })
                        ? `Silenced until ${quietEnd}`
                        : `Silent ${quietStart} – ${quietEnd}`
                      : 'Pings stay on around the clock'
                }
                checked={quietHoursOn}
                onCheckedChange={setQuietHoursOn}
                disabled={!mounted}
                ariaLabel="Toggle quiet hours"
              />
              <AnimatePresence>
                {quietHoursOn ? (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: 'auto' }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.18 }}
                    className="overflow-hidden"
                  >
                    <div className="mx-2.5 mb-1.5 mt-1 flex flex-wrap items-center gap-2 rounded-xl bg-zinc-100/80 px-3 py-2.5 dark:bg-zinc-800/60">
                      <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">From</span>
                      <input
                        type="time"
                        value={quietStart}
                        onChange={(e) => e.target.value && setQuietStart(e.target.value)}
                        aria-label="Quiet hours start time"
                        className="h-8 rounded-lg border border-zinc-200 bg-white px-2 font-mono text-xs text-zinc-700 outline-none focus-visible:ring-2 focus-visible:ring-[var(--ui-accent,#10b981)]/50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
                      />
                      <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">to</span>
                      <input
                        type="time"
                        value={quietEnd}
                        onChange={(e) => e.target.value && setQuietEnd(e.target.value)}
                        aria-label="Quiet hours end time"
                        className="h-8 rounded-lg border border-zinc-200 bg-white px-2 font-mono text-xs text-zinc-700 outline-none focus-visible:ring-2 focus-visible:ring-[var(--ui-accent,#10b981)]/50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
                      />
                      <span className="ml-auto text-[10px] font-medium uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
                        {quietStart < quietEnd || quietStart === quietEnd ? 'same day' : 'overnight'}
                      </span>
                    </div>
                  </motion.div>
                ) : null}
              </AnimatePresence>
            </div>
          </ProfileSection>
        </div>

        {/* ── DATA & STORAGE ────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Data & Storage" delay={0.22}>
            <div className="p-1">
              {installEvent ? (
                <>
                  <div className="flex min-h-[56px] items-center gap-3 px-2.5 py-2">
                    <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_12%,transparent)] text-[var(--ui-accent,#10b981)]">
                      <Smartphone className="size-4" aria-hidden />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Install Pulse</span>
                      <span className="mt-0.5 block text-[11px] text-zinc-400 dark:text-zinc-500">
                        Add to your home screen — opens instantly, works offline
                      </span>
                    </span>
                    <Button
                      size="sm"
                      onClick={handleInstall}
                      className="h-9 shrink-0 rounded-full bg-[var(--ui-accent,#10b981)] px-4 text-xs font-bold text-white hover:opacity-90 active:scale-95"
                    >
                      Install
                    </Button>
                  </div>
                  <Separator className="my-1.5 opacity-60" />
                </>
              ) : null}
              {/* real browser storage footprint */}
              <div className="flex min-h-[56px] items-center gap-3 px-2.5 py-2">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-900/5 text-zinc-500 dark:bg-white/10 dark:text-zinc-300">
                  <Database className="size-4" aria-hidden />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Storage used</span>
                  <span className="mt-0.5 block text-[11px] text-zinc-400 dark:text-zinc-500">
                    {storage
                      ? `${storage.caches} offline ${storage.caches === 1 ? 'cache' : 'caches'} on this device`
                      : 'Checking this device'}
                  </span>
                </span>
                <span className="shrink-0 text-xs font-bold tabular-nums text-zinc-500 dark:text-zinc-400">
                  {storage ? formatBytes(storage.usage) : ''}
                </span>
              </div>
            </div>
          </ProfileSection>
        </div>

        {/* ── ACCOUNT ───────────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Account" delay={0.26}>
            <div className="p-1">
              <ChevronRow
                icon={Fingerprint}
                title="Copy account ID"
                description={me.id}
                onPress={copyId}
                ariaLabel={`Copy account ID ${me.id}`}
              />
              <ChevronRow
                icon={Settings}
                title="All settings"
                description="Privacy, storage details, chat behavior & more"
                onPress={() => setSettingsOpen(true)}
              />
            </div>
          </ProfileSection>
        </div>

        {/* ── ABOUT ─────────────────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="About" delay={0.3}>
            <div className="p-1">
              <div className="flex min-h-[48px] items-center gap-3 px-2.5 py-2">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-900/5 text-zinc-500 dark:bg-white/10 dark:text-zinc-300">
                  <Info className="size-4" aria-hidden />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Pulse</span>
                  <span className="mt-0.5 block text-[11px] text-zinc-400 dark:text-zinc-500">
                    Real-time messenger with presence, typing and read receipts
                  </span>
                </span>
                <span className="shrink-0 rounded-full bg-zinc-900/5 px-2 py-0.5 text-[10px] font-bold tabular-nums text-zinc-500 dark:bg-white/10 dark:text-zinc-400">
                  v{APP_VERSION}
                </span>
              </div>
              <div className="flex min-h-[48px] items-center gap-3 px-2.5 py-2">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-zinc-900/5 text-zinc-500 dark:bg-white/10 dark:text-zinc-300">
                  <CalendarDays className="size-4" aria-hidden />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Member since</span>
                  <span className="mt-0.5 block text-[11px] text-zinc-400 dark:text-zinc-500">
                    {formatMemberSince(me.createdAt)}
                  </span>
                </span>
                {statsQ.data ? (
                  <span className="shrink-0 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
                    {statsQ.data.days} {statsQ.data.days === 1 ? 'day' : 'days'} on Pulse
                  </span>
                ) : null}
              </div>
            </div>
          </ProfileSection>
        </div>

        {/* ── SESSION / SIGN OUT ────────────────────────────── */}
        <div className="px-4">
          <ProfileSection title="Session" delay={0.34}>
            <ChevronRow
              icon={LogOut}
              destructive
              title="Sign out"
              description="Return to the welcome screen — nothing is deleted"
              onPress={() => setSwitchOpen(true)}
            />
          </ProfileSection>
        </div>
      </div>

      {/* saved-messages library drawer */}
      <Drawer open={savedOpen} onOpenChange={setSavedOpen}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          <DrawerTitle className="sr-only">Saved messages</DrawerTitle>
          <DrawerDescription className="sr-only">Messages you starred across every chat</DrawerDescription>
          <div className="pb-2">
            <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
              <Star className="size-4 fill-amber-400 text-amber-500" aria-hidden />
              {savedQuery.isPending
                ? 'Loading'
                : `${(savedQuery.data ?? []).length} saved ${(savedQuery.data ?? []).length === 1 ? 'message' : 'messages'}`}
            </p>
            {(savedQuery.data ?? []).length === 0 && !savedQuery.isPending ? (
              <p className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
                Long-press a message in any chat and choose Save message.
              </p>
            ) : (
              <ul className="pulse-scroll max-h-[52dvh] space-y-2 overflow-y-auto py-1">
                {(savedQuery.data ?? []).map((item) => (
                  <li key={`${item.message.id}`}>
                    <button
                      type="button"
                      onClick={() => {
                        haptic(8)
                        setSavedOpen(false)
                        onOpenSavedMessage?.(item.message.conversationId, item.message.id)
                      }}
                      className="w-full rounded-2xl border border-zinc-200 bg-zinc-50/70 p-2.5 text-left outline-none transition-colors hover:border-[var(--ui-accent,#10b981)]/50 active:scale-[0.99] dark:border-zinc-700 dark:bg-zinc-800/60"
                    >
                      <div className="flex items-center gap-2">
                        <UserAvatar name={item.message.sender.name} color={item.message.sender.color} size={22} />
                        <span className="truncate text-xs font-bold text-[var(--ui-accent,#10b981)]">
                          {item.message.sender.id === me.id ? 'You' : item.message.sender.name}
                          <span className="ml-1.5 font-medium text-zinc-400">in {item.conversation.name ?? 'chat'}</span>
                        </span>
                        <span className="ml-auto shrink-0 text-[10px] text-zinc-400">{item.savedAt.slice(0, 10)}</span>
                      </div>
                      <span className="mt-1 flex items-start gap-1.5">
                        {item.message.imagePath ? (
                          <ImageIcon className="mt-0.5 size-3.5 shrink-0 text-zinc-400" aria-label="Photo message" />
                        ) : null}
                        {item.message.audioPath ? (
                          <Mic className="mt-0.5 size-3.5 shrink-0 text-zinc-400" aria-label="Voice message" />
                        ) : null}
                        <span className="line-clamp-2 text-[13px] leading-snug text-zinc-600 dark:text-zinc-300">
                          {item.message.content.replace(/\s+/g, ' ').trim() || '(media)'}
                        </span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </DrawerContent>
      </Drawer>

      {/* @handle editor dialog */}
      <HandleEditorDialog
        open={handleOpen}
        onOpenChange={setHandleOpen}
        me={me}
        onSaved={(user) => {
          setUser(user)
          queryClient.setQueryData(['me', me.id], user)
          queryClient.setQueryData<AppUser[]>(['users'], (old) =>
            old?.map((u) => (u.id === user.id ? user : u)),
          )
        }}
      />

      {/* full settings tree (owned by R19-d) */}
      <SettingsScreen open={settingsOpen} onClose={() => setSettingsOpen(false)} />

      <AlertDialog open={switchOpen} onOpenChange={setSwitchOpen}>
        <AlertDialogContent className="max-w-[320px] rounded-2xl border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900 sm:left-1/2 sm:translate-x-[-50%]">
          <AlertDialogHeader>
            <AlertDialogTitle className="tracking-tight">Sign out of this account?</AlertDialogTitle>
            <AlertDialogDescription className="text-[13px] leading-relaxed">
              You&apos;ll return to the welcome screen. Nothing is deleted — you can always create or join back in later.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="gap-2">
            <AlertDialogCancel className="rounded-xl">Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={signOut}
              className="rounded-xl bg-destructive text-white hover:bg-destructive/90 focus-visible:ring-destructive/40"
            >
              Sign out
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

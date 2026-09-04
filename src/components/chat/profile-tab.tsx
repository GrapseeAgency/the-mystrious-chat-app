// ─────────────────────────────────────────────────────────────
// Pulse Chat — Profile/Settings tab: edit identity, appearance,
// account actions and switch-account flow.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import { AtSign, BadgeCheck, Check, ChevronRight, Compass, Copy, LayoutDashboard, LayoutGrid, LoaderCircle, LogOut, Moon, MoonStar, PanelRight, Settings, Smartphone, Star, Sun, Volume2, Vibrate, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary, SavedItem } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import {
  AVATAR_GRADIENTS,
  PULSE_COLORS,
  ApiError,
  apiJson,
  formatMemberSince,
  jsonBody,
  type AvatarColor,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { UserAvatar } from '@/components/chat/user-avatar'
import { SettingsScreen } from '@/components/chat/settings-screen'
import { pulseSettingsStore, haptic, isQuietHoursNow, primeSound } from '@/lib/pulse-settings'
import { promptPwaInstall, usePulsePwa } from '@/lib/pwa-store'
import { useMounted } from '@/hooks/use-mounted'
import { NAV_STYLE_META, useNavStyle, type NavStyleId } from '@/components/chat/nav-router'

interface UsersResponse {
  user: AppUser
}
interface ConversationsResponse {
  conversations: ConversationSummary[]
}

const NAME_MAX = 32
const ABOUT_MAX = 140
/** Discord-flavored custom-status glyph choices. */
const STATUS_EMOJIS = ['🔥', '✨', '🎯', '☕', '🎧', '🌙', '💡', '🚀', '😴', '🍽️', ' vacation'.trim(), '💼'] as const

export function ProfileTab({
  me,
  onOpenSavedMessage,
}: {
  me: AppUser
  /** saved-library row tap → open that chat and flash the message */
  onOpenSavedMessage?: (conversationId: string, messageId: string) => void
}) {
  return <ProfileTabInner me={me} onOpenSavedMessage={onOpenSavedMessage} />
}

function ProfileTabInner({
  me,
  onOpenSavedMessage,
}: {
  me: AppUser
  onOpenSavedMessage?: (conversationId: string, messageId: string) => void
}) {
  // remount the editor whenever the underlying identity changes,
  // which re-initializes all local field state (no sync effects)
  const identityKey = `${me.id}|${me.name}|${me.about}|${me.color}`
  return <ProfileEditor key={identityKey} me={me} onOpenSavedMessage={onOpenSavedMessage} />
}

function ProfileEditor({ me, onOpenSavedMessage }: { me: AppUser; onOpenSavedMessage?: (conversationId: string, messageId: string) => void }) {
  const queryClient = useQueryClient()
  const setUser = usePulseSession((s) => s.setUser)
  const { resolvedTheme, setTheme } = useTheme()
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
  // Discord-style custom status
  const [statusEmoji, setStatusEmoji] = useState(me.statusEmoji ?? '')
  const [statusText, setStatusText] = useState(me.statusText ?? '')
  /** saved-messages library drawer */
  const [savedOpen, setSavedOpen] = useState(false)
  const [switchOpen, setSwitchOpen] = useState(false)
  /** @handle editor dialog */
  const [handleOpen, setHandleOpen] = useState(false)
  /** full settings-tree screen (owned by R19-d) */
  const [settingsOpen, setSettingsOpen] = useState(false)

  const handleInstall = async () => {
    const outcome = await promptPwaInstall()
    if (outcome === 'accepted') {
      toast.success('Installing Pulse…', { description: 'Find it on your home screen.' })
    } else if (outcome === 'unavailable') {
      toast.error('Install is not available right now')
    }
  }

  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res.conversations
    },
    refetchInterval: 30_000,
    staleTime: 10_000,
  })

  const stats = useMemo(() => {
    const list = conversations.data ?? []
    return {
      chats: list.length,
      unread: list.reduce((sum, c) => sum + c.unreadCount, 0),
      memberSince: formatMemberSince(me.createdAt),
    }
  }, [conversations.data, me.createdAt])

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
  const switchAccount = () => {
    usePulseSession.getState().clear()
    window.location.reload()
  }

  return (
    <div className="absolute inset-0 flex flex-col bg-white dark:bg-zinc-900">
      <header className="shrink-0 border-b border-zinc-200 px-4 pb-3 pt-[max(0.75rem,env(safe-area-inset-top))] dark:border-zinc-800">
        <h1 className="text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">Settings</h1>
      </header>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pt-4 pb-[max(1.5rem,env(safe-area-inset-bottom))]">
        {/* avatar + fields */}
        <section aria-label="Profile" className="flex flex-col items-center gap-4">
          <UserAvatar name={name || me.name} color={color} size={96} showPresence online />

          <div className="w-full space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="profile-name" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                Name
              </Label>
              <Input
                id="profile-name"
                value={name}
                maxLength={NAME_MAX}
                onChange={(e) => setName(e.target.value.slice(0, NAME_MAX))}
                autoComplete="off"
                className="h-11 rounded-xl border-zinc-200 bg-zinc-50 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="profile-about" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                About
              </Label>
              <Textarea
                id="profile-about"
                value={about}
                rows={2}
                maxLength={ABOUT_MAX}
                placeholder="Hey there! I'm using Pulse."
                onChange={(e) => setAbout(e.target.value.slice(0, ABOUT_MAX))}
                className="resize-none rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
              />
            </div>

            {/* @handle editor row */}
            <button
              type="button"
              onClick={() => {
                haptic(8)
                setHandleOpen(true)
              }}
              aria-label={me.username ? `Change your handle, currently @${me.username}` : 'Set your handle'}
              className="flex min-h-[56px] w-full items-center gap-3 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-left outline-none transition-colors hover:border-emerald-300 active:scale-[0.99] dark:border-zinc-700 dark:bg-zinc-800 dark:hover:border-emerald-500/40"
            >
              <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-500/10">
                <AtSign className="size-4 text-emerald-500" aria-hidden />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-[11px] font-medium text-zinc-500 dark:text-zinc-400">Handle</span>
                <span className="block truncate text-sm font-semibold text-zinc-800 dark:text-zinc-100">
                  {me.username ? `@${me.username}` : 'Set your handle'}
                </span>
              </span>
              <ChevronRight className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
            </button>

            {/* Discord-style custom status */}
            <div className="space-y-2 py-1">
              <Label htmlFor="profile-status" className="text-xs font-medium text-zinc-600 dark:text-zinc-300">
                Custom status <span className="text-zinc-400">(Discord-style · shown on your presence)</span>
              </Label>
              <div role="radiogroup" aria-label="Status emoji" className="flex flex-wrap items-center gap-1">
                {STATUS_EMOJIS.map((e) => {
                  const selected = statusEmoji === e
                  return (
                    <button
                      key={e}
                      type="button"
                      role="radio"
                      aria-checked={selected}
                      aria-label={`Set status emoji ${e}`}
                      onClick={() => {
                        haptic(6)
                        setStatusEmoji(selected ? '' : e)
                      }}
                      className={cn(
                        'flex size-8 items-center justify-center rounded-full text-base outline-none transition-transform',
                        selected
                          ? 'bg-emerald-500/15 ring-2 ring-emerald-500 scale-105'
                          : 'bg-zinc-100 hover:bg-zinc-200 dark:bg-zinc-800 dark:hover:bg-zinc-700',
                      )}
                    >
                      {e}
                    </button>
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
                className="h-10 rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
              />
            </div>

            {/* colors */}
            <div className="space-y-2 py-1">
              <Label className="text-xs font-medium text-zinc-600 dark:text-zinc-300">Avatar color</Label>
              <div role="radiogroup" aria-label="Avatar color" className="flex items-center justify-between px-0.5">
                {PULSE_COLORS.map((c) => {
                  const selected = c === color
                  return (
                    <button
                      key={c}
                      type="button"
                      role="radio"
                      aria-checked={selected}
                      aria-label={`${c} avatar`}
                      onClick={() => setColor(c)}
                      className={cn(
                        'flex size-8 items-center justify-center rounded-full bg-gradient-to-br shadow-sm outline-none transition-transform active:scale-90',
                        AVATAR_GRADIENTS[c],
                        selected
                          ? 'ring-2 ring-emerald-600 ring-offset-2 ring-offset-white dark:ring-offset-zinc-900 scale-105'
                          : 'hover:scale-105',
                      )}
                    >
                      {selected ? <Check className="size-3.5 text-white" strokeWidth={3} /> : null}
                    </button>
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
                    className="h-11 w-full rounded-xl bg-emerald-600 text-sm font-semibold text-white shadow-md shadow-emerald-600/20 hover:bg-emerald-500 active:scale-[0.98]"
                  >
                    {saveProfile.isPending ? (
                      <>
                        <LoaderCircle className="size-4 animate-spin" aria-hidden />
                        Saving…
                      </>
                    ) : (
                      <>
                        <BadgeCheck className="size-4" aria-hidden />
                        Save changes
                      </>
                    )}
                  </Button>
                </motion.div>
              ) : null}
            </AnimatePresence>
          </div>
        </section>

        {/* stats */}
        <section aria-label="Stats" className="mt-5 grid grid-cols-3 gap-2">
          <StatChip label="Chats" value={stats.chats.toLocaleString('en-US')} />
          <StatChip label="Unread" value={stats.unread > 99 ? '99+' : String(stats.unread)} accent />
          <StatChip label="Member since" value={stats.memberSince} />
        </section>

        {/* general — full settings tree */}
        <Section title="General">
          <button
            type="button"
            onClick={() => {
              haptic(8)
              setSettingsOpen(true)
            }}
            aria-label="Open settings"
            className="flex min-h-[52px] w-full items-center gap-3 rounded-xl px-1 py-2 text-left outline-none transition-colors hover:bg-zinc-50 active:bg-zinc-100 dark:hover:bg-zinc-800/60"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-500/10">
              <Settings className="size-4 text-emerald-500" aria-hidden />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Settings</span>
              <span className="block text-[11px] text-zinc-400 dark:text-zinc-500">Preferences, storage, privacy &amp; more</span>
            </span>
            <ChevronRight className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
          </button>
        </Section>

        {/* navigation architecture — 4 swappable styles */}
        <Section title="Navigation">
          <NavStyleGrid />
        </Section>

        {/* saved / starred library (Telegram parity) */}
        <Section title="Library">
          <button
            type="button"
            onClick={() => {
              haptic(8)
              setSavedOpen(true)
            }}
            className="flex w-full items-center gap-3 rounded-xl px-1 py-2 text-left outline-none transition-colors hover:bg-zinc-50 active:bg-zinc-100 dark:hover:bg-zinc-800/60"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-amber-500/10">
              <Star className="size-4 fill-amber-400 text-amber-500" aria-hidden />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-semibold text-zinc-800 dark:text-zinc-100">Saved messages</span>
              <span className="block text-[11px] text-zinc-400 dark:text-zinc-500">Long-press any message → Save message</span>
            </span>
          </button>
        </Section>

        {/* notifications */}
        <Section title="Notifications">
          <div className="flex items-center justify-between px-1 py-1.5">
            <span className="flex items-center gap-3 text-sm font-medium text-zinc-700 dark:text-zinc-200">
              <Volume2 className="size-4 text-emerald-500" aria-hidden />
              In-app sounds
            </span>
            <Switch
              checked={soundOn}
              onCheckedChange={(on) => {
                setSoundOn(on)
                if (on) primeSound()
              }}
              aria-label="Toggle notification sounds"
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>
          <div className="flex items-center justify-between px-1 py-1.5">
            <span className="flex items-center gap-3 text-sm font-medium text-zinc-700 dark:text-zinc-200">
              <Vibrate className="size-4 text-emerald-500" aria-hidden />
              Haptic feedback
            </span>
            <Switch
              checked={hapticsOn}
              onCheckedChange={(on) => {
                setHapticsOn(on)
                if (on) haptic(15)
              }}
              aria-label="Toggle haptic feedback"
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>
          <div className="flex items-center justify-between px-1 py-1.5">
            <span className="flex min-w-0 items-center gap-3 text-sm font-medium text-zinc-700 dark:text-zinc-200">
              <MoonStar
                className={cn('size-4 shrink-0', quietHoursOn ? 'text-emerald-500' : 'text-zinc-400 dark:text-zinc-500')}
                aria-hidden
              />
              <span className="min-w-0">
                Quiet hours
                {quietHoursOn && !mounted ? null : (
                  <span className="mt-0.5 block text-[11px] font-normal text-zinc-400 dark:text-zinc-500">
                    {quietHoursOn
                      ? mounted && isQuietHoursNow({ quietHoursOn, quietStart, quietEnd })
                        ? `Silenced until ${quietEnd}`
                        : `Silent ${quietStart} – ${quietEnd}`
                      : 'Pings stay on around the clock'}
                  </span>
                )}
              </span>
            </span>
            <Switch
              checked={quietHoursOn}
              onCheckedChange={setQuietHoursOn}
              disabled={!mounted}
              aria-label="Toggle quiet hours"
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>
          {quietHoursOn ? (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              transition={{ duration: 0.18 }}
              className="overflow-hidden"
            >
              <div className="mx-1 mb-1.5 mt-1 flex items-center gap-2 rounded-xl bg-zinc-50 px-3 py-2.5 dark:bg-zinc-800/60">
                <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">From</span>
                <input
                  type="time"
                  value={quietStart}
                  onChange={(e) => e.target.value && setQuietStart(e.target.value)}
                  aria-label="Quiet hours start time"
                  className="h-8 rounded-lg border border-zinc-200 bg-white px-2 font-mono text-xs text-zinc-700 outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
                />
                <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">to</span>
                <input
                  type="time"
                  value={quietEnd}
                  onChange={(e) => e.target.value && setQuietEnd(e.target.value)}
                  aria-label="Quiet hours end time"
                  className="h-8 rounded-lg border border-zinc-200 bg-white px-2 font-mono text-xs text-zinc-700 outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
                />
                <span className="ml-auto text-[10px] font-medium uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
                  {quietStart < quietEnd || quietStart === quietEnd ? 'same day' : 'overnight'}
                </span>
              </div>
            </motion.div>
          ) : null}
        </Section>

        {/* appearance */}
        <Section title="Appearance">
          <div className="flex items-center justify-between px-1 py-1.5">
            <span className="flex items-center gap-3 text-sm font-medium text-zinc-700 dark:text-zinc-200">
              {mounted && resolvedTheme === 'dark' ? (
                <Moon className="size-4 text-emerald-500" aria-hidden />
              ) : (
                <Sun className="size-4 text-emerald-500" aria-hidden />
              )}
              Dark mode
            </span>
            <Switch
              checked={mounted && resolvedTheme === 'dark'}
              onCheckedChange={toggleDarkMode}
              disabled={!mounted}
              aria-label="Toggle dark mode"
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>
        </Section>

        {/* app install (visible when the browser offers the prompt) */}
        {installEvent ? (
          <Section title="App">
            <div className="flex items-center justify-between gap-2 px-1 py-1.5">
              <div className="min-w-0">
                <p className="flex items-center gap-3 text-sm font-medium text-zinc-700 dark:text-zinc-200">
                  <Smartphone className="size-4 shrink-0 text-emerald-500" aria-hidden />
                  Install Pulse
                </p>
                <p className="mt-0.5 pl-7 text-xs leading-relaxed text-zinc-400 dark:text-zinc-500">
                  Add to your home screen — opens instantly, works offline.
                </p>
              </div>
              <Button
                size="sm"
                onClick={handleInstall}
                className="h-9 shrink-0 rounded-full bg-emerald-600 px-4 text-xs font-semibold text-white shadow-sm shadow-emerald-600/20 hover:bg-emerald-500 active:scale-95"
              >
                Install
              </Button>
            </div>
          </Section>
        ) : null}

        {/* account */}
        <Section title="Account">
          <div className="flex items-center justify-between gap-2 px-1 py-1.5">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-zinc-700 dark:text-zinc-200">{me.name}</p>
              <p className="mt-0.5 truncate font-mono text-xs text-zinc-400 dark:text-zinc-500">{me.id}</p>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={copyId}
              className="h-9 shrink-0 gap-1.5 rounded-full px-3 text-xs font-semibold text-zinc-600 dark:text-zinc-300"
            >
              <Copy className="size-3.5" aria-hidden />
              Copy ID
            </Button>
          </div>
        </Section>

        {/* danger zone */}
        <Section title="Danger zone">
          <Button
            variant="outline"
            onClick={() => setSwitchOpen(true)}
            className="h-11 w-full gap-2 rounded-xl border-destructive/40 text-sm font-semibold text-destructive hover:bg-destructive/10 hover:text-destructive active:scale-[0.98]"
          >
            <LogOut className="size-4" aria-hidden />
            Switch account
          </Button>
        </Section>
      </div>

      {/* saved-messages library drawer */}
      <Drawer open={savedOpen} onOpenChange={setSavedOpen}>
        <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
          <DrawerTitle className="sr-only">Saved messages</DrawerTitle>
          <DrawerDescription className="sr-only">Messages you starred across every chat</DrawerDescription>
          <div className="pb-2">
            <p className="flex items-center justify-center gap-1.5 pb-1 pt-1 text-sm font-bold text-zinc-800 dark:text-zinc-100">
              <Star className="size-4 fill-amber-400 text-amber-500" aria-hidden />
              {savedQuery.isPending ? 'Loading…' : `${(savedQuery.data ?? []).length} saved ${(savedQuery.data ?? []).length === 1 ? 'message' : 'messages'}`}
            </p>
            {(savedQuery.data ?? []).length === 0 && !savedQuery.isPending ? (
              <p className="py-6 text-center text-xs text-zinc-400 dark:text-zinc-500">
                Long-press a message in any chat and choose “Save message”.
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
                      className="w-full rounded-2xl border border-zinc-200 bg-zinc-50/70 p-2.5 text-left outline-none transition-colors hover:border-emerald-300 active:scale-[0.99] dark:border-zinc-700 dark:bg-zinc-800/60 dark:hover:border-emerald-500/50"
                    >
                      <div className="flex items-center gap-2">
                        <UserAvatar name={item.message.sender.name} color={item.message.sender.color} size={22} />
                        <span className="truncate text-xs font-bold text-emerald-700 dark:text-emerald-400">
                          {item.message.sender.id === me.id ? 'You' : item.message.sender.name}
                          <span className="ml-1.5 font-medium text-zinc-400">in {item.conversation.name ?? 'chat'}</span>
                        </span>
                        <span className="ml-auto shrink-0 text-[10px] text-zinc-400">{item.savedAt.slice(0, 10)}</span>
                      </div>
                      <p className="mt-1 line-clamp-2 text-[13px] leading-snug text-zinc-600 dark:text-zinc-300">
                        {item.message.imagePath ? '📷 ' : ''}
                        {item.message.audioPath ? '🎤 ' : ''}
                        {item.message.content.replace(/\s+/g, ' ').trim() || '(media)'}
                      </p>
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

      {/* full settings tree (owned by crew R19-d) */}
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
              onClick={switchAccount}
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

function StatChip({ label, value, accent = false }: { label: string; value: string; accent?: boolean }) {
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-0.5 rounded-2xl border p-3',
        accent
          ? 'border-emerald-500/25 bg-emerald-500/5'
          : 'border-zinc-200 bg-zinc-50 dark:border-zinc-700/70 dark:bg-zinc-800/50',
      )}
    >
      <span
        className={cn(
          'text-base font-bold tracking-tight',
          accent ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-800 dark:text-zinc-100',
        )}
      >
        {value}
      </span>
      <span className="text-[10px] font-medium uppercase tracking-wide text-zinc-400 dark:text-zinc-500">
        {label}
      </span>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title} className="mt-6">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="px-1 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
          {title}
        </h2>
        <Separator className="flex-1" />
      </div>
      <div className="rounded-2xl border border-zinc-200 bg-white p-2 shadow-sm dark:border-zinc-700/70 dark:bg-zinc-800/40">
        {children}
      </div>
    </section>
  )
}

/** Icons for the four nav architectures (verbatim strings for JIT). */
const NAV_ICONS: Record<NavStyleId, typeof Compass> = {
  acrylic: LayoutDashboard,
  rail: PanelRight,
  edge: Compass,
  radial: LayoutGrid,
}

/** Switch the whole shell between the 4 navigation architectures live. */
function NavStyleGrid() {
  const [style, apply] = useNavStyle()
  return (
    <div className="grid grid-cols-2 gap-2 p-1">
      {NAV_STYLE_META.map((opt) => {
        const Icon = NAV_ICONS[opt.id]
        const isActive = style === opt.id
        return (
          <button
            key={opt.id}
            type="button"
            aria-pressed={isActive}
            onClick={() => {
              apply(opt.id)
              haptic(12)
              toast.success(`${opt.label} navigation active`)
            }}
            className={cn(
              'rounded-xl border p-3 text-left transition-all active:scale-[0.98]',
              isActive
                ? 'border-emerald-500 bg-emerald-500/10'
                : 'border-zinc-200 hover:border-emerald-300 dark:border-zinc-700',
            )}
          >
            <Icon className={cn('size-5', isActive ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-500')} aria-hidden />
            <p className="mt-1.5 flex items-center gap-1 text-[13px] font-bold text-zinc-800 dark:text-zinc-100">
              {opt.label}
              {isActive ? <Check className="size-3.5 text-emerald-600 dark:text-emerald-400" aria-hidden /> : null}
            </p>
            <p className="mt-0.5 text-[10px] leading-snug text-zinc-500 dark:text-zinc-400">{opt.hint}</p>
          </button>
        )
      })}
    </div>
  )
}

// ── @handle editor ───────────────────────────────────────────

const HANDLE_MIN = 3
const HANDLE_MAX = 20
const HANDLE_RE = /^[a-z0-9_]+$/
const HANDLE_DEBOUNCE_MS = 350

/** Mirrors normalizeUsername on the server: 3–20 chars, a-z0-9_ */
function isValidHandle(value: string): boolean {
  return value.length >= HANDLE_MIN && value.length <= HANDLE_MAX && HANDLE_RE.test(value)
}

/** Keep only characters the server would accept. */
function sanitizeHandleInput(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '')
    .slice(0, HANDLE_MAX)
}

function HandleEditorDialog({
  open,
  onOpenChange,
  me,
  onSaved,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  me: AppUser
  onSaved: (user: AppUser) => void
}) {
  // body mounts only while open → its state resets naturally on every open
  if (!open) return null
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <HandleEditorBody me={me} onSaved={onSaved} onClose={() => onOpenChange(false)} />
    </Dialog>
  )
}

function HandleEditorBody({
  me,
  onSaved,
  onClose,
}: {
  me: AppUser
  onSaved: (user: AppUser) => void
  onClose: () => void
}) {
  const [value, setValue] = useState(me.username ?? '')
  const [debounced, setDebounced] = useState(me.username ?? '')
  /** suggestion captured from a 409 username_taken response */
  const [clashSuggestion, setClashSuggestion] = useState<string | null>(null)

  const trimmed = value.trim()
  const changed = trimmed !== (me.username ?? '')
  const valid = isValidHandle(trimmed)

  // live availability — debounced, skipped while re-typing the current handle
  const checkEnabled = valid && debounced !== (me.username ?? '')
  const checkQ = useQuery({
    queryKey: ['username-check', debounced],
    queryFn: async (): Promise<{ available: boolean; suggestion: string | null }> => {
      return apiJson<{ available: boolean; suggestion: string | null }>(
        `/api/users/check-username?username=${encodeURIComponent(debounced)}`,
      )
    },
    enabled: checkEnabled,
    staleTime: 5_000,
  })

  const checkStale = !checkEnabled || debounced !== trimmed
  const checking = valid && changed && (checkStale || checkQ.isFetching)
  const available = valid && changed && !checkStale && checkQ.data?.available === true
  const taken = valid && changed && !checkStale && checkQ.data?.available === false

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(trimmed), HANDLE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [trimmed])

  const save = useMutation({
    mutationFn: async (): Promise<AppUser> => {
      const res = await apiJson<{ user: AppUser }>(
        `/api/users/${encodeURIComponent(me.id)}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ username: trimmed }),
        },
      )
      return res.user
    },
    onSuccess: (user) => {
      onSaved(user)
      toast.success(user.username ? `@${user.username} is yours now` : 'Handle saved')
      onClose()
    },
    onError: async (error: Error) => {
      toast.error(error.message || 'Could not save the handle')
      if (error instanceof ApiError && error.status === 409) {
        // the PATCH route's 409 carries a suggestion but ApiError drops it —
        // re-ask the availability endpoint for the nearest free variant
        try {
          const res = await apiJson<{ available: boolean; suggestion: string | null }>(
            `/api/users/check-username?username=${encodeURIComponent(trimmed)}`,
          )
          setClashSuggestion(res.suggestion)
        } catch {
          setClashSuggestion(null)
        }
      }
    },
  })

  return (
    <DialogContent className="max-w-[340px] gap-3 rounded-2xl border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
        <DialogHeader className="text-left">
          <DialogTitle className="text-base tracking-tight">Your @handle</DialogTitle>
          <DialogDescription className="text-xs leading-relaxed">
            3–20 characters: lowercase letters, digits, underscore. Friends can find you by it.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-1.5">
          <div className="relative">
            <span
              aria-hidden
              className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-[15px] font-semibold text-zinc-400 dark:text-zinc-500"
            >
              @
            </span>
            <Input
              value={value}
              onChange={(e) => {
                setClashSuggestion(null)
                setValue(sanitizeHandleInput(e.target.value))
              }}
              placeholder="e.g. alice_chen"
              maxLength={HANDLE_MAX}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="none"
              spellCheck={false}
              enterKeyHint="done"
              aria-label="Your handle"
              aria-invalid={(taken || clashSuggestion !== null) || undefined}
              aria-describedby="handle-editor-availability"
              className={cn(
                'h-11 rounded-xl border-zinc-200 bg-zinc-50 pl-8 text-[15px] focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800',
                (taken || clashSuggestion !== null) &&
                  'border-amber-400 focus-visible:ring-amber-500/50 dark:border-amber-500/60',
              )}
            />
          </div>

          <p
            id="handle-editor-availability"
            role="status"
            aria-live="polite"
            className="flex min-h-[18px] flex-wrap items-center gap-1.5 text-xs font-medium"
          >
            {trimmed.length === 0 ? (
              <span className="text-zinc-400 dark:text-zinc-500">Type a handle, or leave empty.</span>
            ) : !valid ? (
              <span className="text-zinc-500 dark:text-zinc-400">
                {HANDLE_MIN}–{HANDLE_MAX} characters: a-z, 0-9, underscore.
              </span>
            ) : checking ? (
              <span className="flex items-center gap-1.5 text-zinc-500 dark:text-zinc-400">
                <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                Checking @{trimmed}…
              </span>
            ) : available ? (
              <span className="flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400">
                <Check className="size-3.5" strokeWidth={3} aria-hidden />
                @{trimmed} is free!
              </span>
            ) : trimmed === (me.username ?? '') ? (
              <span className="flex items-center gap-1.5 text-zinc-500 dark:text-zinc-400">
                <Check className="size-3.5" strokeWidth={3} aria-hidden />
                That&apos;s your current handle
              </span>
            ) : taken ? (
              <span className="flex flex-wrap items-center gap-1.5 text-amber-600 dark:text-amber-400">
                <X className="size-3.5" strokeWidth={3} aria-hidden />
                @{trimmed} is taken
                {checkQ.data?.suggestion ? (
                  <button
                    type="button"
                    onClick={() => setValue(sanitizeHandleInput(checkQ.data?.suggestion ?? ''))}
                    className="ml-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[11px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/25 active:scale-95 dark:text-amber-300"
                  >
                    Use @{checkQ.data.suggestion}
                  </button>
                ) : null}
              </span>
            ) : clashSuggestion ? (
              <span className="flex flex-wrap items-center gap-1.5 text-amber-600 dark:text-amber-400">
                <X className="size-3.5" strokeWidth={3} aria-hidden />
                @{trimmed} was just taken
                <button
                  type="button"
                  onClick={() => setValue(sanitizeHandleInput(clashSuggestion))}
                  className="ml-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[11px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/25 active:scale-95 dark:text-amber-300"
                >
                  Use @{clashSuggestion}
                </button>
              </span>
            ) : null}
          </p>
        </div>

        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={onClose}
            className="h-10 flex-1 rounded-xl text-sm font-semibold"
          >
            Cancel
          </Button>
          <Button
            onClick={() => save.mutate()}
            disabled={!valid || checking || taken || !changed || save.isPending}
            className="h-10 flex-1 rounded-xl bg-emerald-600 text-sm font-semibold text-white hover:bg-emerald-500 active:scale-[0.98]"
          >
            {save.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              'Save handle'
            )}
          </Button>
        </div>
      </DialogContent>
  )
}

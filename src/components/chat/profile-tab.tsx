// ─────────────────────────────────────────────────────────────
// Pulse Chat — Profile/Settings tab: edit identity, appearance,
// account actions and switch-account flow.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTheme } from 'next-themes'
import { BadgeCheck, Check, Copy, LogOut, LoaderCircle, Moon, Sun } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import {
  AVATAR_GRADIENTS,
  PULSE_COLORS,
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
import { UserAvatar } from '@/components/chat/user-avatar'
import { useMounted } from '@/hooks/use-mounted'

interface UsersResponse {
  user: AppUser
}
interface ConversationsResponse {
  conversations: ConversationSummary[]
}

const NAME_MAX = 32
const ABOUT_MAX = 140

export function ProfileTab({ me }: { me: AppUser }) {
  // remount the editor whenever the underlying identity changes,
  // which re-initializes all local field state (no sync effects)
  const identityKey = `${me.id}|${me.name}|${me.about}|${me.color}`
  return <ProfileEditor key={identityKey} me={me} />
}

function ProfileEditor({ me }: { me: AppUser }) {
  const queryClient = useQueryClient()
  const setUser = usePulseSession((s) => s.setUser)
  const { resolvedTheme, setTheme } = useTheme()
  const mounted = useMounted()

  const [name, setName] = useState(me.name)
  const [about, setAbout] = useState(me.about)
  const [color, setColor] = useState<AvatarColor>((me.color as AvatarColor) ?? 'emerald')
  const [switchOpen, setSwitchOpen] = useState(false)

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
    color !== me.color

  const saveProfile = useMutation({
    mutationFn: async () => {
      return apiJson<UsersResponse>(`/api/users/${encodeURIComponent(me.id)}`, {
        method: 'PATCH',
        body: JSON.stringify({ name: name.trim(), about: about.trim(), color }),
      })
    },
    onMutate: async () => {
      // optimistic update across session store + query caches
      const optimistic: AppUser = { ...me, name: name.trim(), about: about.trim(), color }
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

// ─────────────────────────────────────────────────────────────
// Pulse — Profile tab (R26-d): social-media-style profile.
//
// ROOT PAGE (what others see):
//   • Glass hero — gradient cover from the user's identity color
//     (gradientFor), specular-ring avatar, name + verified-style
//     member badge, tap-to-copy @handle chip, status glyph + text,
//     bio, quick-action pills (Edit profile · Share).
//   • REAL stats row — /api/users/[id]/stats + /api/hub/wallet
//     (messages · rooms · coins · member-since). No mock numbers.
//   • Saved messages (real flow via onOpenSavedMessage) and a
//     compact Account group (copy ID · sign out).
//
// EDIT PROFILE IS A REAL SUB-PAGE (user's #1 fix): a full-screen
// panel portaled above the nav, driven by the hash router —
// open = navigateHash('/profile/edit'), close = backHash(), and
// the hash is read reactively on mount so #/profile/edit
// deep-links and browser back works. The form edits display name,
// bio, status glyph/text, avatar color and @handle, and saves
// through the real PATCH /api/users/[id] with optimistic cache
// updates + toast + rollback.
//
// Appearance / Navigation / Preferences / Data & Storage / About
// were REMOVED from the profile on purpose — Settings owns them.
// Zero emojis — Lucide icons + framer-motion microinteractions.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft,
  AtSign,
  BadgeCheck,
  Check,
  Coins,
  Copy,
  Fingerprint,
  Image as ImageIcon,
  LoaderCircle,
  LogOut,
  Mic,
  Pencil,
  Share2,
  Star,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, SavedItem, UserStats } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import {
  AVATAR_GRADIENTS,
  PULSE_COLORS,
  apiJson,
  gradientFor,
  type AvatarColor,
} from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { ease, pressSpring, pressTap, spring } from '@/lib/motion'
import { useHashNav } from '@/lib/hash-router'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { useMounted } from '@/hooks/use-mounted'
import { haptic } from '@/lib/pulse-settings'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { UserAvatar } from '@/components/chat/user-avatar'
import { AvatarPhotoEditor } from '@/components/profile/avatar-editor'
import { ChevronRow, CountUp, ProfileSection, StatTile, PROFILE_CARD } from '@/components/profile/profile-primitives'
import { STATUS_GLYPH_CHOICES, StatusGlyph } from '@/components/profile/status-glyph'
import { HandleEditorDialog } from '@/components/profile/handle-editor'

const NAME_MAX = 32
const ABOUT_MAX = 140
const STATUS_MAX = 48

interface UsersResponse {
  user: AppUser
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
  const { onlineIds } = usePulseRealtime()
  const mounted = useMounted()

  // ── real sub-page routing — '/profile/edit' is owned here ──
  // open = push the hash, close = history back, and reading the
  // hash on mount makes #/profile/edit a deep-link.
  const { path, navigate, back } = useHashNav()
  const editing = path === '/profile/edit'

  // edit form state (initialized from the committed identity;
  // the identityKey remount re-syncs it after every save)
  const [name, setName] = useState(me.name)
  const [about, setAbout] = useState(me.about)
  const [color, setColor] = useState<AvatarColor>((me.color as AvatarColor) ?? 'emerald')
  const [statusEmoji, setStatusEmoji] = useState(me.statusEmoji ?? '')
  const [statusText, setStatusText] = useState(me.statusText ?? '')

  const [savedOpen, setSavedOpen] = useState(false)
  const [handleOpen, setHandleOpen] = useState(false)
  const [switchOpen, setSwitchOpen] = useState(false)
  /** transient flash on the tap-to-copy handle chip */
  const [handleCopied, setHandleCopied] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    return () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    }
  }, [])

  // ── real data: profile stats + hub wallet + saved library ──
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

  const savedQuery = useQuery({
    queryKey: ['saved', me.id],
    queryFn: async (): Promise<SavedItem[]> => {
      const res = await apiJson<{ items: SavedItem[] }>(`/api/users/${encodeURIComponent(me.id)}/saved`)
      return res.items
    },
    enabled: savedOpen,
  })

  const iAmOnline = onlineIds.has(me.id)
  const gradient = gradientFor(me.color)

  const dirty =
    name.trim() !== me.name.trim() ||
    about.trim() !== me.about.trim() ||
    color !== me.color ||
    statusEmoji.trim() !== (me.statusEmoji ?? '') ||
    statusText.trim() !== (me.statusText ?? '')

  // real save: PATCH /api/users/[id] — optimistic update across the
  // session store + query caches, toast, and rollback on failure
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
      // social-flow: pop the sub-page back to the profile root
      back("/profile")
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

  const canSave = dirty && name.trim().length > 0 && !saveProfile.isPending

  const copyId = async () => {
    haptic(8)
    try {
      await navigator.clipboard.writeText(me.id)
      toast.success('Account ID copied')
    } catch {
      toast.error('Clipboard is unavailable here')
    }
  }

  const copyHandle = async () => {
    if (!me.username) return
    haptic(8)
    try {
      await navigator.clipboard.writeText(`@${me.username}`)
      setHandleCopied(true)
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
      copiedTimer.current = setTimeout(() => setHandleCopied(false), 1600)
      toast.success('Handle copied')
    } catch {
      toast.error('Clipboard is unavailable here')
    }
  }

  /** real share: the OS share sheet when available, clipboard fallback */
  const shareProfile = async () => {
    haptic(10)
    if (!me.username) {
      toast.info('Claim a handle first — it is how people find you')
      setHandleOpen(true)
      return
    }
    const text = `Find me on Pulse — @${me.username}`
    try {
      if (typeof navigator.share === 'function') {
        await navigator.share({ text })
        return
      }
      await navigator.clipboard.writeText(text)
      toast.success('Share text copied')
    } catch (error) {
      // user dismissed the share sheet — not an error
      if (error instanceof DOMException && error.name === 'AbortError') return
      toast.error('Could not share right now')
    }
  }

  const signOut = () => {
    usePulseSession.getState().clear()
    window.location.reload()
  }

  const memberSinceShort = useMemo(() => {
    const d = new Date(me.createdAt)
    return Number.isNaN(d.getTime())
      ? ''
      : d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
  }, [me.createdAt])

  // Escape closes the edit sub-page (only when no dialog sits on top)
  useEffect(() => {
    if (!editing || handleOpen || savedOpen || switchOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") back("/profile")
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [editing, handleOpen, savedOpen, switchOpen, back])

  return (
    <div className="absolute inset-0 flex flex-col">
      {/* ── ROOT PAGE ───────────────────────────────────────── */}
      <div className="pulse-scroll relative min-h-0 flex-1 overflow-y-auto overscroll-contain pb-[calc(104px+env(safe-area-inset-bottom))]">
        {/* ── HERO ── */}
        <section aria-label="Profile" className="relative">
          {/* gradient cover from the identity color + glass sheen */}
          <div
            className={cn(
              'relative isolate h-40 overflow-hidden rounded-b-[28px] bg-gradient-to-br sm:h-48',
              gradient,
            )}
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
            {/* overlapping avatar with specular gradient ring */}
            <motion.div
              initial={reducedMotion ? false : { scale: 0.8, opacity: 0, y: 10 }}
              animate={{ scale: 1, opacity: 1, y: 0 }}
              transition={spring.bouncy}
              className={cn(
                'relative z-10 -mt-12 w-fit rounded-full p-[3px] shadow-lg shadow-black/15',
                'bg-gradient-to-br',
                gradient,
              )}
            >
              <div className="rounded-full bg-white p-[3px] dark:bg-zinc-900">
                <UserAvatar name={me.name} color={me.color} avatar={me.avatar} size={92} showPresence online={iAmOnline} />
              </div>
            </motion.div>

            {/* name + verified-style member badge */}
            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.05 }}
              className="mt-3 flex items-center gap-2"
            >
              <h1 className="truncate text-2xl font-black tracking-tight text-zinc-900 dark:text-zinc-50">
                {me.name}
              </h1>
              <span
                title="Registered member"
                className="flex size-5 shrink-0 items-center justify-center rounded-full"
                aria-label="Registered member"
              >
                <BadgeCheck className="size-5 fill-[var(--ui-accent,#10b981)] text-white dark:text-zinc-900" aria-hidden />
              </span>
            </motion.div>

            {/* @handle — tap to copy (editing lives on the sub-page) */}
            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.09 }}
              className="mt-1.5"
            >
              <motion.button
                type="button"
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                onClick={me.username ? copyHandle : () => setHandleOpen(true)}
                aria-label={
                  me.username ? `Copy handle @${me.username}` : 'Set your handle'
                }
                className="glass-pill flex min-h-[32px] items-center gap-1.5 rounded-full px-3 py-1 text-xs font-bold text-[var(--ui-accent,#10b981)] outline-none"
              >
                <AtSign className="size-3.5" aria-hidden />
                {me.username ? (
                  handleCopied ? (
                    <>
                      <Check className="size-3.5" strokeWidth={3} aria-hidden />
                      Copied
                    </>
                  ) : (
                    `@${me.username}`
                  )
                ) : (
                  'Set your handle'
                )}
              </motion.button>
            </motion.div>

            {/* status glyph + text */}
            {me.statusEmoji || me.statusText ? (
              <motion.p
                initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, ease: ease.out, delay: 0.12 }}
                className="mt-2.5 flex items-center gap-1.5 text-[13px] font-semibold text-zinc-700 dark:text-zinc-200"
              >
                {me.statusEmoji ? (
                  <StatusGlyph value={me.statusEmoji} className="size-4 text-[var(--ui-accent,#10b981)]" />
                ) : null}
                {me.statusText}
              </motion.p>
            ) : null}

            {/* bio */}
            <motion.p
              initial={reducedMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.15 }}
              className="mt-1.5 max-w-md text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400"
            >
              {me.about.trim() || 'No bio yet'}
            </motion.p>

            {/* quick actions — two designed pills, not a toolbox dump */}
            <motion.div
              initial={reducedMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3, ease: ease.out, delay: 0.18 }}
              className="mt-4 flex items-center gap-2"
            >
              <motion.button
                type="button"
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                onClick={() => {
                  haptic(10)
                  navigate('/profile/edit')
                }}
                aria-label="Edit profile"
                className="glass-pill flex h-10 items-center gap-2 rounded-full px-4 text-[13px] font-bold text-[var(--ui-accent,#10b981)] outline-none"
              >
                <Pencil className="size-4" aria-hidden />
                Edit profile
              </motion.button>
              <motion.button
                type="button"
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                onClick={shareProfile}
                aria-label="Share your profile"
                className="glass-pill flex h-10 items-center gap-2 rounded-full px-4 text-[13px] font-bold text-zinc-600 outline-none dark:text-zinc-300"
              >
                <Share2 className="size-4" aria-hidden />
                Share
              </motion.button>
            </motion.div>
          </div>
        </section>

        {/* ── STATS — real data only ── */}
        <section aria-label="Your activity" className="mt-5 px-4">
          <div className="glass-deep glass-sheen isolate grid grid-cols-4 gap-1.5 rounded-3xl p-2">
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

        {/* ── SAVED — real starred-message library ── */}
        <div className="px-4">
          <ProfileSection title="Saved" delay={0.02}>
            <ChevronRow
              icon={Star}
              iconClassName="bg-amber-500/12 text-amber-500"
              title="Saved messages"
              description="Long-press any message in a chat, then Save"
              onPress={() => setSavedOpen(true)}
            />
          </ProfileSection>
        </div>

        {/* ── ACCOUNT ── */}
        <div className="px-4">
          <ProfileSection title="Account" delay={0.06}>
            <div className="p-1">
              <ChevronRow
                icon={Fingerprint}
                title="Copy account ID"
                description={me.id}
                onPress={copyId}
                ariaLabel={`Copy account ID ${me.id}`}
              />
              <ChevronRow
                icon={LogOut}
                destructive
                title="Sign out"
                description="Return to the welcome screen — nothing is deleted"
                onPress={() => setSwitchOpen(true)}
              />
            </div>
          </ProfileSection>
        </div>
      </div>

      {/* ── EDIT PROFILE — real full-screen sub-page (hash-driven) ── */}
      {mounted
        ? createPortal(
            <AnimatePresence>
              {editing ? (
                <motion.div
                  key="edit-profile-subpage"
                  role="dialog"
                  aria-modal="true"
                  aria-label="Edit profile"
                  initial={reducedMotion ? { opacity: 0 } : { x: '100%' }}
                  animate={{ x: 0, opacity: 1 }}
                  exit={reducedMotion ? { opacity: 0, transition: { duration: 0.12 } } : { x: '100%' }}
                  transition={spring.soft}
                  className="fixed inset-0 z-[46] flex flex-col"
                  style={{ background: 'var(--ui-page-bg, #09090b)' }}
                >
                  {/* glass sub-header — back pill + title + save */}
                  <div className="glass-deep glass-sheen isolate flex h-14 shrink-0 items-center gap-2 px-3">
                    <motion.button
                      type="button"
                      whileTap={reducedMotion ? undefined : pressTap}
                      transition={pressSpring}
                      onClick={() => {
                        haptic(10)
                        back("/profile")
                      }}
                      aria-label="Back to profile"
                      className="glass-pill flex h-9 items-center gap-1.5 rounded-full pl-2 pr-3 text-[13px] font-bold text-zinc-700 outline-none dark:text-zinc-200"
                    >
                      <ArrowLeft className="size-4" aria-hidden />
                      Profile
                    </motion.button>
                    <h2 className="text-[15px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                      Edit profile
                    </h2>
                    <div className="flex-1" />
                    <AnimatePresence>
                      {dirty ? (
                        <motion.div
                          initial={{ opacity: 0, scale: 0.9 }}
                          animate={{ opacity: 1, scale: 1 }}
                          exit={{ opacity: 0, scale: 0.9 }}
                          transition={{ duration: 0.15 }}
                        >
                          <Button
                            size="sm"
                            onClick={() => saveProfile.mutate()}
                            disabled={!canSave}
                            aria-label="Save changes"
                            className="h-9 rounded-full bg-[var(--ui-accent,#10b981)] px-3.5 text-xs font-bold text-white hover:opacity-90 active:scale-95"
                          >
                            {saveProfile.isPending ? (
                              <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                            ) : (
                              <Check className="size-3.5" strokeWidth={3} aria-hidden />
                            )}
                            Save
                          </Button>
                        </motion.div>
                      ) : null}
                    </AnimatePresence>
                  </div>

                  {/* form body */}
                  <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[calc(2rem+env(safe-area-inset-bottom))] pt-4">
                    {/* R27-d — profile photo editor above the identity fields */}
                    <AvatarPhotoEditor me={me} />

                    <ProfileSection title="Identity" className="mt-6">
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
                      </div>
                    </ProfileSection>

                    <ProfileSection title="Status">
                      <div className="space-y-2 p-1.5">
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
                          maxLength={STATUS_MAX}
                          placeholder="What's happening? (optional)"
                          onChange={(e) => setStatusText(e.target.value.slice(0, STATUS_MAX))}
                          autoComplete="off"
                          aria-label="Status text"
                          className="h-10 rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-[var(--ui-accent,#10b981)]/60 dark:border-zinc-700 dark:bg-zinc-800"
                        />
                      </div>
                    </ProfileSection>

                    <ProfileSection title="Avatar">
                      <div className="p-1.5">
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
                    </ProfileSection>

                    <ProfileSection title="Handle">
                      <div className="p-1">
                        <ChevronRow
                          icon={AtSign}
                          iconClassName="bg-[color-mix(in_oklab,var(--ui-accent,#10b981)_12%,transparent)] text-[var(--ui-accent,#10b981)]"
                          title="Your handle"
                          description={me.username ? `@${me.username}` : 'Claim yours — friends can find you by it'}
                          onPress={() => setHandleOpen(true)}
                          ariaLabel={me.username ? `Change your handle, currently @${me.username}` : 'Set your handle'}
                        />
                      </div>
                    </ProfileSection>

                    <div className="mt-6">
                      <Button
                        onClick={() => saveProfile.mutate()}
                        disabled={!canSave}
                        className="h-12 w-full rounded-2xl bg-[var(--ui-accent,#10b981)] text-sm font-bold text-white shadow-lg shadow-black/10 hover:opacity-90 active:scale-[0.98] disabled:opacity-50"
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
                      <p className="mt-2.5 text-center text-[11px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                        Changes appear everywhere instantly — profile, chats and mentions.
                      </p>
                    </div>
                  </div>
                </motion.div>
              ) : null}
            </AnimatePresence>,
            document.body,
          )
        : null}

      {/* saved-messages library drawer (real flow) */}
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
            {savedQuery.isPending ? (
              <div role="status" aria-label="Loading saved messages" className="space-y-2 py-1">
                <Skeleton className="h-16 rounded-3xl" />
                <Skeleton className="h-16 rounded-3xl" />
              </div>
            ) : (savedQuery.data ?? []).length === 0 ? (
              <div className="flex flex-col items-center gap-2.5 px-6 py-7 text-center">
                <div className="flex size-12 items-center justify-center rounded-2xl bg-amber-500/12">
                  <Star className="size-6 text-amber-500" aria-hidden />
                </div>
                <p className="max-w-[250px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
                  Long-press a message in any chat and choose Save message.
                </p>
              </div>
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
                      className={cn(PROFILE_CARD, 'glass-row-hover w-full p-2.5 text-left outline-none active:scale-[0.99]')}
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

      {/* sign-out confirm */}
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

// ─────────────────────────────────────────────────────────────
// Pulse — other-user profile sheet (R26-d).
// Opened from chat room member taps, group info rows and the
// contacts list. Immersive glass page-style layout: a deep-glass
// hero (specular rim + glossy sheen) with the user's gradient
// orbs, presence-ring avatar, name + member badge, tap-to-copy
// @handle pill, status glyph + text, bio — then REAL stats from
// /api/users/[id]/stats, mutual rooms from the live conversations
// cache, member-since / last-active footer and the working
// Message action (creates the DM via POST /api/conversations when
// the caller does not supply onMessage).
//
// NEVER-EMPTY CONTRACT: the content remounts per user id, stats
// render skeleton → data → explicit error line (never blank),
// bio/handle have explicit fallbacks, and mutual rooms only mount
// when the overlap is real. If a caller reports "nothing shows",
// audit the CALLER first (see worklog R26-d: chat-room DM bubbles
// render no avatar at all — chat-room.tsx owns that gate).
//
// z-layering: content sits at z-[80] so it also opens above the
// full-screen GroupInfoSheet (z-[70]) when tapping a member row.
// Zero emojis — Lucide icons + framer-motion microinteractions.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BadgeCheck,
  Ban,
  Check,
  Copy,
  Flag,
  LoaderCircle,
  MessageCircle,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary, UserStats } from '@/lib/types'
import { apiJson, formatMemberSince, gradientFor } from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { pressSpring, pressTap, spring } from '@/lib/motion'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { usePulseSession } from '@/lib/pulse-store'
import { Button } from '@/components/ui/button'
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { StatusGlyph } from '@/components/profile/status-glyph'
import { ReportPanel } from '@/components/chat/report-panel'

const itemVariants = {
  hidden: { opacity: 0, y: 14 },
  shown: { opacity: 1, y: 0, transition: spring.snappy },
}

const listVariants = {
  hidden: {},
  shown: { transition: { staggerChildren: 0.05 } },
}

function StatCell({ label, value }: { label: string; value: number }) {
  return (
    <motion.div
      whileHover={{ y: -2 }}
      transition={pressSpring}
      className="rounded-2xl border border-zinc-200/60 bg-white/55 px-2 py-2.5 text-center dark:border-white/[0.06] dark:bg-white/[0.05]"
    >
      <p className="text-base font-bold tabular-nums text-zinc-800 dark:text-zinc-100">
        {value.toLocaleString('en-US')}
      </p>
      <p className="text-[10px] font-medium text-zinc-500 dark:text-zinc-400">{label}</p>
    </motion.div>
  )
}

export function UserProfileSheet({
  user,
  open,
  onOpenChange,
  onMessage,
}: {
  user: AppUser | null
  open: boolean
  onOpenChange: (v: boolean) => void
  /** provided by callers that can navigate (Contacts) — takes priority */
  onMessage?: (userId: string) => void
}) {
  const reducedMotion = useReducedMotion()
  const { onlineIds } = usePulseRealtime()
  const me = usePulseSession((s) => s.user)
  const queryClient = useQueryClient()
  /** transient flash on the copy-handle secondary button */
  const [handleCopied, setHandleCopied] = useState(false)
  /** R48: the report panel expands under the block row */
  const [reportOpen, setReportOpen] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    return () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    }
  }, [])

  // real profile statistics (messages, reactions, photos, voice, chats, groups)
  const statsQ = useQuery({
    queryKey: ['user-stats', user?.id],
    queryFn: async (): Promise<UserStats> => {
      const res = await apiJson<{ stats: UserStats }>(`/api/users/${user?.id}/stats`)
      return res.stats
    },
    enabled: open && user !== null,
    staleTime: 30_000,
  })

  // my conversations → derive the rooms we are BOTH in (real overlap)
  const conversationsQ = useQuery({
    queryKey: ['conversations', me?.id ?? '-'],
    queryFn: async (): Promise<ConversationSummary[]> => {
      const res = await apiJson<{ conversations: ConversationSummary[] }>(
        `/api/conversations?userId=${encodeURIComponent(me?.id ?? '')}`,
      )
      return res.conversations
    },
    enabled: open && !!me,
    staleTime: 10_000,
  })

  const mutualRooms = useMemo(() => {
    if (!user) return []
    return (conversationsQ.data ?? []).filter(
      (c) => c.isGroup && c.members.some((m) => m.id === user.id),
    )
  }, [conversationsQ.data, user])

  // fallback DM creation when the caller cannot navigate the shell
  const dmMutation = useMutation({
    mutationFn: async (): Promise<{ conversation: { id: string } }> => {
      return apiJson<{ conversation: { id: string } }>('/api/conversations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ creatorId: me?.id, memberIds: [user?.id], isGroup: false }),
      })
    },
    onSuccess: () => {
      if (me) void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Direct chat is ready in Chats')
      onOpenChange(false)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not open that chat')
    },
  })

  // R47 — block pair-state (is THIS user blocked by me?) + the toggle.
  const blockQ = useQuery({
    queryKey: ['block-pair', me?.id ?? '-', user?.id ?? '-'],
    queryFn: async (): Promise<boolean> => {
      const res = await apiJson<{ blocked: boolean }>(
        `/api/users/${user?.id}/block?userId=${encodeURIComponent(me?.id ?? '')}`,
      )
      return res.blocked
    },
    enabled: open && !!me && !!user && user.id !== me.id,
    staleTime: 10_000,
  })
  const blockMutation = useMutation({
    mutationFn: async (next: boolean) => {
      if (next) {
        return apiJson<{ ok: boolean }>(`/api/users/${user?.id}/block`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: me?.id }),
        })
      }
      return apiJson<{ ok: boolean }>(
        `/api/users/${user?.id}/block?userId=${encodeURIComponent(me?.id ?? '')}`,
        { method: 'DELETE' },
      )
    },
    onSuccess: (_data, next) => {
      haptic(14)
      if (me && user) {
        void queryClient.invalidateQueries({ queryKey: ['block-pair', me.id, user.id] })
        // the DM composer reads detail.dmBlocked — refresh any live rooms
        void queryClient.invalidateQueries({ queryKey: ['conversation'] })
      }
      toast.success(next ? `Blocked ${user?.name ?? 'account'}` : `Unblocked ${user?.name ?? 'account'}`)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not update the block')
    },
  })

  if (!user) return null

  const gradient = gradientFor(user.color)
  const online = onlineIds.has(user.id)
  const stats = statsQ.data
  const firstName = user.name.split(' ')[0]
  const isSelfView = !me || user.id === me.id

  const copyHandle = async () => {
    if (!user.username) return
    haptic(10)
    try {
      await navigator.clipboard.writeText(`@${user.username}`)
      setHandleCopied(true)
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
      copiedTimer.current = setTimeout(() => setHandleCopied(false), 1600)
      toast.success(`@${user.username} copied`)
    } catch {
      toast.error('Could not copy the handle')
    }
  }

  const copyId = async () => {
    haptic(10)
    try {
      await navigator.clipboard.writeText(user.id)
      toast.success('Account ID copied')
    } catch {
      toast.error('Could not copy the ID')
    }
  }

  const handlePressMessage = () => {
    haptic(12)
    if (onMessage) {
      onMessage(user.id)
      onOpenChange(false)
      return
    }
    if (!me) {
      toast.error('Your session is not ready yet')
      return
    }
    dmMutation.mutate()
  }

  const messagePending = !onMessage && dmMutation.isPending

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="z-[80] max-h-[88dvh]">
        <DrawerHeader className="sr-only">
          <DrawerTitle>{user.name}&apos;s profile</DrawerTitle>
        </DrawerHeader>

        <motion.div
          key={user.id}
          initial="hidden"
          animate="shown"
          variants={listVariants}
          className="-mt-2 flex flex-col gap-3.5 px-4 pb-[max(2rem,env(safe-area-inset-bottom))] pt-1"
        >
          {/* ── glass hero — deep glass + sheen + gradient orbs ── */}
          <motion.div
            variants={itemVariants}
            className="glass-deep glass-sheen relative isolate overflow-hidden rounded-3xl p-4"
          >
            {/* identity-tinted glow orbs behind the content */}
            <span
              aria-hidden
              className={cn(
                'absolute -right-10 -top-12 size-36 rounded-full bg-gradient-to-br opacity-35 blur-2xl',
                gradient,
              )}
            />
            <span
              aria-hidden
              className={cn(
                'absolute -bottom-12 -left-10 size-28 rounded-full bg-gradient-to-tr opacity-25 blur-2xl',
                gradient,
              )}
            />
            <div className="relative flex items-center gap-3.5">
              {/* presence avatar in a specular gradient ring */}
              <motion.div
                initial={reducedMotion ? false : { scale: 0.85, rotate: -4 }}
                animate={{ scale: 1, rotate: 0 }}
                transition={spring.bouncy}
                className={cn('shrink-0 rounded-full bg-gradient-to-br p-[3px] shadow-lg shadow-black/10', gradient)}
              >
                <div className="rounded-full bg-white/90 p-[2px] dark:bg-zinc-900/90">
                  <UserAvatar
                    name={user.name}
                    color={user.color}
                    avatar={user.avatar}
                    size={68}
                    showPresence
                    online={online}
                    className="rounded-full"
                  />
                </div>
              </motion.div>
              <div className="min-w-0 flex-1">
                <p className="flex items-center gap-1.5 text-lg font-bold leading-tight text-zinc-900 dark:text-zinc-50">
                  <span className="truncate">{user.name}</span>
                  <span title="Registered member" aria-label="Registered member" className="shrink-0">
                    <BadgeCheck className="size-4.5 fill-[var(--ui-accent,#10b981)] text-white dark:text-zinc-900" aria-hidden />
                  </span>
                </p>
                {user.username ? (
                  <motion.button
                    type="button"
                    onClick={copyHandle}
                    whileTap={reducedMotion ? undefined : pressTap}
                    transition={pressSpring}
                    aria-label={`Copy handle @${user.username}`}
                    className="glass-pill mt-1.5 flex min-h-[28px] items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-bold text-[var(--ui-accent,#10b981)] outline-none"
                  >
                    {handleCopied ? (
                      <>
                        <Check className="size-3" strokeWidth={3} aria-hidden />
                        Copied
                      </>
                    ) : (
                      <>
                        @{user.username}
                        <Copy className="size-3" aria-hidden />
                      </>
                    )}
                  </motion.button>
                ) : (
                  <p className="mt-1 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">No handle yet</p>
                )}
                <p className="mt-1 flex items-center gap-1 text-[11px] font-semibold text-zinc-500 dark:text-zinc-400">
                  <span
                    aria-hidden
                    className={cn(
                      'inline-block size-1.5 rounded-full',
                      online ? 'bg-[var(--ui-accent,#10b981)]' : 'bg-zinc-300 dark:bg-zinc-600',
                    )}
                  />
                  {online ? 'Online now' : 'Offline'}
                </p>
              </div>
            </div>
            {user.statusEmoji || user.statusText ? (
              <p className="relative mt-3 flex items-center gap-1.5 text-[13px] font-semibold text-zinc-700 dark:text-zinc-200">
                {user.statusEmoji ? <StatusGlyph value={user.statusEmoji} className="size-4 text-[var(--ui-accent,#10b981)]" /> : null}
                {user.statusText}
              </p>
            ) : null}
            <p className="relative mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
              {user.about?.trim() ? user.about : 'No bio yet'}
            </p>
          </motion.div>

          {/* ── real stats ── */}
          <motion.div variants={itemVariants}>
            <p className="mb-2 px-1 text-[11px] font-semibold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
              Activity
            </p>
            {stats ? (
              <div className="grid grid-cols-3 gap-2">
                <StatCell label="messages" value={stats.messages} />
                <StatCell label="reactions" value={stats.reactions} />
                <StatCell label="photos" value={stats.photos} />
                <StatCell label="voice" value={stats.voiceNotes} />
                <StatCell label="chats" value={stats.chats} />
                <StatCell label="groups" value={stats.groups} />
              </div>
            ) : statsQ.isError ? (
              <p className="rounded-xl border border-dashed border-zinc-300 px-3 py-4 text-center text-xs text-zinc-500 dark:border-zinc-700">
                Stats unavailable right now.
              </p>
            ) : (
              <div className="grid grid-cols-3 gap-2">
                {Array.from({ length: 6 }).map((_, i) => (
                  <Skeleton key={i} className="h-[56px] rounded-2xl" />
                ))}
              </div>
            )}
          </motion.div>

          {/* ── mutual rooms (real overlap from my conversations) ── */}
          {me && mutualRooms.length > 0 ? (
            <motion.div
              variants={itemVariants}
              className="glass-deep glass-sheen relative isolate rounded-3xl p-2"
            >
              <p className="px-1.5 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
                Rooms in common
              </p>
              <ul className="space-y-0.5">
                {mutualRooms.slice(0, 3).map((room) => (
                  <li key={room.id}>
                    <div className="glass-row-hover flex min-h-[44px] items-center gap-2.5 rounded-xl px-1.5 py-1.5">
                      <GroupAvatar title={room.name ?? 'Group'} id={room.id} size={32} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13px] font-semibold text-zinc-800 dark:text-zinc-100">
                          {room.name ?? 'Group'}
                        </span>
                        <span className="block text-[11px] text-zinc-400 dark:text-zinc-500">
                          {room.members.length} {room.members.length === 1 ? 'member' : 'members'}
                        </span>
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
              {mutualRooms.length > 3 ? (
                <p className="px-1.5 pb-1.5 pt-0.5 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
                  +{mutualRooms.length - 3} more
                </p>
              ) : null}
            </motion.div>
          ) : null}

          {/* ── member since / last active (real timestamps) ── */}
          <motion.p
            variants={itemVariants}
            className="px-1 text-[11px] leading-relaxed text-zinc-500 dark:text-zinc-400"
          >
            Member since {stats ? formatMemberSince(stats.joinedAt) : formatMemberSince(user.createdAt)}
            {stats ? (
              <>
                <span className="mx-1.5 text-zinc-300 dark:text-zinc-600" aria-hidden>
                  |
                </span>
                {stats.lastSeenAt ? (
                  <>
                    {'Last active '}
                    {new Date(stats.lastSeenAt).toLocaleString('en-US', {
                      month: 'short',
                      day: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                      hourCycle: 'h23',
                    })}
                  </>
                ) : (
                  'Last seen hidden'
                )}
              </>
            ) : null}
          </motion.p>

          {/* ── actions — one primary button + two quiet copy pills ── */}
          <motion.div variants={itemVariants} className="flex items-center gap-2">
            <motion.div
              whileTap={reducedMotion ? undefined : { scale: 0.98 }}
              transition={pressSpring}
              className="flex-1"
            >
              <Button
                className="h-12 w-full rounded-2xl bg-[var(--ui-accent,#10b981)] text-sm font-bold text-white hover:opacity-90"
                onClick={handlePressMessage}
                disabled={messagePending}
              >
                {messagePending ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <MessageCircle className="mr-1.5 size-4" aria-hidden />
                )}
                Message {firstName}
              </Button>
            </motion.div>
            <motion.button
              type="button"
              onClick={copyId}
              whileTap={reducedMotion ? undefined : pressTap}
              transition={pressSpring}
              aria-label="Copy account ID"
              title="Copy account ID"
              className="glass-pill flex size-12 shrink-0 items-center justify-center rounded-2xl text-zinc-500 outline-none dark:text-zinc-300"
            >
              <Copy className="size-4" aria-hidden />
            </motion.button>
            {user.username ? (
              <motion.button
                type="button"
                onClick={copyHandle}
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                aria-label={`Copy handle @${user.username}`}
                title="Copy handle"
                className="glass-pill flex size-12 shrink-0 items-center justify-center rounded-2xl text-[var(--ui-accent,#10b981)] outline-none"
              >
                {handleCopied ? <Check className="size-4" strokeWidth={3} /> : <Copy className="size-4" aria-hidden />}
              </motion.button>
            ) : null}
          </motion.div>

          {/* ── R47 — block / unblock (danger quiet row; toggles in place) + R48 report ── */}
          {!isSelfView ? (
            <>
              <motion.button
                variants={itemVariants}
                type="button"
                onClick={() => blockMutation.mutate(!(blockQ.data ?? false))}
                disabled={blockMutation.isPending || blockQ.isPending}
                aria-pressed={blockQ.data ?? false}
                aria-label={blockQ.data ? `Unblock ${user.name}` : `Block ${user.name}`}
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                className="flex min-h-[44px] items-center justify-center gap-2 rounded-2xl border border-rose-500/25 bg-rose-500/[0.06] px-3 py-2.5 text-[13px] font-bold text-rose-600 outline-none transition-colors hover:bg-rose-500/[0.12] disabled:opacity-50 dark:text-rose-400"
              >
                {blockMutation.isPending || blockQ.isPending ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <Ban className="size-4" aria-hidden />
                )}
                {blockQ.data ? `Unblock ${firstName}` : `Block ${firstName}`}
              </motion.button>
              <motion.button
                variants={itemVariants}
                type="button"
                onClick={() => {
                  haptic(8)
                  setReportOpen((v) => !v)
                }}
                aria-expanded={reportOpen}
                aria-label={`Report ${user.name}`}
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                className="flex min-h-[44px] items-center justify-center gap-2 rounded-2xl border border-amber-500/25 bg-amber-500/[0.06] px-3 py-2.5 text-[13px] font-bold text-amber-700 outline-none transition-colors hover:bg-amber-500/[0.12] dark:text-amber-400"
              >
                <Flag className="size-4" aria-hidden />
                Report {firstName}
              </motion.button>
              {reportOpen ? (
                <motion.div variants={itemVariants}>
                  <ReportPanel
                    reportedId={user.id}
                    reporterId={me?.id}
                    reportedName={user.name}
                    onDone={() => setReportOpen(false)}
                  />
                </motion.div>
              ) : null}
            </>
          ) : null}
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}

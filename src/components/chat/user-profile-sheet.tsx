// ─────────────────────────────────────────────────────────────
// Pulse — other-user profile sheet (R25-b).
// Opened from chat room contact info, group member rows and the
// contacts list. Premium layout: color hero with presence ring,
// name + member badge, tap-to-copy @handle, status, bio, REAL
// stats from /api/users/[id]/stats, mutual rooms from the live
// conversations cache, working Message action (creates the DM via
// POST /api/conversations when the caller does not supply
// onMessage), member-since / last-active footer.
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
  Check,
  Copy,
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
      className="rounded-xl bg-zinc-100/80 px-2 py-2.5 text-center dark:bg-zinc-800/60"
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

  if (!user) return null

  const gradient = gradientFor(user.color)
  const online = onlineIds.has(user.id)
  const stats = statsQ.data
  const firstName = user.name.split(' ')[0]

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
      <DrawerContent className="z-[80] max-h-[85dvh]">
        <DrawerHeader className="sr-only">
          <DrawerTitle>{user.name}&apos;s profile</DrawerTitle>
        </DrawerHeader>

        <motion.div
          key={user.id}
          initial="hidden"
          animate="shown"
          variants={listVariants}
          className="-mt-2 flex flex-col gap-4 px-4 pb-[max(2rem,env(safe-area-inset-bottom))] pt-1"
        >
          {/* ── hero ── */}
          <motion.div
            variants={itemVariants}
            className={cn('relative -mx-4 overflow-hidden px-4 pb-4 pt-5', gradient)}
          >
            <span aria-hidden className="absolute -right-8 -top-10 size-36 rounded-full bg-white/15 blur-2xl" />
            <span aria-hidden className="absolute -left-10 bottom-0 size-28 rounded-full bg-black/10 blur-2xl" />
            <div className="relative flex items-center gap-3.5">
              <motion.div
                initial={reducedMotion ? false : { scale: 0.85, rotate: -4 }}
                animate={{ scale: 1, rotate: 0 }}
                transition={spring.bouncy}
                className="rounded-full bg-white/25 p-[3px]"
              >
                <div className="rounded-full bg-black/10 p-[2px]">
                  <UserAvatar name={user.name} color={user.color} size={72} showPresence online={online} className="rounded-full" />
                </div>
              </motion.div>
              <div className="min-w-0 flex-1 text-white">
                <p className="flex items-center gap-1.5 text-lg font-bold leading-tight">
                  <span className="truncate">{user.name}</span>
                  <span title="Registered member" aria-label="Registered member" className="shrink-0">
                    <BadgeCheck className="size-4.5 fill-white text-black/40" aria-hidden />
                  </span>
                </p>
                {user.username ? (
                  <motion.button
                    type="button"
                    onClick={copyHandle}
                    whileTap={reducedMotion ? undefined : pressTap}
                    transition={pressSpring}
                    aria-label={`Copy handle @${user.username}`}
                    className="mt-1 flex min-h-[28px] items-center gap-1 rounded-full bg-black/25 px-2.5 py-0.5 text-xs font-bold text-white outline-none transition-colors hover:bg-black/35 active:bg-black/45"
                  >
                    @{user.username}
                    <Copy className="size-3" aria-hidden />
                  </motion.button>
                ) : (
                  <p className="mt-1 text-[11px] font-medium text-white/70">No handle yet</p>
                )}
                <p className="mt-0.5 flex items-center gap-1 text-[11px] font-semibold text-white/85">
                  <span
                    aria-hidden
                    className={cn('inline-block size-1.5 rounded-full', online ? 'bg-white' : 'bg-white/40')}
                  />
                  {online ? 'Online now' : 'Offline'}
                </p>
              </div>
            </div>
            {user.statusEmoji || user.statusText ? (
              <p className="relative mt-3 flex items-center gap-1.5 text-[13px] font-semibold text-white/95">
                {user.statusEmoji ? <StatusGlyph value={user.statusEmoji} className="size-4" /> : null}
                {user.statusText}
              </p>
            ) : null}
          </motion.div>

          {/* ── about ── */}
          <motion.div
            variants={itemVariants}
            className="rounded-2xl border border-zinc-200 p-3.5 dark:border-zinc-800"
          >
            <p className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">About</p>
            <p className="mt-1 text-sm leading-relaxed text-zinc-700 dark:text-zinc-200">{user.about}</p>
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
                  <Skeleton key={i} className="h-[56px] rounded-xl" />
                ))}
              </div>
            )}
          </motion.div>

          {/* ── mutual rooms (real overlap from my conversations) ── */}
          {me && mutualRooms.length > 0 ? (
            <motion.div
              variants={itemVariants}
              className="rounded-2xl border border-zinc-200 p-2 dark:border-zinc-800"
            >
              <p className="px-1.5 pb-1 pt-1 text-[11px] font-semibold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
                Rooms in common
              </p>
              <ul className="space-y-0.5">
                {mutualRooms.slice(0, 3).map((room) => (
                  <li key={room.id}>
                    <div className="flex min-h-[44px] items-center gap-2.5 rounded-xl px-1.5 py-1.5">
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
                <p className="px-1.5 pb-1 pt-0.5 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
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
                Last active {new Date(stats.lastSeenAt).toLocaleString('en-US', {
                  month: 'short',
                  day: 'numeric',
                  hour: '2-digit',
                  minute: '2-digit',
                  hourCycle: 'h23',
                })}
              </>
            ) : null}
          </motion.p>

          {/* ── actions ── */}
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
              className="flex size-12 shrink-0 items-center justify-center rounded-2xl border border-zinc-200 text-zinc-500 outline-none transition-colors hover:bg-zinc-100 hover:text-zinc-700 active:bg-zinc-200 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-200"
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
                className="flex size-12 shrink-0 items-center justify-center rounded-2xl border border-zinc-200 text-[var(--ui-accent,#10b981)] outline-none transition-colors hover:bg-zinc-100 active:bg-zinc-200 dark:border-zinc-700 dark:hover:bg-zinc-800"
              >
                {handleCopied ? <Check className="size-4" strokeWidth={3} /> : <Copy className="size-4" aria-hidden />}
              </motion.button>
            ) : null}
          </motion.div>
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}

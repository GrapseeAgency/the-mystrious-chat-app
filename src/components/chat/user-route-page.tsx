// ─────────────────────────────────────────────────────────────
// Pulse — standalone user profile route page (R27-a).
// Mounted globally by the shell; self-driving from the hash:
//   • reads the target id from '#/user/<id>' itself
//   • subscribes to hash changes (useHashRoute → render gate)
//   • back via backHash() (push-depth aware; deep-link safe)
// Full-screen glass social profile: color cover, large avatar
// (photo when the user has one, palette fallback otherwise),
// tap-to-copy @handle chip, status glyph + text, real activity
// stamps, SHARED GROUPS computed from real conversations, and a
// Message action that creates/dedupes a DM (POST /api/conversations).
//
// Room-opening contract (Message / shared-group rows):
//   • pass `onOpenConversation` to hand the conversation id to the
//     shell (the page then backs out so the shell room shows); or
//   • mount with no props — the page hosts <ChatRoom> itself.
// Zero mocks — every value comes from Prisma-backed REST APIs.
// ─────────────────────────────────────────────────────────────
'use client'

import { useContext, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  BadgeCheck,
  CalendarDays,
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  LoaderCircle,
  MessageCircle,
  UserX,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson, formatMemberSince, gradientFor, jsonBody } from '@/lib/pulse-utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { useHashNav } from '@/lib/hash-router'
import { usePulseSession } from '@/lib/pulse-store'
import { PulseRealtimeContext } from '@/hooks/use-pulse-socket'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { ChatRoom } from '@/components/chat/chat-room'
import { StatusGlyph } from '@/components/profile/status-glyph'

const EMPTY_ONLINE_IDS: ReadonlySet<string> = new Set()

interface UsersResponse {
  user: AppUser
}
interface ConversationsResponse {
  conversations: ConversationSummary[]
}
interface CreateConversationResponse {
  conversation: ConversationSummary
}

export interface UserRoutePageProps {
  /**
   * Shell handoff — called with the conversation id after the DM is
   * created/deduped. Omit it to let this page host the chat room itself.
   */
  onOpenConversation?: (conversationId: string, unreadAnchorMs?: number | null) => void
}

/** "Active 5m ago" — real relative stamp from lastSeenAt (refreshes every 30s). */
function activeAgoLabel(iso: string, now: number): string {
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return 'Active a while ago'
  const seconds = Math.max(0, (now - t) / 1000)
  if (seconds < 60) return 'Active just now'
  const minutes = seconds / 60
  if (minutes < 60) return `Active ${Math.floor(minutes)}m ago`
  const hours = minutes / 60
  if (hours < 24) return `Active ${Math.floor(hours)}h ago`
  const days = hours / 24
  if (days < 7) return `Active ${Math.floor(days)}d ago`
  return `Active ${new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(t)}`
}

export default function UserRoutePage(props: UserRoutePageProps = {}) {
  const { path, back } = useHashNav()
  // '#/user/<id>' — self-parsed from the live hash, reactive to changes
  const match = /^\/user\/([A-Za-z0-9_-]+)$/.exec(path)
  const userId = match?.[1] ?? null

  return (
    <AnimatePresence>
      {userId !== null ? (
        <motion.div
          key={`user-page-${userId}`}
          initial={{ opacity: 0, y: 56, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1, transition: spring.soft }}
          exit={{ opacity: 0, y: 44, scale: 0.99, transition: { duration: 0.18, ease: 'easeIn' } }}
          className="absolute inset-0 z-[72] flex flex-col bg-white dark:bg-zinc-900"
          style={{ willChange: 'transform' }}
          role="dialog"
          aria-label="User profile"
        >
          <UserPageBody key={userId} userId={userId} onBack={back} {...props} />
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

function UserPageBody({
  userId,
  onBack,
  onOpenConversation,
}: {
  userId: string
  onBack: () => void
  onOpenConversation?: (conversationId: string, unreadAnchorMs?: number | null) => void
}) {
  const queryClient = useQueryClient()
  const reducedMotion = useReducedMotion()
  const me = usePulseSession((s) => s.user)
  // safe presence read — the page may mount before/outside the realtime provider
  const realtime = useContext(PulseRealtimeContext)
  const onlineIds = realtime?.onlineIds ?? EMPTY_ONLINE_IDS

  const [roomId, setRoomId] = useState<string | null>(null)
  const [handleCopied, setHandleCopied] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [nowTick, setNowTick] = useState(() => Date.now())

  // keep the "Active X ago" stamp honest while the page is open
  useEffect(() => {
    const interval = setInterval(() => setNowTick(Date.now()), 30_000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    return () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    }
  }, [])

  const userQuery = useQuery({
    queryKey: ['user', userId],
    queryFn: async (): Promise<AppUser> => {
      const res = await apiJson<UsersResponse>(`/api/users/${encodeURIComponent(userId)}`)
      return res.user
    },
    staleTime: 15_000,
  })

  const conversations = useQuery({
    queryKey: ['conversations', me?.id ?? '-'],
    enabled: me !== null,
    staleTime: 10_000,
    queryFn: async (): Promise<ConversationSummary[]> => {
      // cache contract: chats-tab stores the UNWRAPPED summary array under this key
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me?.id ?? '')}`,
      )
      return res.conversations
    },
  })

  const user = userQuery.data
  const online = user !== undefined ? onlineIds.has(user.id) : false

  /** groups where BOTH the viewer and this person are members (real overlap) */
  const sharedGroups = useMemo(() => {
    if (!user) return []
    return (conversations.data ?? [])
      .filter((conv) => conv.isGroup && conv.members.some((m) => m.id === user.id))
      .slice(0, 6)
  }, [conversations.data, user])

  /** existing 1:1 DM with this person, when one is already on the wire */
  const existingDm = useMemo(() => {
    if (!user) return null
    return (
      (conversations.data ?? []).find(
        (conv) => !conv.isGroup && !conv.isSelf && conv.members.some((m) => m.id === user.id),
      ) ?? null
    )
  }, [conversations.data, user])

  /** open a known conversation — shell handoff when wired, in-page room otherwise */
  const openRoom = (conversationId: string) => {
    haptic(12)
    if (onOpenConversation) {
      onOpenConversation(conversationId, null)
      // pop this page so the shell-owned room is visible
      onBack()
      return
    }
    setRoomId(conversationId)
  }

  const startDm = useMutation({
    mutationFn: async (): Promise<ConversationSummary> => {
      if (!me || !user) throw new Error('Your session is not ready yet')
      const res = await apiJson<CreateConversationResponse>(
        '/api/conversations',
        jsonBody({ creatorId: me.id, memberIds: [user.id] }),
      )
      return res.conversation
    },
    onSuccess: (conversation) => {
      if (me) void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      openRoom(conversation.id)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not start the chat')
    },
  })

  const copyHandle = async () => {
    if (!user?.username) return
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
    if (!user) return
    haptic(10)
    try {
      await navigator.clipboard.writeText(user.id)
      toast.success('Account ID copied')
    } catch {
      toast.error('Could not copy the ID')
    }
  }

  // ── loading skeleton ──────────────────────────────────────
  if (userQuery.isPending) {
    return (
      <div className="flex min-h-0 flex-1 flex-col" role="status" aria-label="Loading profile">
        <Skeleton className="h-44 w-full shrink-0 rounded-none" />
        <div className="-mt-10 px-4">
          <Skeleton className="size-24 rounded-full" />
          <Skeleton className="mt-4 h-6 w-2/5" />
          <Skeleton className="mt-2 h-5 w-1/3 rounded-full" />
          <Skeleton className="mt-6 h-24 w-full rounded-3xl" />
          <Skeleton className="mt-4 h-20 w-full rounded-3xl" />
        </div>
      </div>
    )
  }

  // ── not found ─────────────────────────────────────────────
  if (userQuery.isError || !user) {
    return (
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-4 px-8 text-center">
        <motion.div
          initial={reducedMotion ? false : { scale: 0.8, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={spring.bouncy}
          className="flex size-16 items-center justify-center rounded-3xl bg-rose-500/10"
        >
          <UserX className="size-8 text-rose-500" aria-hidden />
        </motion.div>
        <div>
          <p className="text-sm font-bold tracking-tight text-zinc-800 dark:text-zinc-100">Profile unavailable</p>
          <p className="mt-1 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
            This Pulse member does not exist (or is no longer here).
          </p>
        </div>
        <Button
          onClick={onBack}
          className="h-10 rounded-full bg-zinc-900 px-5 text-[13px] font-bold text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          Back
        </Button>
      </div>
    )
  }

  const gradient = gradientFor(user.color)
  const firstName = user.name.split(' ')[0]
  const hasStatus = Boolean(user.statusEmoji || user.statusText)

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      {/* floating back — glass pill over the cover, above safe area */}
      <div className="pointer-events-none absolute inset-x-0 top-0 z-30 flex items-start justify-start px-3 pt-[max(0.75rem,env(safe-area-inset-top))]">
        <button
          type="button"
          onClick={() => {
            haptic(8)
            onBack()
          }}
          aria-label="Back"
          className="glass-pill pointer-events-auto flex size-9 items-center justify-center rounded-full text-white outline-none transition-transform duration-150 hover:bg-white/10 active:scale-90"
        >
          <ChevronLeft className="size-[18px]" aria-hidden />
        </button>
      </div>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-[calc(1.5rem+env(safe-area-inset-bottom))]">
        {/* ── color cover from the identity palette ── */}
        <motion.div
          initial={reducedMotion ? false : { opacity: 0, scale: 1.04 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.5, ease: ease.out }}
          className={cn(
            'glass-sheen relative h-44 shrink-0 overflow-hidden bg-gradient-to-br',
            gradient,
          )}
        >
          <span aria-hidden className="absolute -left-10 -top-14 size-40 rounded-full bg-white/20 blur-2xl" />
          <span aria-hidden className="absolute -bottom-16 -right-8 size-44 rounded-full bg-black/20 blur-2xl" />
        </motion.div>

        {/* ── identity ── */}
        <motion.div
          initial={reducedMotion ? false : 'hidden'}
          animate="show"
          className="relative z-10 -mt-12 flex flex-col px-4"
        >
          <motion.div
            variants={{ hidden: { opacity: 0, y: 18 }, show: { opacity: 1, y: 0, transition: spring.soft } }}
            className="flex items-end gap-3"
          >
            <motion.div
              initial={reducedMotion ? false : { scale: 0.85, rotate: -4 }}
              animate={{ scale: 1, rotate: 0 }}
              transition={spring.bouncy}
              className={cn(
                'shrink-0 rounded-full bg-gradient-to-br p-[3px] shadow-lg shadow-black/10',
                gradient,
              )}
            >
              <div className="rounded-full bg-white/90 p-[2px] dark:bg-zinc-900/90">
                <UserAvatar
                  name={user.name}
                  color={user.color}
                  avatar={user.avatar}
                  size={96}
                  showPresence
                  online={online}
                />
              </div>
            </motion.div>
            <div className="glass-deep glass-sheen mb-1 flex min-w-0 flex-1 items-center gap-2 rounded-2xl px-3 py-2">
              <span
                aria-hidden
                className={cn(
                  'size-2 shrink-0 rounded-full',
                  online ? 'bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.6)]' : 'bg-zinc-300 dark:bg-zinc-600',
                )}
              />
              <span className="truncate text-[11px] font-bold uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
                {online ? 'Online now' : activeAgoLabel(user.lastSeenAt, nowTick)}
              </span>
            </div>
          </motion.div>

          <motion.div
            variants={{ hidden: { opacity: 0, y: 14 }, show: { opacity: 1, y: 0, transition: { ...spring.soft, delay: 0.05 } } }}
            className="mt-3"
          >
            <h1 className="flex items-center gap-1.5 text-2xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
              <span className="truncate">{user.name}</span>
              <span title="Registered member" aria-label="Registered member" className="shrink-0">
                <BadgeCheck className="size-5 fill-[var(--ui-accent,#10b981)] text-white dark:text-zinc-900" aria-hidden />
              </span>
            </h1>
            {user.username ? (
              <motion.button
                type="button"
                onClick={copyHandle}
                whileTap={reducedMotion ? undefined : pressTap}
                transition={pressSpring}
                aria-label={`Copy handle @${user.username}`}
                className="glass-pill mt-2 flex min-h-[30px] items-center gap-1.5 rounded-full px-3 py-1 text-xs font-bold text-[var(--ui-accent,#10b981)] outline-none"
              >
                {handleCopied ? (
                  <>
                    <Check className="size-3.5" strokeWidth={3} aria-hidden />
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
              <p className="mt-2 text-[11px] font-medium text-zinc-400 dark:text-zinc-500">No handle yet</p>
            )}
          </motion.div>

          {/* status + about */}
          {hasStatus ? (
            <motion.p
              variants={{ hidden: { opacity: 0, y: 12 }, show: { opacity: 1, y: 0, transition: { ...spring.soft, delay: 0.08 } } }}
              className="mt-3 flex items-center gap-1.5 text-[13px] font-semibold text-zinc-700 dark:text-zinc-200"
            >
              {user.statusEmoji ? (
                <StatusGlyph value={user.statusEmoji} className="size-4 text-[var(--ui-accent,#10b981)]" />
              ) : null}
              {user.statusText}
            </motion.p>
          ) : null}
          <motion.p
            variants={{ hidden: { opacity: 0, y: 12 }, show: { opacity: 1, y: 0, transition: { ...spring.soft, delay: 0.1 } } }}
            className="mt-1.5 text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400"
          >
            {user.about?.trim() ? user.about : 'No bio yet'}
          </motion.p>

          {/* ── real activity stamps ── */}
          <motion.div
            variants={{ hidden: { opacity: 0, y: 14 }, show: { opacity: 1, y: 0, transition: { ...spring.soft, delay: 0.12 } } }}
            className="glass-deep glass-sheen mt-4 overflow-hidden rounded-3xl p-1.5"
          >
            <div className="glass-row-hover flex min-h-[44px] items-center gap-3 rounded-2xl px-3">
              <CalendarDays className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              <span className="text-[13px] font-medium text-zinc-500 dark:text-zinc-400">Member since</span>
              <span className="ml-auto text-[13px] font-bold tabular-nums text-zinc-800 dark:text-zinc-100">
                {formatMemberSince(user.createdAt)}
              </span>
            </div>
            <div aria-hidden className="mx-3 h-px bg-zinc-200/50 dark:bg-white/[0.05]" />
            <div className="glass-row-hover flex min-h-[44px] items-center gap-3 rounded-2xl px-3">
              <Activity className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              <span className="text-[13px] font-medium text-zinc-500 dark:text-zinc-400">Last seen</span>
              <span className="ml-auto text-[13px] font-bold tabular-nums text-zinc-800 dark:text-zinc-100">
                {online ? 'Online now' : activeAgoLabel(user.lastSeenAt, nowTick)}
              </span>
            </div>
          </motion.div>

          {/* ── shared rooms (real membership overlap) ── */}
          <motion.div
            variants={{ hidden: { opacity: 0, y: 14 }, show: { opacity: 1, y: 0, transition: { ...spring.soft, delay: 0.15 } } }}
            className="glass-deep glass-sheen mt-3 overflow-hidden rounded-3xl p-1.5"
          >
            <p className="px-2.5 pb-1 pt-2 text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500 dark:text-zinc-400">
              Shared groups
            </p>
            {sharedGroups.length === 0 ? (
              <p className="px-2.5 pb-2.5 pt-1 text-[12px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                No shared groups yet — say hello in a room you both joined.
              </p>
            ) : (
              <ul>
                {sharedGroups.map((room, i) => (
                  <motion.li
                    key={room.id}
                    initial={reducedMotion ? false : { opacity: 0, x: 12 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ ...spring.snappy, delay: stagger(i, 0.04, 6) }}
                  >
                    <motion.button
                      type="button"
                      onClick={() => openRoom(room.id)}
                      aria-label={`Open shared group ${room.name ?? 'Group'}`}
                      whileTap={reducedMotion ? undefined : pressTap}
                      transition={pressSpring}
                      className="glass-row-hover flex min-h-[52px] w-full items-center gap-3 rounded-2xl px-2 py-1.5 text-left outline-none"
                    >
                      <GroupAvatar title={room.name ?? 'Group'} id={room.id} size={36} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13px] font-semibold text-zinc-800 dark:text-zinc-100">
                          {room.name ?? 'Group'}
                        </span>
                        <span className="block text-[11px] text-zinc-400 dark:text-zinc-500">
                          {room.members.length} {room.members.length === 1 ? 'member' : 'members'}
                        </span>
                      </span>
                      <ChevronRight className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
                    </motion.button>
                  </motion.li>
                ))}
              </ul>
            )}
            {existingDm ? (
              <>
                <div aria-hidden className="mx-2.5 h-px bg-zinc-200/50 dark:bg-white/[0.05]" />
                <motion.button
                  type="button"
                  onClick={() => openRoom(existingDm.id)}
                  aria-label={`Continue your direct chat with ${user.name}`}
                  whileTap={reducedMotion ? undefined : pressTap}
                  transition={pressSpring}
                  className="glass-row-hover flex min-h-[52px] w-full items-center gap-3 rounded-2xl px-2 py-1.5 text-left outline-none"
                >
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-emerald-500/10">
                    <MessageCircle className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
                  </span>
                  <span className="min-w-0 flex-1 text-[13px] font-semibold text-zinc-800 dark:text-zinc-100">
                    Continue direct chat
                  </span>
                  <ChevronRight className="size-4 shrink-0 text-zinc-300 dark:text-zinc-600" aria-hidden />
                </motion.button>
              </>
            ) : null}
          </motion.div>

          {/* ── actions ── */}
          <motion.div
            variants={{ hidden: { opacity: 0, y: 14 }, show: { opacity: 1, y: 0, transition: { ...spring.soft, delay: 0.18 } } }}
            className="mt-4 flex items-center gap-2"
          >
            <motion.div whileTap={reducedMotion ? undefined : { scale: 0.98 }} transition={pressSpring} className="flex-1">
              <Button
                className="h-12 w-full rounded-2xl bg-[var(--ui-accent,#10b981)] text-sm font-bold text-white hover:opacity-90"
                onClick={() => startDm.mutate()}
                disabled={!me || startDm.isPending}
              >
                {startDm.isPending ? (
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
          </motion.div>
          {!me ? (
            <p className="mt-2 text-center text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
              Sign in to start a chat with {firstName}.
            </p>
          ) : null}
        </motion.div>
      </div>

      {/* ── in-page room host (used when the shell handoff prop is not wired) ── */}
      <AnimatePresence>
        {roomId !== null && me ? (
          <motion.div
            key="user-page-room"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1, transition: { duration: 0.14 } }}
            exit={{ opacity: 0, transition: { duration: 0.12 } }}
            className="absolute inset-0 z-[60]"
          >
            <ChatRoom me={me} conversationId={roomId} unreadAnchorMs={null} onClose={() => setRoomId(null)} />
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  )
}

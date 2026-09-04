// ─────────────────────────────────────────────────────────────
// Pulse Chat — Contacts tab: my identity strip + everyone list.
// Tapping a person opens (or creates) a direct conversation.
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronRight, MessageCircle, Search, UserPlus, UsersRound, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Badge } from '@/components/ui/badge'
import { UserAvatar } from '@/components/chat/user-avatar'
import { UserProfileSheet } from '@/components/chat/user-profile-sheet'

interface UsersResponse {
  users: AppUser[]
}
interface CreateConversationResponse {
  conversation: ConversationSummary
}

const IS_NEW_WINDOW_MS = 30_000

/** Pulsing emerald presence halo behind online avatars (spring.gentle loop). */
function PresenceGlow({ reduced }: { reduced: boolean }) {
  if (reduced) {
    return <span aria-hidden className="absolute -inset-[3px] rounded-full ring-2 ring-emerald-400/50" />
  }
  return (
    <motion.span
      aria-hidden
      initial={{ scale: 1, opacity: 0.65 }}
      animate={{ scale: 1.14, opacity: 0.18 }}
      transition={{ ...spring.gentle, repeat: Infinity, repeatType: 'reverse' }}
      className="absolute -inset-[3px] rounded-full ring-2 ring-emerald-400/60 shadow-[0_0_14px_rgba(16,185,129,0.35)]"
    />
  )
}

export function ContactsTab({
  me,
  onOpenConversation,
  onGoProfile,
  onRequestNewGroup,
}: {
  me: AppUser
  onOpenConversation: (conversationId: string, unreadAnchorMs?: number | null) => void
  onGoProfile: () => void
  onRequestNewGroup: () => void
}) {
  const queryClient = useQueryClient()
  const [nowTick, setNowTick] = useState(() => Date.now())
  const [profileUser, setProfileUser] = useState<AppUser | null>(null)
  const [searching, setSearching] = useState(false)
  const [searchFocused, setSearchFocused] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const reducedMotion = useReducedMotion()
  const onlineIds = usePulseRealtime().onlineIds

  /**
   * Entrance stagger plays ONLY on the tab's first list render — the flag flips
   * after the first commit that has rows, so refetches never re-animate.
   */
  const [entranceOn, setEntranceOn] = useState(true)

  // lightweight ticker so the "NEW" badge expires itself
  useEffect(() => {
    const interval = setInterval(() => setNowTick(Date.now()), 5_000)
    return () => clearInterval(interval)
  }, [])

  const users = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<UsersResponse>('/api/users')
      return res.users
    },
    refetchInterval: 30_000,
    staleTime: 15_000,
  })

  useEffect(() => {
    if ((users.data ?? []).length > 1) {
      const t = setTimeout(() => setEntranceOn(false), 0)
      return () => clearTimeout(t)
    }
  })

  const others = useMemo(
    () => (users.data ?? []).filter((u) => u.id !== me.id),
    [users.data, me.id],
  )

  /** local, real-data search over the loaded people list (no extra endpoint) */
  const visiblePeople = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return others
    return others.filter((p) =>
      p.name.toLowerCase().includes(q) ||
      (p.username ?? '').toLowerCase().includes(q) ||
      p.about.toLowerCase().includes(q) ||
      [p.statusEmoji, p.statusText].filter(Boolean).join(' ').toLowerCase().includes(q),
    )
  }, [others, searchQuery])

  const closeSearch = useCallback(() => {
    setSearching(false)
    setSearchFocused(false)
    setSearchQuery('')
  }, [])

  const startDm = useMutation({
    mutationFn: async (person: AppUser): Promise<ConversationSummary> => {
      const res = await apiJson<CreateConversationResponse>(
        '/api/conversations',
        jsonBody({ creatorId: me.id, memberIds: [person.id] }),
      )
      return res.conversation
    },
    onSuccess: (conversation) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      onOpenConversation(conversation.id, null)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not start the chat')
    },
  })

  return (
    <div className="absolute inset-0 flex flex-col bg-white dark:bg-zinc-900">
      <header className="shrink-0 border-b border-zinc-200 pt-[max(0.75rem,env(safe-area-inset-top))] dark:border-zinc-800">
        {searching ? (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="flex items-center gap-2 px-3 pb-2.5"
          >
            {/* glass pill — the search icon expands into the full input */}
            <motion.div
              initial={{ opacity: 0, scale: 0.96 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.2, ease: ease.out }}
              className="relative flex h-10 min-w-0 flex-1 items-center gap-2.5 rounded-full bg-zinc-100/80 px-4 ring-1 ring-zinc-200/70 backdrop-blur-xl dark:bg-zinc-900/60 dark:ring-white/10"
            >
              <motion.span
                aria-hidden
                animate={{ opacity: searchFocused ? 1 : 0 }}
                transition={spring.soft}
                className="pointer-events-none absolute inset-0 rounded-full shadow-[0_0_20px_rgba(16,185,129,0.25)] ring-2 ring-emerald-500/50"
              />
              <Search className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              <motion.div
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.2, ease: ease.out }}
                className="min-w-0 flex-1"
              >
                <Input
                  autoFocus
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onFocus={() => setSearchFocused(true)}
                  onBlur={() => setSearchFocused(false)}
                  placeholder="Search people…"
                  aria-label="Search people"
                  className="h-full border-0 bg-transparent p-0 text-sm shadow-none focus-visible:ring-0 dark:bg-transparent"
                />
              </motion.div>
              <AnimatePresence initial={false}>
                {searchQuery ? (
                  <motion.button
                    key="clear-people-search"
                    type="button"
                    initial={{ scale: 0, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    exit={{ scale: 0, opacity: 0 }}
                    transition={spring.bouncy}
                    onClick={() => setSearchQuery('')}
                    aria-label="Clear search"
                    className="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-300/70 text-zinc-600 outline-none dark:bg-zinc-700 dark:text-zinc-300"
                  >
                    <X className="size-3.5" aria-hidden />
                  </motion.button>
                ) : null}
              </AnimatePresence>
            </motion.div>
            <motion.span
              initial={{ opacity: 0, scale: 0.6 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={spring.bouncy}
            >
              <Button
                variant="ghost"
                size="icon"
                aria-label="Close search"
                onClick={closeSearch}
                className="size-10 shrink-0 rounded-full text-zinc-500 hover:text-zinc-700 active:scale-95 dark:hover:text-zinc-300"
              >
                <X className="size-5" aria-hidden />
              </Button>
            </motion.span>
          </motion.div>
        ) : (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.2, ease: ease.out }}
            className="flex items-center gap-2 px-4 pb-3"
          >
            <h1 className="mr-auto text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">People</h1>
            <motion.button
              type="button"
              aria-label="Start searching"
              onClick={() => setSearching(true)}
              whileTap={pressTap}
              transition={pressSpring}
              className="flex size-10 items-center justify-center rounded-full bg-zinc-100/80 text-zinc-400 outline-none ring-1 ring-zinc-200/70 backdrop-blur-xl transition-colors hover:text-zinc-600 dark:bg-zinc-900/60 dark:ring-white/10 dark:text-zinc-500 dark:hover:text-zinc-300"
            >
              <Search className="size-[18px]" aria-hidden />
            </motion.button>
            <Button
              variant="ghost"
              size="sm"
              onClick={onRequestNewGroup}
              className="h-9 gap-1.5 rounded-full px-3 text-[13px] font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 dark:text-emerald-400 dark:hover:text-emerald-400"
            >
              <UserPlus className="size-[18px]" aria-hidden />
              New group
            </Button>
          </motion.div>
        )}
      </header>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-6">
        {/* my identity strip */}
        {users.isPending ? (
          <div className="px-4 pt-3">
            <Skeleton className="h-[72px] w-full rounded-3xl" />
          </div>
        ) : !searching ? (
          <motion.div
            initial={entranceOn ? { opacity: 0, y: 14 } : false}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.32, ease: ease.out, delay: 0 }}
            className="px-3 pt-3"
          >
            <motion.button
              type="button"
              onClick={onGoProfile}
              aria-label="Edit my profile"
              initial="rest"
              animate="rest"
              whileTap="tap"
              variants={{ rest: { scale: 1 }, tap: { scale: 0.98 } }}
              transition={pressSpring}
              className="flex w-full touch-manipulation items-center gap-3 rounded-3xl border border-zinc-200/70 bg-white/70 p-3 text-left shadow-[inset_0_1px_0_rgba(255,255,255,0.35)] outline-none backdrop-blur-2xl transition-colors hover:border-emerald-300/60 active:bg-zinc-50 dark:border-white/10 dark:bg-zinc-900/60 dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.06)] dark:active:bg-zinc-800/80"
            >
              <span className="relative shrink-0">
                <PresenceGlow reduced={reducedMotion === true} />
                <UserAvatar name={me.name} color={me.color} size={44} showPresence online />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-100">
                  {me.name}
                </span>
                <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">{me.about}</span>
              </span>
              <motion.span variants={{ rest: { x: 0 }, tap: { x: 2 } }} className="shrink-0">
                <ChevronRight className="size-4 text-zinc-400" aria-hidden />
              </motion.span>
            </motion.button>
          </motion.div>
        ) : null}

        {/* everyone */}
        <h2 className="sticky top-0 z-10 mt-4 bg-gradient-to-b from-white via-white to-transparent px-4 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:from-zinc-900 dark:via-zinc-900 dark:text-zinc-400">
          {searching && searchQuery.trim() ? 'Results' : 'Everyone'}
        </h2>

        {users.isPending ? (
          <div role="status" aria-label="Loading people" className="pt-1">
            <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
          </div>
        ) : visiblePeople.length === 0 ? (
          searching && searchQuery.trim() ? (
            <motion.div
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2, ease: ease.out }}
              className="flex flex-col items-center justify-center gap-2 px-8 pt-14 text-center"
            >
              <Search className="size-8 text-zinc-300 dark:text-zinc-600" aria-hidden />
              <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">No matches</p>
              <p className="text-xs text-zinc-400 dark:text-zinc-500">
                Nobody here for “{searchQuery.trim()}”.
              </p>
            </motion.div>
          ) : (
            <EmptyPeople />
          )
        ) : (
          <ul className="pb-2">
            {visiblePeople.map((person, i) => (
              <PersonRow
                key={person.id}
                person={person}
                online={onlineIds.has(person.id)}
                isNew={nowTick - new Date(person.createdAt).getTime() < IS_NEW_WINDOW_MS}
                disabled={startDm.isPending}
                entranceIndex={entranceOn ? i : null}
                onPress={() => startDm.mutate(person)}
                onAvatarPress={() => setProfileUser(person)}
              />
            ))}
          </ul>
        )}
      </div>

      <UserProfileSheet
        user={profileUser}
        open={profileUser !== null}
        onOpenChange={(v) => {
          if (!v) setProfileUser(null)
        }}
        onMessage={(userId) => {
          const person = others.find((u) => u.id === userId)
          if (person) startDm.mutate(person)
        }}
      />
    </div>
  )
}

function PersonRow({
  person,
  online,
  isNew,
  disabled,
  entranceIndex,
  onPress,
  onAvatarPress,
}: {
  person: AppUser
  online: boolean
  isNew: boolean
  disabled: boolean
  entranceIndex: number | null
  onPress: () => void
  onAvatarPress: () => void
}) {
  const reducedMotion = useReducedMotion()
  const entrance = entranceIndex !== null && !reducedMotion
  return (
    <motion.li
      initial={entrance ? { opacity: 0, y: 14 } : false}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        duration: 0.32,
        ease: ease.out,
        delay: entrance ? stagger(entranceIndex ?? 0, 0.028, 12) : 0,
      }}
      className="px-2"
    >
      <motion.div
        whileTap={reducedMotion ? undefined : { scale: 0.975 }}
        transition={pressSpring}
        className="group relative flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5"
      >
        {/* press tint flash (theme-aware white/5) */}
        <span
          aria-hidden
          className="pointer-events-none absolute inset-0 rounded-2xl bg-zinc-900/[0.04] opacity-0 transition-opacity duration-100 group-active:opacity-100 dark:bg-white/5"
        />
        <button
          type="button"
          onClick={onAvatarPress}
          aria-label={`View ${person.name}'s profile`}
          className="relative shrink-0 rounded-full outline-none"
        >
          {online ? <PresenceGlow reduced={reducedMotion === true} /> : null}
          <UserAvatar name={person.name} color={person.color} size={44} showPresence online={online} />
        </button>
        <button
          type="button"
          onClick={onPress}
          disabled={disabled}
          aria-label={`Chat with ${person.name}`}
          className="flex min-w-0 flex-1 items-center gap-3 text-left outline-none"
        >
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-1.5">
              <span className="truncate text-sm font-medium tracking-tight text-zinc-900 dark:text-zinc-100">
                {person.name}
              </span>
              {person.username ? (
                <span className="truncate text-[10px] font-semibold text-emerald-600 dark:text-emerald-400">
                  @{person.username}
                </span>
              ) : null}
              {isNew ? (
                <motion.span
                  initial={reducedMotion ? false : { scale: 0, opacity: 0 }}
                  animate={{ scale: 1, opacity: 1 }}
                  transition={spring.bouncy}
                  className="inline-flex"
                >
                  <Badge className="h-4 border-none bg-emerald-100 px-1.5 text-[9px] font-bold uppercase tracking-wide text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-400">
                    New
                  </Badge>
                </motion.span>
              ) : null}
            </span>
            <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">
              {person.statusEmoji || person.statusText
                ? [person.statusEmoji, person.statusText].filter(Boolean).join(' ')
                : person.about}
            </span>
          </span>
        </button>
        <Button
          variant="ghost"
          size="icon"
          aria-label={`Message ${person.name}`}
          disabled={disabled}
          onClick={onPress}
          className="size-9 shrink-0 rounded-full text-zinc-400 hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 dark:hover:text-emerald-400"
        >
          <MessageCircle className="size-[18px]" aria-hidden />
        </Button>
      </motion.div>
      <div aria-hidden className="ml-[60px] h-px bg-zinc-100 dark:bg-zinc-800" />
    </motion.li>
  )
}

function RowSkeleton() {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <Skeleton className="size-11 rounded-full" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-3.5 w-1/4" />
        <Skeleton className="h-3 w-1/2" />
      </div>
    </div>
  )
}

function EmptyPeople() {
  return (
    <div className="mx-3 mt-2 flex flex-col items-center gap-3 rounded-3xl border border-dashed border-zinc-200 p-8 text-center dark:border-zinc-700">
      <div className="flex size-14 items-center justify-center rounded-2xl bg-emerald-500/10">
        <UsersRound className="size-7 text-emerald-500" aria-hidden />
      </div>
      <div>
        <p className="text-sm font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">It's quiet in here</p>
        <p className="mx-auto mt-1 max-w-[250px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
          No other humans yet. Open this preview in a second browser tab, sign up another account, and it will appear here instantly.
        </p>
      </div>
    </div>
  )
}

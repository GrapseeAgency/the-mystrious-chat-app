// ─────────────────────────────────────────────────────────────
// Pulse — #/contacts/add sub-page (R27-a).
// Renders INSIDE the contacts tab when the hash matches
// (same internal-hash pattern as the settings tree). Real
// search-as-you-type over GET /api/users (client filter on the
// shared ['users'] cache — real data, no mocks), keyboard-focused
// glass search field, presence dots, and a Message action that
// creates/dedupes a DM via POST /api/conversations and opens the
// room through the shell's onOpenConversation contract.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, LoaderCircle, MessageCircle, Search, UserPlus, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { ease, pressSpring, pressTap, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { backHash, navigateHash } from '@/lib/hash-router'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { cn } from '@/lib/utils'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { UserAvatar } from '@/components/chat/user-avatar'

interface UsersResponse {
  users: AppUser[]
}
interface CreateConversationResponse {
  conversation: ConversationSummary
}

export function ContactsAddPage({
  me,
  onOpenConversation,
  dmByUserId,
}: {
  me: AppUser
  onOpenConversation: (conversationId: string, unreadAnchorMs?: number | null) => void
  /** real DM-membership marks computed by the contacts root (shared cache) */
  dmByUserId: Map<string, string>
}) {
  const queryClient = useQueryClient()
  const reducedMotion = useReducedMotion()
  const onlineIds = usePulseRealtime().onlineIds
  const [searchQuery, setSearchQuery] = useState('')
  const [searchFocused, setSearchFocused] = useState(false)

  const users = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<UsersResponse>('/api/users')
      return res.users
    },
    staleTime: 15_000,
  })

  const others = useMemo(
    () => (users.data ?? []).filter((u) => u.id !== me.id),
    [users.data, me.id],
  )

  /** real search-as-you-type over the loaded people list */
  const results = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    if (!q) return others
    return others.filter(
      (p) =>
        p.name.toLowerCase().includes(q) ||
        (p.username ?? '').toLowerCase().includes(q) ||
        p.about.toLowerCase().includes(q),
    )
  }, [others, searchQuery])

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
      haptic(14)
      // return to the indexed root, then the shell opens the room above it
      backHash()
      onOpenConversation(conversation.id, null)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not start the chat')
    },
  })

  const goBack = () => {
    haptic(8)
    backHash()
  }

  const openPerson = (person: AppUser) => {
    haptic(8)
    navigateHash(`/user/${person.id}`)
  }

  return (
    <motion.div
      initial={reducedMotion ? { opacity: 0 } : { opacity: 0, x: 44 }}
      animate={{ opacity: 1, x: 0, transition: spring.soft }}
      exit={reducedMotion ? { opacity: 0, transition: { duration: 0.12 } } : { opacity: 0, x: 32, transition: { duration: 0.16, ease: 'easeIn' } }}
      className="absolute inset-0 z-20 flex flex-col bg-white dark:bg-zinc-900"
      role="dialog"
      aria-label="Add contact"
    >
      {/* ── glass sub-header: back + focused search field ── */}
      <div className="glass-deep glass-sheen flex h-14 shrink-0 items-center gap-2 px-2.5">
        <button
          type="button"
          onClick={goBack}
          aria-label="Back to contacts"
          className="glass-pill flex size-9 shrink-0 items-center justify-center text-zinc-600 outline-none transition-transform duration-150 hover:text-zinc-900 active:scale-90 dark:text-zinc-300 dark:hover:text-white"
        >
          <ChevronLeft className="size-[18px]" aria-hidden />
        </button>
        <div className="relative flex h-10 min-w-0 flex-1 items-center gap-2.5 rounded-full bg-zinc-100/80 px-3.5 ring-1 ring-zinc-200/70 backdrop-blur-xl dark:bg-zinc-900/60 dark:ring-white/10">
          <motion.span
            aria-hidden
            initial={false}
            animate={{ opacity: searchFocused ? 1 : 0 }}
            transition={spring.soft}
            className="pointer-events-none absolute inset-0 rounded-full ring-2 ring-emerald-500/50 shadow-[0_0_20px_rgba(16,185,129,0.25)]"
          />
          <Search className="size-4 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
          <Input
            autoFocus
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onFocus={() => setSearchFocused(true)}
            onBlur={() => setSearchFocused(false)}
            placeholder="Search people by name or handle…"
            aria-label="Search people"
            className="h-full border-0 bg-transparent p-0 text-sm shadow-none focus-visible:ring-0 dark:bg-transparent"
          />
          <AnimatePresence initial={false}>
            {searchQuery ? (
              <motion.button
                key="clear-add-search"
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
        </div>
      </div>

      {/* ── results ── */}
      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-2 pb-6 pt-2">
        <p className="px-2 pb-2 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:text-zinc-400">
          {searchQuery.trim() ? `${results.length} ${results.length === 1 ? 'match' : 'matches'}` : 'Everyone'}
        </p>

        {users.isPending ? (
          <div role="status" aria-label="Loading people">
            <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
          </div>
        ) : results.length === 0 ? (
          searchQuery.trim() ? (
            <motion.div
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2, ease: ease.out }}
              className="flex flex-col items-center justify-center gap-2 px-8 pt-12 text-center"
            >
              <Search className="size-8 text-zinc-300 dark:text-zinc-600" aria-hidden />
              <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">No matches</p>
              <p className="text-xs text-zinc-400 dark:text-zinc-500">
                Nobody here for “{searchQuery.trim()}”.
              </p>
            </motion.div>
          ) : (
            <motion.div
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.2, ease: ease.out }}
              className="mx-1 flex flex-col items-center gap-3 rounded-3xl border border-dashed border-zinc-200 p-8 text-center dark:border-zinc-700"
            >
              <div className="flex size-14 items-center justify-center rounded-2xl bg-emerald-500/10">
                <UserPlus className="size-7 text-emerald-500" aria-hidden />
              </div>
              <div>
                <p className="text-sm font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">No one to add yet</p>
                <p className="mx-auto mt-1 max-w-[250px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
                  New Pulse members show up here the moment they join.
                </p>
              </div>
            </motion.div>
          )
        ) : (
          <ul>
            {results.map((person, i) => (
              <AddResultRow
                key={person.id}
                person={person}
                online={onlineIds.has(person.id)}
                sharesDm={dmByUserId.has(person.id)}
                pending={startDm.isPending}
                entranceIndex={i}
                reduced={reducedMotion === true}
                onPress={() => openPerson(person)}
                onMessage={() => startDm.mutate(person)}
              />
            ))}
          </ul>
        )}
      </div>
    </motion.div>
  )
}

function AddResultRow({
  person,
  online,
  sharesDm,
  pending,
  entranceIndex,
  reduced,
  onPress,
  onMessage,
}: {
  person: AppUser
  online: boolean
  sharesDm: boolean
  pending: boolean
  entranceIndex: number
  reduced: boolean
  onPress: () => void
  onMessage: () => void
}) {
  return (
    <motion.li
      initial={reduced ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        duration: 0.26,
        ease: ease.out,
        delay: stagger(entranceIndex, 0.024, 12),
      }}
      className="px-1"
    >
      <div className="glass-row-hover flex items-center gap-3 rounded-2xl px-2 py-2">
        <motion.button
          type="button"
          onClick={onPress}
          aria-label={`Open ${person.name}'s profile`}
          whileTap={reduced ? undefined : pressTap}
          transition={pressSpring}
          className="relative flex min-w-0 flex-1 touch-manipulation items-center gap-3 text-left outline-none"
        >
          <span className="relative shrink-0">
            <UserAvatar name={person.name} color={person.color} avatar={person.avatar} size={44} showPresence online={online} />
            {online ? (
              <span aria-hidden className="absolute -inset-[3px] rounded-full ring-2 ring-emerald-400/50" />
            ) : null}
          </span>
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
              {sharesDm ? (
                <span className="inline-flex h-4 shrink-0 items-center gap-1 rounded-full bg-emerald-500/10 px-1.5 text-[9px] font-bold uppercase tracking-wide text-emerald-600 ring-1 ring-emerald-500/20 dark:text-emerald-400">
                  <MessageCircle className="size-2.5" aria-hidden />
                  Chat
                </span>
              ) : null}
            </span>
            <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">
              {online ? 'Online now' : person.about}
            </span>
          </span>
        </motion.button>
        <motion.button
          type="button"
          onClick={onMessage}
          disabled={pending}
          aria-label={`Message ${person.name}`}
          whileTap={reduced ? undefined : pressTap}
          transition={pressSpring}
          className={cn(
            'glass-pill flex size-10 shrink-0 items-center justify-center rounded-full text-emerald-600 outline-none',
            'hover:bg-emerald-500/10 active:scale-95 disabled:opacity-40 dark:text-emerald-400',
          )}
        >
          {pending ? (
            <LoaderCircle className="size-[18px] animate-spin" aria-hidden />
          ) : (
            <MessageCircle className="size-[18px]" aria-hidden />
          )}
        </motion.button>
      </div>
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

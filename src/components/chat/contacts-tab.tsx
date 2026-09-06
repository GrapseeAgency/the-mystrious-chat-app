// ─────────────────────────────────────────────────────────────
// Pulse Chat — Contacts tab (R27-a): real A–Z indexed people
// directory with hash sub-pages.
//   • root            → A–Z indexed contact list (sticky glass
//                       letter headers + kinetic index rail),
//                       real DM-membership marks, socket presence.
//   • #/contacts/add  → search-as-you-type sub-page (own file).
// Row tap → navigateHash('#/user/<id>') — the standalone user
// profile route page (user-route-page.tsx).
// ─────────────────────────────────────────────────────────────
'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { ChevronRight, MessageCircle, UserPlus, UsersRound } from 'lucide-react'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { ease, pressSpring, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { navigateHash, useHashRoute } from '@/lib/hash-router'
import { usePulseRealtime } from '@/hooks/use-pulse-socket'
import { cn } from '@/lib/utils'
import { Skeleton } from '@/components/ui/skeleton'
import { UserAvatar } from '@/components/chat/user-avatar'
import { ContactsAddPage } from '@/components/chat/contacts-add-page'

interface UsersResponse {
  users: AppUser[]
}
interface ConversationsResponse {
  conversations: ConversationSummary[]
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

/** Uppercase index bucket for a person ("A"…"Z", anything else → "#"). */
function indexLetterOf(name: string): string {
  const first = name.trim().charAt(0).toUpperCase()
  return /[A-Z]/.test(first) ? first : '#'
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
  const { path } = useHashRoute()
  const addOpen = path === '/contacts/add'
  const reducedMotion = useReducedMotion()
  const onlineIds = usePulseRealtime().onlineIds

  /**
   * Entrance stagger plays ONLY on the tab's first list render — the flag flips
   * after the first commit that has rows, so refetches never re-animate.
   */
  const [entranceOn, setEntranceOn] = useState(true)

  useEffect(() => {
    const t = setTimeout(() => setEntranceOn(false), 900)
    return () => clearTimeout(t)
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

  // real DM membership — shared cache with the chats list
  const conversations = useQuery({
    queryKey: ['conversations', me.id],
    queryFn: async (): Promise<ConversationsResponse> => {
      const res = await apiJson<ConversationsResponse>(
        `/api/conversations?userId=${encodeURIComponent(me.id)}`,
      )
      return res
    },
    enabled: !addOpen,
    staleTime: 10_000,
  })

  const others = useMemo(
    () => (users.data ?? []).filter((u) => u.id !== me.id),
    [users.data, me.id],
  )

  /** contact id → existing 1:1 DM conversation id (real membership mark) */
  const dmByUserId = useMemo(() => {
    const map = new Map<string, string>()
    for (const conv of conversations.data?.conversations ?? []) {
      if (conv.isGroup || conv.isSelf) continue
      const other = conv.members.find((m) => m.id !== me.id)
      if (other && !map.has(other.id)) map.set(other.id, conv.id)
    }
    return map
  }, [conversations.data, me.id])

  /** A–Z sections in index order ('#' bucket last) */
  const sections = useMemo(() => {
    const buckets = new Map<string, AppUser[]>()
    for (const person of others) {
      const letter = indexLetterOf(person.name)
      const list = buckets.get(letter)
      if (list) list.push(person)
      else buckets.set(letter, [person])
    }
    return Array.from(buckets.entries())
      .sort(([a], [b]) => (a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b)))
      .map(([letter, people]) => ({ letter, people }))
  }, [others])

  const letters = useMemo(() => sections.map((s) => s.letter), [sections])

  // ── index rail: refs + active-letter tracking + tap-to-scroll ──
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const sectionRefs = useRef(new Map<string, HTMLElement>())
  const [activeLetter, setActiveLetter] = useState<string | null>(null)

  const trackActiveLetter = useCallback(() => {
    const container = scrollRef.current
    if (container === null || letters.length === 0) return
    const containerTop = container.getBoundingClientRect().top
    let current: string | null = null
    for (const letter of letters) {
      const el = sectionRefs.current.get(letter)
      if (!el) continue
      if (el.getBoundingClientRect().top - containerTop <= 84) current = letter
    }
    setActiveLetter((prev) => (prev === current ? prev : current))
  }, [letters])

  const jumpToLetter = useCallback(
    (letter: string) => {
      haptic(6)
      setActiveLetter(letter)
      const el = sectionRefs.current.get(letter)
      el?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
    },
    [reducedMotion],
  )

  const setSectionRef = useCallback((letter: string, el: HTMLElement | null) => {
    if (el) sectionRefs.current.set(letter, el)
    else sectionRefs.current.delete(letter)
  }, [])

  const openPerson = useCallback((person: AppUser) => {
    haptic(8)
    navigateHash(`/user/${person.id}`)
  }, [])

  const loading = users.isPending

  return (
    <div className="absolute inset-0 flex flex-col bg-white dark:bg-zinc-900">
      <AnimatePresence initial={false}>
        {addOpen ? (
          <ContactsAddPage
            key="contacts-add"
            me={me}
            onOpenConversation={onOpenConversation}
            dmByUserId={dmByUserId}
          />
        ) : (
          <motion.div
            key="contacts-root"
            initial={false}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0, transition: { duration: 0.12 } }}
            className="absolute inset-0 flex flex-col"
          >
            {/* ── toolbar ── */}
            <header className="shrink-0 border-b border-zinc-200/70 pt-[max(0.75rem,env(safe-area-inset-top))] dark:border-zinc-800/80">
              <motion.div
                initial={entranceOn && !reducedMotion ? { opacity: 0, y: -10 } : false}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.28, ease: ease.out }}
                className="flex items-center gap-2 px-4 pb-3"
              >
                <div className="mr-auto min-w-0">
                  <h1 className="text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                    Contacts
                  </h1>
                  {!loading ? (
                    <p className="text-[11px] font-medium text-zinc-400 dark:text-zinc-500">
                      {others.length} {others.length === 1 ? 'person' : 'people'} on this Pulse
                    </p>
                  ) : null}
                </div>
                <motion.button
                  type="button"
                  onClick={onRequestNewGroup}
                  whileTap={reducedMotion ? undefined : { scale: 0.94 }}
                  transition={pressSpring}
                  aria-label="Create a new group"
                  className="glass-pill flex size-10 items-center justify-center text-zinc-500 outline-none transition-colors hover:text-zinc-700 active:scale-95 dark:text-zinc-400 dark:hover:text-zinc-200"
                >
                  <UsersRound className="size-[18px]" aria-hidden />
                </motion.button>
                <motion.button
                  type="button"
                  onClick={() => {
                    haptic(8)
                    navigateHash('/contacts/add')
                  }}
                  whileTap={reducedMotion ? undefined : { scale: 0.94 }}
                  transition={pressSpring}
                  className="glass-pill flex h-10 items-center gap-1.5 rounded-full px-3.5 text-[13px] font-bold text-emerald-600 outline-none hover:bg-emerald-500/10 active:scale-95 dark:text-emerald-400"
                >
                  <UserPlus className="size-4" aria-hidden />
                  Add contact
                </motion.button>
              </motion.div>
            </header>

            {/* ── scroll body + index rail ── */}
            <div className="relative min-h-0 flex-1">
              <div
                ref={scrollRef}
                onScroll={trackActiveLetter}
                className="pulse-scroll absolute inset-0 overflow-y-auto overscroll-contain pb-6"
              >
                {/* my identity strip */}
                {loading ? (
                  <div className="px-4 pt-3">
                    <Skeleton className="h-[72px] w-full rounded-3xl" />
                  </div>
                ) : (
                  <motion.div
                    initial={entranceOn && !reducedMotion ? { opacity: 0, y: 14 } : false}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: 0.32, ease: ease.out }}
                    className="px-3 pt-3"
                  >
                    <motion.button
                      type="button"
                      onClick={onGoProfile}
                      aria-label="Open my profile"
                      initial="rest"
                      animate="rest"
                      whileTap="tap"
                      variants={{ rest: { scale: 1 }, tap: { scale: 0.98 } }}
                      transition={pressSpring}
                      className="glass-deep glass-sheen flex w-full touch-manipulation items-center gap-3 rounded-3xl p-3 text-left outline-none"
                    >
                      <span className="relative shrink-0">
                        <PresenceGlow reduced={reducedMotion === true} />
                        <UserAvatar name={me.name} color={me.color} avatar={me.avatar} size={44} showPresence online />
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
                )}

                {/* A–Z indexed directory */}
                {loading ? (
                  <div role="status" aria-label="Loading contacts" className="pt-1">
                    <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
                  </div>
                ) : others.length === 0 ? (
                  <EmptyPeople />
                ) : (
                  <div className="mt-2">
                    {sections.map(({ letter, people }) => (
                      <section key={letter} aria-label={`Contacts under ${letter}`}>
                        <h2
                          ref={(el) => setSectionRef(letter, el)}
                          className="glass-deep sticky top-0 z-10 mx-3 mb-1 mt-2 flex h-7 items-center gap-2 rounded-full px-3 text-[11px] font-bold uppercase tracking-[0.14em] text-zinc-500 dark:text-zinc-400"
                        >
                          {letter}
                          <span className="text-[10px] font-semibold tabular-nums text-zinc-400 dark:text-zinc-600">
                            {people.length}
                          </span>
                        </h2>
                        <ul>
                          {people.map((person, i) => (
                            <PersonRow
                              key={person.id}
                              person={person}
                              online={onlineIds.has(person.id)}
                              sharesDm={dmByUserId.has(person.id)}
                              isNew={Date.now() - new Date(person.createdAt).getTime() < IS_NEW_WINDOW_MS}
                              entranceIndex={entranceOn ? i : null}
                              reduced={reducedMotion === true}
                              onPress={() => openPerson(person)}
                            />
                          ))}
                        </ul>
                      </section>
                    ))}
                  </div>
                )}
              </div>

              {/* ── kinetic index rail (sticky letter bubbles, tap-to-scroll) ── */}
              {!loading && letters.length > 1 ? (
                <nav
                  aria-label="Contact index"
                  className="pointer-events-none absolute bottom-2 right-0.5 top-2 z-20 flex flex-col items-center justify-center gap-px"
                >
                  {letters.map((letter, i) => {
                    const active = activeLetter === letter
                    return (
                      <motion.button
                        key={letter}
                        type="button"
                        initial={entranceOn && !reducedMotion ? { opacity: 0, x: 14 } : false}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ ...spring.snappy, delay: stagger(i, 0.022, 14) }}
                        whileTap={reducedMotion ? undefined : { scale: 1.45 }}
                        onClick={() => jumpToLetter(letter)}
                        aria-label={`Jump to contacts under ${letter}`}
                        aria-current={active ? 'true' : undefined}
                        className="pointer-events-auto relative flex h-[17px] w-5 items-center justify-center rounded-full outline-none"
                      >
                        {active ? (
                          <motion.span
                            layoutId="contacts-rail-bubble"
                            transition={spring.bouncy}
                            className="absolute inset-0 rounded-full bg-emerald-500/20 ring-1 ring-emerald-500/40"
                            aria-hidden
                          />
                        ) : null}
                        <span
                          className={cn(
                            'relative text-[9px] font-bold leading-none tabular-nums',
                            active
                              ? 'text-emerald-600 dark:text-emerald-400'
                              : 'text-zinc-400 dark:text-zinc-500',
                          )}
                        >
                          {letter}
                        </span>
                      </motion.button>
                    )
                  })}
                </nav>
              ) : null}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

function PersonRow({
  person,
  online,
  sharesDm,
  isNew,
  entranceIndex,
  reduced,
  onPress,
}: {
  person: AppUser
  online: boolean
  sharesDm: boolean
  isNew: boolean
  entranceIndex: number | null
  reduced: boolean
  onPress: () => void
}) {
  const entrance = entranceIndex !== null && !reduced
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
      <motion.button
        type="button"
        onClick={onPress}
        aria-label={`Open ${person.name}'s profile`}
        whileTap={reduced ? undefined : { scale: 0.975 }}
        transition={pressSpring}
        className="glass-row-hover group relative flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 text-left outline-none"
      >
        <span className="relative shrink-0">
          {online ? <PresenceGlow reduced={reduced} /> : null}
          <UserAvatar name={person.name} color={person.color} avatar={person.avatar} size={44} showPresence online={online} />
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
            {isNew ? (
              <motion.span
                initial={reduced ? false : { scale: 0, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                transition={spring.bouncy}
                className="inline-flex"
              >
                <span className="inline-flex h-4 items-center rounded-full bg-emerald-100 px-1.5 text-[9px] font-bold uppercase tracking-wide text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-400">
                  New
                </span>
              </motion.span>
            ) : null}
            {sharesDm ? (
              <span className="inline-flex h-4 shrink-0 items-center gap-1 rounded-full bg-emerald-500/10 px-1.5 text-[9px] font-bold uppercase tracking-wide text-emerald-600 ring-1 ring-emerald-500/20 dark:text-emerald-400">
                <MessageCircle className="size-2.5" aria-hidden />
                Chat
              </span>
            ) : null}
          </span>
          <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">
            {person.statusEmoji || person.statusText
              ? [person.statusText].filter(Boolean).join(' ')
              : person.about}
          </span>
        </span>
        <ChevronRight className="size-4 shrink-0 text-zinc-300 transition-transform duration-150 group-active:translate-x-0.5 dark:text-zinc-600" aria-hidden />
      </motion.button>
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
        <p className="text-sm font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">It&apos;s quiet in here</p>
        <p className="mx-auto mt-1 max-w-[250px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
          No other humans yet. Open this preview in a second browser tab, sign up another account, and it will appear here instantly.
        </p>
      </div>
    </div>
  )
}

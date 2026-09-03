// ─────────────────────────────────────────────────────────────
// Pulse Chat — Contacts tab: my identity strip + everyone list.
// Tapping a person opens (or creates) a direct conversation.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronRight, MessageCircle, UserPlus, UsersRound } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ConversationSummary } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { Button } from '@/components/ui/button'
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

  const others = useMemo(
    () => (users.data ?? []).filter((u) => u.id !== me.id),
    [users.data, me.id],
  )

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
      <header className="flex shrink-0 items-center gap-2 border-b border-zinc-200 px-4 pb-3 pt-[max(0.75rem,env(safe-area-inset-top))] dark:border-zinc-800">
        <h1 className="mr-auto text-xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">People</h1>
        <Button
          variant="ghost"
          size="sm"
          onClick={onRequestNewGroup}
          className="h-9 gap-1.5 rounded-full px-3 text-[13px] font-semibold text-emerald-600 hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 dark:text-emerald-400 dark:hover:text-emerald-400"
        >
          <UserPlus className="size-[18px]" aria-hidden />
          New group
        </Button>
      </header>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-6">
        {/* my identity strip */}
        {users.isPending ? (
          <div className="px-4 pt-3">
            <Skeleton className="h-[72px] w-full rounded-2xl" />
          </div>
        ) : (
          <div className="px-3 pt-3">
            <button
              type="button"
              onClick={onGoProfile}
              aria-label="Edit my profile"
              className="flex w-full touch-manipulation items-center gap-3 rounded-2xl border border-zinc-200 bg-white p-3 text-left shadow-sm outline-none transition-colors active:bg-zinc-50 active:scale-[0.99] dark:border-zinc-700/70 dark:bg-zinc-800/60 dark:active:bg-zinc-800"
            >
              <UserAvatar name={me.name} color={me.color} size={44} showPresence online />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold tracking-tight text-zinc-900 dark:text-zinc-100">
                  {me.name}
                </span>
                <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">{me.about}</span>
              </span>
              <ChevronRight className="size-4 shrink-0 text-zinc-400" aria-hidden />
            </button>
          </div>
        )}

        {/* everyone */}
        <h2 className="sticky top-0 z-10 mt-4 bg-gradient-to-b from-white via-white to-transparent px-4 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-wider text-zinc-500 dark:from-zinc-900 dark:via-zinc-900 dark:text-zinc-400">
          Everyone
        </h2>

        {users.isPending ? (
          <div role="status" aria-label="Loading people" className="pt-1">
            <RowSkeleton /><RowSkeleton /><RowSkeleton /><RowSkeleton />
          </div>
        ) : others.length === 0 ? (
          <EmptyPeople />
        ) : (
          <ul className="pb-2">
            {others.map((person) => (
              <PersonRow
                key={person.id}
                person={person}
                isNew={nowTick - new Date(person.createdAt).getTime() < IS_NEW_WINDOW_MS}
                disabled={startDm.isPending}
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
  isNew,
  disabled,
  onPress,
  onAvatarPress,
}: {
  person: AppUser
  isNew: boolean
  disabled: boolean
  onPress: () => void
  onAvatarPress: () => void
}) {
  return (
    <li className="px-2">
      <div className="flex w-full touch-manipulation items-center gap-3 rounded-2xl px-2 py-2.5 transition-colors active:bg-zinc-100 dark:active:bg-zinc-800">
        <button
          type="button"
          onClick={onAvatarPress}
          aria-label={`View ${person.name}'s profile`}
          className="shrink-0 rounded-full outline-none active:scale-95"
        >
          <UserAvatar name={person.name} color={person.color} size={44} showPresence />
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
                <Badge className="h-4 border-none bg-emerald-100 px-1.5 text-[9px] font-bold uppercase tracking-wide text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-400">
                  New
                </Badge>
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
      </div>
      <div aria-hidden className="ml-[60px] h-px bg-zinc-100 dark:bg-zinc-800" />
    </li>
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

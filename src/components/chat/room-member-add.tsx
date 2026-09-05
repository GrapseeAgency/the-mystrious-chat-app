// ─────────────────────────────────────────────────────────────
// Pulse — add-members glass sub-view (#/room/<id>/info) — R28-b.
// Full-screen slide-over INSIDE the room info page (not a route):
// the real Pulse directory (GET /api/users) minus current members,
// live filter, multi-select rows and a floating "Add N" action bar.
// POST /api/conversations/[id]/members takes { requesterId, userIds }
// (admins only server-side — the info page hides this view for
// non-admins, no dead UI), returns { conversation, added: string[] }.
// Optimistic insert into ['conversation', id] with rollback, plus
// ['conversations', myId] invalidation; the socket room syncs others.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Check,
  ChevronLeft,
  LoaderCircle,
  Search,
  SearchX,
  UserRoundPlus,
  UsersRound,
} from 'lucide-react'
import type { AppUser, ConversationDetail, GroupRole } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { ease, spring } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { toast } from 'sonner'
import { UserAvatar } from '@/components/chat/user-avatar'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

interface UsersResponse {
  users: AppUser[]
}

interface AddMembersResponse {
  conversation: ConversationDetail
  added: string[]
}

export interface RoomMemberAddPageProps {
  me: AppUser
  conversationId: string
  /** member ids already in the group — excluded from the directory */
  existingIds: ReadonlySet<string>
  reducedMotion?: boolean
  /** back to the info page */
  onClose: () => void
}

export function RoomMemberAddPage({
  me,
  conversationId,
  existingIds,
  reducedMotion = false,
  onClose,
}: RoomMemberAddPageProps) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState('')
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())

  /** shared app-wide directory cache (contacts / spotlight / new-chat all use ['users']) */
  const usersQ = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<UsersResponse>('/api/users')
      return res.users
    },
  })

  const query = draft.trim().toLowerCase()
  const candidates = useMemo(() => {
    return (usersQ.data ?? [])
      .filter((u) => !existingIds.has(u.id))
      .filter(
        (u) =>
          query.length === 0 ||
          u.name.toLowerCase().includes(query) ||
          (u.username ?? '').toLowerCase().includes(query),
      )
  }, [usersQ.data, existingIds, query])

  const addMutation = useMutation({
    mutationFn: async (userIds: string[]): Promise<AddMembersResponse> => {
      return apiJson<AddMembersResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members`,
        jsonBody({ requesterId: me.id, userIds }),
      )
    },
    onMutate: async (userIds: string[]) => {
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      const now = new Date().toISOString()
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) => {
        if (!old) return old
        const known = new Set(old.members.map((m) => m.id))
        const fresh = (usersQ.data ?? [])
          .filter((u) => userIds.includes(u.id) && !known.has(u.id))
          .map((u) => ({ ...u, lastReadAt: now, role: 'member' as GroupRole }))
        return fresh.length > 0 ? { ...old, members: [...old.members, ...fresh] } : old
      })
      return { prev }
    },
    onSuccess: (data) => {
      // POST /members builds the detail for the requester (me) — safe to cache directly
      queryClient.setQueryData(['conversation', conversationId], data.conversation)
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(
        data.added.length === 1 ? '1 member added' : `${data.added.length} members added`,
      )
      haptic(14)
      onClose()
    },
    onError: (error: Error, _vars, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      // honest server copy — 403 "Only group admins can add members.", 400/404 verbatim
      toast.error(error.message || 'Could not add members')
    },
  })

  const toggle = (id: string) => {
    haptic(6)
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const busy = addMutation.isPending
  const submit = () => {
    if (selected.size === 0 || busy) return
    haptic(10)
    addMutation.mutate([...selected])
  }

  const anim = {
    initial: reducedMotion ? false : { opacity: 0, y: 10 },
    animate: { opacity: 1, y: 0 },
  }

  return (
    <motion.div
      initial={reducedMotion ? false : { y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={spring.soft}
      role="dialog"
      aria-label="Add members"
      className="absolute inset-0 z-[56] flex flex-col bg-zinc-100/85 backdrop-blur-2xl dark:bg-black/70"
    >
      {/* header — back + title + live selection count */}
      <div className="flex min-h-14 shrink-0 items-center gap-1.5 px-2 pt-[env(safe-area-inset-top)]">
        <button
          type="button"
          aria-label="Back to group info"
          onClick={() => {
            haptic(6)
            onClose()
          }}
          className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-transform active:scale-90 dark:text-zinc-300"
        >
          <ChevronLeft className="size-6" aria-hidden />
        </button>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Add members
          </p>
          <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
            People already on this Pulse
          </p>
        </div>
        <span
          aria-live="polite"
          className={cn(
            'glass-pill mr-1 flex h-7 shrink-0 items-center px-2.5 text-[11px] font-bold tabular-nums transition-colors',
            selected.size > 0
              ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400'
              : 'text-zinc-400 dark:text-zinc-500',
          )}
        >
          {selected.size} selected
        </span>
      </div>

      {/* search */}
      <div className="shrink-0 px-3 pt-1 pb-2">
        <motion.div
          {...anim}
          transition={spring.soft}
          className="glass-pill relative"
        >
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400"
            aria-hidden
          />
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value.slice(0, 40))}
            placeholder="Search people"
            aria-label="Search people to add"
            autoComplete="off"
            className="h-10 w-full rounded-full bg-transparent pr-9 pl-9 text-sm text-zinc-900 placeholder:text-zinc-400 outline-none dark:text-zinc-100"
          />
          {draft.length > 0 ? (
            <button
              type="button"
              aria-label="Clear search"
              onClick={() => setDraft('')}
              className="absolute top-1/2 right-2 -translate-y-1/2 rounded-full p-1.5 text-zinc-400 outline-none transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
            >
              <Search className="size-3.5" aria-hidden />
            </button>
          ) : null}
        </motion.div>
      </div>

      {/* directory */}
      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 pb-32">
        {usersQ.isPending ? (
          <div
            className="glass-deep glass-sheen space-y-2 rounded-3xl p-2"
            role="status"
            aria-label="Loading directory"
          >
            <Skeleton className="h-14 w-full rounded-2xl" />
            <Skeleton className="h-14 w-full rounded-2xl" />
            <Skeleton className="h-14 w-4/5 rounded-2xl" />
          </div>
        ) : usersQ.isError ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 px-8 text-center">
            <div
              aria-hidden
              className="flex size-14 items-center justify-center rounded-2xl bg-zinc-200/60 text-zinc-400 dark:bg-zinc-800"
            >
              <UsersRound className="size-6" aria-hidden />
            </div>
            <p className="text-sm font-semibold text-zinc-500 dark:text-zinc-400">
              Could not load the directory
            </p>
            <p className="text-xs text-zinc-400 dark:text-zinc-500">Check your connection.</p>
          </div>
        ) : candidates.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 px-8 text-center">
            <div
              aria-hidden
              className="glass-sheen flex size-14 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-400/15 to-emerald-600/10 text-emerald-500 dark:from-emerald-400/10 dark:to-emerald-600/5"
            >
              {query.length > 0 ? (
                <SearchX className="size-6" aria-hidden />
              ) : (
                <UsersRound className="size-6" aria-hidden />
              )}
            </div>
            <p className="text-sm font-semibold text-zinc-500 dark:text-zinc-400">
              {query.length > 0 ? `No people match “${query}”` : 'Everyone is already here'}
            </p>
            <p className="text-xs leading-relaxed text-zinc-400 dark:text-zinc-500">
              {query.length > 0
                ? 'Try a different name or @handle.'
                : 'Every account on this Pulse is already in this group.'}
            </p>
          </div>
        ) : (
          <motion.div
            {...anim}
            transition={{ ...spring.soft, delay: 0.04 }}
            className="glass-deep glass-sheen overflow-hidden rounded-3xl p-1.5"
          >
            <ul>
              {candidates.map((user, i) => {
                const isSelected = selected.has(user.id)
                return (
                  <motion.li
                    key={user.id}
                    initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{
                      duration: 0.2,
                      delay: Math.min(i * 0.025, 0.22),
                      ease: ease.out,
                    }}
                  >
                    <button
                      type="button"
                      role="checkbox"
                      aria-checked={isSelected}
                      aria-label={`Select ${user.name}`}
                      onClick={() => toggle(user.id)}
                      className={cn(
                        'glass-row-hover flex min-h-[56px] w-full items-center gap-3 rounded-2xl px-2 py-2 text-left outline-none transition-colors',
                        isSelected && 'bg-emerald-500/[0.08]',
                      )}
                    >
                      <UserAvatar
                        name={user.name}
                        color={user.color}
                        avatar={user.avatar}
                        size={40}
                      />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-semibold text-zinc-900 dark:text-zinc-50">
                          {user.name}
                        </span>
                        <span className="block truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                          {user.username ? `@${user.username}` : user.about || 'Member'}
                        </span>
                      </span>
                      <span
                        aria-hidden
                        className={cn(
                          'flex size-7 shrink-0 items-center justify-center rounded-full border transition-all duration-150',
                          isSelected
                            ? 'border-emerald-500 bg-emerald-500 text-white'
                            : 'border-zinc-300 bg-transparent text-transparent dark:border-zinc-600',
                        )}
                      >
                        <Check className="size-4" strokeWidth={3} />
                      </span>
                    </button>
                  </motion.li>
                )
              })}
            </ul>
          </motion.div>
        )}
      </div>

      {/* floating action bar — real POST, disabled while pending */}
      <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 bg-gradient-to-t from-zinc-100/95 via-zinc-100/70 to-transparent pt-8 pb-[calc(env(safe-area-inset-bottom)+14px)] dark:from-black/90 dark:via-black/50">
        <motion.div
          initial={reducedMotion ? false : { opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={spring.soft}
          className="pointer-events-auto mx-4"
        >
          <button
            type="button"
            disabled={selected.size === 0 || busy}
            onClick={submit}
            className={cn(
              'glass-deep glass-sheen flex h-12 w-full items-center justify-center gap-2 rounded-full px-5 text-sm font-bold outline-none transition-transform active:scale-[0.97]',
              selected.size > 0 && !busy
                ? 'bg-emerald-500/90 text-white shadow-lg shadow-emerald-500/20 hover:bg-emerald-500 disabled:opacity-60'
                : 'text-zinc-400 disabled:opacity-70 dark:text-zinc-500',
            )}
          >
            {busy ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <UserRoundPlus className="size-4" aria-hidden />
            )}
            {busy
              ? 'Adding…'
              : selected.size === 0
                ? 'Select people to add'
                : `Add ${selected.size === 1 ? '1 member' : `${selected.size} members`}`}
          </button>
        </motion.div>
      </div>
    </motion.div>
  )
}

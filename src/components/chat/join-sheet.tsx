// ─────────────────────────────────────────────────────────────
// Pulse Chat — invite deep-link join sheet (?join=CODE).
// Previews the group behind the code, then joins (or jumps in
// when the viewer is already a member). Fully DB-backed.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import { Link2, LoaderCircle, LogIn, UsersRound } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, InvitePreview } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerTitle,
} from '@/components/ui/drawer'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar } from '@/components/chat/user-avatar'

interface JoinResponse {
  conversationId: string
  alreadyMember: boolean
}

export function JoinGroupSheet({
  me,
  code,
  open,
  onClose,
  onJoined,
}: {
  me: AppUser
  code: string
  open: boolean
  onClose: () => void
  onJoined: (conversationId: string) => void
}) {
  const queryClient = useQueryClient()

  const preview = useQuery({
    queryKey: ['invite', code, me.id],
    queryFn: async (): Promise<InvitePreview> => {
      const res = await apiJson<{ invite: InvitePreview }>(
        `/api/invite/${encodeURIComponent(code)}?userId=${encodeURIComponent(me.id)}`,
      )
      return res.invite
    },
    enabled: open && code.length > 0,
    retry: false,
    staleTime: 10_000,
  })

  const join = useMutation({
    mutationFn: async () => {
      return apiJson<JoinResponse>(
        `/api/invite/${encodeURIComponent(code)}/join`,
        jsonBody({ userId: me.id }),
      )
    },
    onSuccess: async (res) => {
      await queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(res.alreadyMember ? 'You are already in this group' : `Welcome to ${preview.data?.name ?? 'the group'}!`)
      onJoined(res.conversationId)
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not join the group')
    },
  })

  const invalid = preview.isError
  const invite = preview.data ?? null

  return (
    <Drawer open={open} onOpenChange={(next) => (!next ? onClose() : null)}>
      <DrawerContent className="mx-auto max-w-[420px] px-5 pb-[max(1.25rem,env(safe-area-inset-bottom))] pt-2">
        <DrawerTitle className="sr-only">Join group invite</DrawerTitle>
        <DrawerDescription className="sr-only">
          Preview and join the group behind this invite link.
        </DrawerDescription>

        {preview.isPending ? (
          <div className="flex flex-col items-center gap-4 py-8" role="status" aria-label="Loading invite">
            <Skeleton className="size-16 rounded-3xl" />
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-4 w-24" />
          </div>
        ) : invalid || !invite ? (
          <div className="flex flex-col items-center gap-3 py-8 text-center">
            <div className="flex size-14 items-center justify-center rounded-2xl bg-zinc-100 text-zinc-400 dark:bg-zinc-800">
              <Link2 className="size-6" aria-hidden />
            </div>
            <div>
              <p className="text-sm font-semibold text-zinc-700 dark:text-zinc-200">Link not valid</p>
              <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">
                This invite was reset or never existed. Ask the group admin for a fresh one.
              </p>
            </div>
            <Button variant="outline" onClick={onClose} className="mt-1 h-10 rounded-xl px-6 text-sm font-medium">
              Close
            </Button>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-1.5 pb-1 pt-3 text-center">
            <motion.div
              initial={{ scale: 0.85, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ type: 'spring', stiffness: 300, damping: 22 }}
            >
              <GroupAvatar title={invite.name ?? 'Group'} id={invite.conversationId} size={64} />
            </motion.div>
            <DrawerTitle asChild>
              <h2 className="mt-1.5 truncate text-lg font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                {invite.name ?? 'Group'}
              </h2>
            </DrawerTitle>
            <p className="flex items-center gap-1.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
              <UsersRound className="size-3.5" aria-hidden />
              {invite.memberCount} member{invite.memberCount === 1 ? '' : 's'}
            </p>
            <p className="mt-1 max-w-[280px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
              {invite.alreadyMember
                ? 'You are already in this group — jump back in?'
                : 'You were invited to join this group on Pulse.'}
            </p>
            <Button
              disabled={join.isPending}
              onClick={() => (invite.alreadyMember ? onJoined(invite.conversationId) : join.mutate())}
              className="mt-3 h-11 w-full rounded-xl bg-emerald-600 text-sm font-semibold text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-500 active:scale-[0.98] disabled:opacity-60"
            >
              {join.isPending ? (
                <>
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                  Joining…
                </>
              ) : (
                <>
                  <LogIn className="size-4" aria-hidden />
                  {invite.alreadyMember ? 'Open chat' : 'Join group'}
                </>
              )}
            </Button>
            <button
              type="button"
              onClick={onClose}
              className="mt-1 py-1.5 text-xs font-medium text-zinc-400 outline-none transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
            >
              Not now
            </button>
          </div>
        )}
      </DrawerContent>
    </Drawer>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — other-user profile sheet (tap any avatar anywhere)
// Real stats from /api/users/[id]/stats · tap-to-copy @handle ·
// Message button opens their DM instantly.
// ─────────────────────────────────────────────────────────────
'use client'

import { useQuery } from '@tanstack/react-query'
import { Copy, MessageCircle, ShieldCheck } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, UserStats } from '@/lib/types'
import { apiJson } from '@/lib/pulse-utils'
import { cn } from '@/lib/utils'
import { AVATAR_GRADIENTS, initialsOf } from '@/lib/pulse-utils'
import type { AvatarColor } from '@/lib/pulse-utils'
import { Button } from '@/components/ui/button'
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer'
import { Skeleton } from '@/components/ui/skeleton'

interface ProfileStatsResponse {
  stats: UserStats
}

function StatCell({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-xl bg-zinc-100/80 px-2 py-2.5 text-center dark:bg-zinc-800/50">
      <p className="text-base font-bold tabular-nums">{typeof value === 'number' ? value.toLocaleString() : value}</p>
      <p className="text-[10px] font-medium text-zinc-500">{label}</p>
    </div>
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
  onMessage?: (userId: string) => void
}) {
  const statsQ = useQuery({
    queryKey: ['user-stats', user?.id],
    queryFn: async () => {
      const res = await apiJson<ProfileStatsResponse>(`/api/users/${user?.id}/stats`)
      return res
    },
    enabled: open && user !== null,
  })

  if (!user) return null

  const gradient = AVATAR_GRADIENTS[(user.color as AvatarColor) in AVATAR_GRADIENTS ? (user.color as AvatarColor) : 'emerald']
  const stats = statsQ.data?.stats

  const copyHandle = async () => {
    if (!user.username) return
    try {
      await navigator.clipboard.writeText(`@${user.username}`)
      toast.success(`@${user.username} copied`)
    } catch {
      toast.error('Could not copy the handle')
    }
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="max-h-[85vh]">
        <DrawerHeader className="sr-only">
          <DrawerTitle>{user.name}&apos;s profile</DrawerTitle>
        </DrawerHeader>

        <div className="-mt-2 flex flex-col gap-4 px-4 pb-8">
          {/* hero */}
          <div className={cn('relative -mx-4 overflow-hidden px-4 pb-4 pt-5 text-white', gradient)}>
            <div className="absolute -right-8 -top-10 size-36 rounded-full bg-white/15 blur-2xl" />
            <div className="relative flex items-center gap-3.5">
              <div className="flex size-16 shrink-0 items-center justify-center rounded-2xl border-2 border-white/50 bg-black/10 text-xl font-black">
                {initialsOf(user.name)}
              </div>
              <div className="min-w-0">
                <p className="flex items-center gap-1.5 truncate text-lg font-bold leading-tight">
                  {user.name}
                  <ShieldCheck className="size-4 shrink-0 text-white/80" aria-label="verified member" />
                </p>
                {user.username ? (
                  <button
                    type="button"
                    onClick={copyHandle}
                    className="mt-0.5 flex items-center gap-1 rounded-full bg-black/20 px-2 py-0.5 text-xs font-semibold text-white/90 transition-colors hover:bg-black/30"
                  >
                    @{user.username} <Copy className="size-3" />
                  </button>
                ) : (
                  <p className="mt-0.5 text-[10px] text-white/70">no handle yet</p>
                )}
                {user.statusText ? (
                  <p className="mt-1 truncate text-xs text-white/85">
                    {user.statusEmoji ? `${user.statusEmoji} ` : ''}
                    {user.statusText}
                  </p>
                ) : null}
              </div>
            </div>
          </div>

          {/* about */}
          <div className="rounded-2xl border border-zinc-200 p-3.5 dark:border-zinc-800">
            <p className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">About</p>
            <p className="mt-1 text-sm leading-relaxed">{user.about}</p>
          </div>

          {/* real stats */}
          <div>
            <p className="mb-2 px-1 text-[11px] font-semibold uppercase tracking-wide text-zinc-500">Real activity — live from the database</p>
            {stats ? (
              <div className="grid grid-cols-4 gap-2">
                <StatCell label="messages" value={stats.messages} />
                <StatCell label="reactions" value={stats.reactions} />
                <StatCell label="photos" value={stats.photos} />
                <StatCell label="voice" value={stats.voiceNotes} />
                <StatCell label="chats" value={stats.chats} />
                <StatCell label="groups" value={stats.groups} />
                <StatCell label="days in" value={stats.days} />
                <StatCell label="since" value={new Date(stats.joinedAt).toLocaleDateString(undefined, { month: 'short', year: 'numeric' })} />
              </div>
            ) : statsQ.isError ? (
              <p className="rounded-xl border border-dashed border-zinc-300 px-3 py-4 text-center text-xs text-zinc-500 dark:border-zinc-700">
                Stats unavailable right now.
              </p>
            ) : (
              <div className="grid grid-cols-4 gap-2">
                {Array.from({ length: 8 }).map((_, i) => (
                  <Skeleton key={i} className="h-[52px] rounded-xl" />
                ))}
              </div>
            )}
          </div>

          {/* presence */}
          {stats ? (
            <p className="px-1 text-[11px] text-zinc-500">
              Last active {new Date(stats.lastSeenAt).toLocaleString()}
            </p>
          ) : null}

          <Button
            className="w-full"
            onClick={() => {
              onMessage?.(user.id)
              onOpenChange(false)
            }}
          >
            <MessageCircle className="mr-1.5 size-4" /> Message {user.name.split(' ')[0]}
          </Button>
        </div>
      </DrawerContent>
    </Drawer>
  )
}

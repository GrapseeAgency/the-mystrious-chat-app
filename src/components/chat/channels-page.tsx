// ─────────────────────────────────────────────────────────────
// Pulse — #/chats/channels sub-page (R30-c).
// WhatsApp-Channels/Telegram-style broadcast directory INSIDE the
// chats tab, mounted exactly like #/chats/archived: dark glass
// page, hash-routed (navigateHash('/chats/channels') opens,
// backHash() closes), staggered row entrances.
//   SUBSCRIBED — the viewer's channels (tap = open the room)
//   DISCOVER   — the rest of the directory (Subscribe pill → POST
//                /api/channels/[id]/subscribe, optimistic move).
// Data is the REAL /api/channels directory — no mocks.
// ─────────────────────────────────────────────────────────────
'use client'

import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft,
  LoaderCircle,
  Radio,
  RefreshCw,
  Users,
} from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ChannelSummary } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupAvatar } from '@/components/chat/user-avatar'

interface ChannelsResponse {
  channels: ChannelSummary[]
}

interface SubscribeResponse {
  already: boolean
  memberCount: number
}

export interface ChannelsPageProps {
  /** reactive: hash path === '/chats/channels' */
  open: boolean
  me: AppUser
  onBack: () => void
  /** open a channel room — the same navigation call the chats list uses */
  onOpenConversation: (conversationId: string) => void
}

/** Section label — same rhythm as the archived page / info page. */
function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <motion.p
      initial={false}
      className="px-2 pt-4 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500"
    >
      {children}
    </motion.p>
  )
}

/** One directory row — glass, 56px target, Radio tile + meta + action pill. */
function ChannelRow({
  channel,
  index,
  reducedMotion,
  onPress,
  onSubscribe,
  subscribePending,
}: {
  channel: ChannelSummary
  index: number
  reducedMotion: boolean | null
  onPress: () => void
  onSubscribe: () => void
  subscribePending: boolean
}) {
  return (
    <motion.div
      initial={reducedMotion ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...spring.soft, delay: stagger(Math.min(index, 9)) }}
      className="glass-row-hover flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left"
      role="row"
      aria-label={`${channel.name} — ${channel.memberCount} subscribers`}
    >
      <button
        type="button"
        onClick={onPress}
        aria-label={`Open ${channel.name}`}
        className="flex min-w-0 flex-1 items-center gap-3 outline-none"
      >
        {/* R33-b — circular channel photo when one is set; the broadcast-icon
            glass tile stays the fallback (unread dot overlays both).
            R34-a shapes: channels are THINGS → both photo and fallback tile
            wear the .pulse-squircle mask (the unread dot sits OUTSIDE the
            masked wrapper so it never gets clipped). */}
        <span className="relative shrink-0">
          <span className="pulse-squircle block" style={{ width: 44, height: 44 }}>
            {channel.photo ? (
              <GroupAvatar title={channel.name} id={channel.id} size={44} photo={channel.photo} />
            ) : (
              <span
                aria-hidden
                className="glass-deep relative flex size-11 items-center justify-center rounded-2xl"
              >
                <Radio className="size-5 text-emerald-600 dark:text-emerald-400" />
              </span>
            )}
          </span>
          {channel.unread ? (
            <span className="absolute -top-0.5 -right-0.5 size-2.5 rounded-full bg-emerald-500 ring-2 ring-white dark:ring-zinc-900" />
          ) : null}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[14px] font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
            {channel.name}
          </span>
          <span className="block truncate text-[11.5px] text-zinc-500 dark:text-zinc-400">
            {channel.description || channel.preview || 'No description yet'}
          </span>
          <span className="mt-0.5 flex items-center gap-1 text-[10.5px] font-medium text-zinc-400 dark:text-zinc-500">
            <Users className="size-3" aria-hidden />
            {channel.memberCount === 1 ? '1 subscriber' : `${channel.memberCount} subscribers`}
          </span>
        </span>
      </button>
      {channel.isSubscribed ? (
        <button
          type="button"
          onClick={onPress}
          className="glass-pill h-8 shrink-0 px-3 text-xs font-bold text-emerald-600 outline-none transition-transform active:scale-95 dark:text-emerald-400"
        >
          Open
        </button>
      ) : (
        <button
          type="button"
          disabled={subscribePending}
          onClick={onSubscribe}
          className="h-8 shrink-0 rounded-full bg-emerald-500 px-3.5 text-xs font-bold text-white shadow-sm shadow-emerald-600/30 outline-none transition-transform hover:bg-emerald-400 active:scale-95 disabled:opacity-60"
        >
          {subscribePending ? (
            <LoaderCircle className="mx-auto size-3.5 animate-spin" aria-hidden />
          ) : (
            'Subscribe'
          )}
        </button>
      )}
    </motion.div>
  )
}

export function ChannelsPage({ open, me, onBack, onOpenConversation }: ChannelsPageProps) {
  const reducedMotion = useReducedMotion()
  const queryClient = useQueryClient()

  const directory = useQuery({
    queryKey: ['channels', me.id],
    queryFn: async (): Promise<ChannelsResponse> =>
      apiJson<ChannelsResponse>(`/api/channels?userId=${encodeURIComponent(me.id)}`),
    enabled: open,
    refetchInterval: 20_000,
  })

  const channels = directory.data?.channels ?? []
  const subscribed = channels.filter((c) => c.isSubscribed)
  const discover = channels.filter((c) => !c.isSubscribed)

  /** Subscribe — optimistic move into SUBSCRIBED, real memberCount on settle. */
  const subscribe = useMutation({
    mutationFn: async (channel: ChannelSummary) => {
      return apiJson<SubscribeResponse>(
        `/api/channels/${encodeURIComponent(channel.id)}/subscribe`,
        jsonBody({ userId: me.id }),
      )
    },
    onMutate: async (channel) => {
      await queryClient.cancelQueries({ queryKey: ['channels', me.id] })
      const previous = queryClient.getQueryData<ChannelsResponse>(['channels', me.id])
      if (previous) {
        queryClient.setQueryData<ChannelsResponse>(['channels', me.id], {
          channels: previous.channels.map((c) =>
            c.id === channel.id ? { ...c, isSubscribed: true, memberCount: c.memberCount + 1 } : c,
          ),
        })
      }
      return { previous }
    },
    onSuccess: (data) => {
      haptic(10)
      toast.success(data.already ? 'Already subscribed' : 'Subscribed')
    },
    onError: (_error, _channel, ctx) => {
      if (ctx?.previous) queryClient.setQueryData(['channels', me.id], ctx.previous)
      toast.error('Could not subscribe — try again')
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['channels', me.id] })
      // joining created a participant row → the chats list gains the channel
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
    },
  })

  return (
    <AnimatePresence>
      {open ? (
        <motion.div
          key="channels-page"
          initial={reducedMotion ? false : { opacity: 0, x: '7%' }}
          animate={{ opacity: 1, x: 0 }}
          exit={reducedMotion ? { opacity: 0 } : { opacity: 0, x: '7%' }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="absolute inset-0 z-30 flex flex-col bg-white dark:bg-zinc-900"
          role="region"
          aria-label="Channels"
        >
          {/* frosted sub-page header — same glass recipe as #/chats/archived */}
          <header className="glass-deep glass-sheen shrink-0 border-b border-zinc-200/70 pt-[max(0px,env(safe-area-inset-top))] dark:border-white/10">
            <div className="flex items-center gap-2 px-3 py-2.5">
              <motion.button
                type="button"
                aria-label="Back to chats"
                onClick={() => {
                  haptic(8)
                  onBack()
                }}
                whileTap={reducedMotion ? undefined : { scale: 0.9 }}
                transition={spring.snappy}
                className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-colors hover:text-zinc-900 dark:text-zinc-300 dark:hover:text-white"
              >
                <ArrowLeft className="size-5" aria-hidden />
              </motion.button>
              <div className="min-w-0 flex-1">
                <h1 className="flex items-center gap-2 truncate text-[17px] font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                  Channels
                  <span className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-emerald-500/15 px-1.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                    {subscribed.length > 99 ? '99+' : subscribed.length}
                  </span>
                </h1>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  Broadcast spaces — only admins post
                </p>
              </div>
              <motion.button
                type="button"
                aria-label="Refresh channels"
                onClick={() => {
                  haptic(6)
                  void directory.refetch()
                }}
                whileTap={reducedMotion ? undefined : { scale: 0.9 }}
                transition={spring.snappy}
                className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-colors hover:text-zinc-900 dark:text-zinc-300 dark:hover:text-white"
              >
                <RefreshCw
                  className={cn('size-4', directory.isFetching && 'animate-spin')}
                  aria-hidden
                />
              </motion.button>
            </div>
          </header>

          <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain pb-6">
            {directory.isPending ? (
              <div className="space-y-2 px-2 pt-4" role="status" aria-label="Loading channels">
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-full rounded-2xl" />
                <Skeleton className="h-16 w-11/12 rounded-2xl" />
              </div>
            ) : directory.isError ? (
              <motion.div
                initial={reducedMotion ? false : { opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3 }}
                className="flex flex-col items-center justify-center gap-3 px-8 pt-24 text-center"
              >
                <span aria-hidden className="glass-deep flex size-16 items-center justify-center rounded-3xl">
                  <Radio className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  Could not load channels
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  {directory.error instanceof Error ? directory.error.message : 'Something went wrong.'}
                </p>
                <button
                  type="button"
                  onClick={() => void directory.refetch()}
                  className="glass-pill h-9 px-4 text-xs font-bold text-emerald-600 outline-none dark:text-emerald-400"
                >
                  Try again
                </button>
              </motion.div>
            ) : channels.length === 0 ? (
              <motion.div
                initial={reducedMotion ? false : { opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3 }}
                className="flex flex-col items-center justify-center gap-3 px-8 pt-24 text-center"
              >
                <span aria-hidden className="glass-deep flex size-16 items-center justify-center rounded-3xl">
                  <Radio className="size-7 text-zinc-400 dark:text-zinc-500" />
                </span>
                <h2 className="text-base font-semibold tracking-tight text-zinc-800 dark:text-zinc-100">
                  No channels yet
                </h2>
                <p className="max-w-[260px] text-[13px] leading-relaxed text-zinc-500 dark:text-zinc-400">
                  Start one from the new-chat sheet — pick New channel. Yours shows up here, and in
                  every subscriber&apos;s list.
                </p>
              </motion.div>
            ) : (
              <>
                <SectionLabel>
                  Subscribed{subscribed.length > 0 ? ` · ${subscribed.length}` : ''}
                </SectionLabel>
                {subscribed.length > 0 ? (
                  <div className="glass-deep glass-sheen mx-2 rounded-3xl p-1.5">
                    {subscribed.map((channel, i) => (
                      <ChannelRow
                        key={channel.id}
                        channel={channel}
                        index={i}
                        reducedMotion={reducedMotion}
                        onPress={() => {
                          haptic(10)
                          onOpenConversation(channel.id)
                        }}
                        onSubscribe={() => subscribe.mutate(channel)}
                        subscribePending={
                          subscribe.isPending && subscribe.variables?.id === channel.id
                        }
                      />
                    ))}
                  </div>
                ) : (
                  <p className="px-3 pt-1 pb-2 text-[13px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                    Nothing yet — discover a channel below.
                  </p>
                )}

                <SectionLabel>
                  Discover{discover.length > 0 ? ` · ${discover.length}` : ''}
                </SectionLabel>
                {discover.length > 0 ? (
                  <div className="glass-deep glass-sheen mx-2 rounded-3xl p-1.5">
                    {discover.map((channel, i) => (
                      <ChannelRow
                        key={channel.id}
                        channel={channel}
                        index={i}
                        reducedMotion={reducedMotion}
                        onPress={() => {
                          haptic(10)
                          if (channel.isSubscribed) onOpenConversation(channel.id)
                        }}
                        onSubscribe={() => subscribe.mutate(channel)}
                        subscribePending={
                          subscribe.isPending && subscribe.variables?.id === channel.id
                        }
                      />
                    ))}
                  </div>
                ) : (
                  <p className="px-3 pt-1 pb-2 text-[13px] leading-relaxed text-zinc-400 dark:text-zinc-500">
                    You have discovered every channel.
                  </p>
                )}
              </>
            )}
          </div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

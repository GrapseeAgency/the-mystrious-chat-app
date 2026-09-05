// ─────────────────────────────────────────────────────────────
// Pulse Chat — "New chat" bottom sheet (vaul Drawer):
// DM mode = searchable contact list; group mode = multi-select
// builder with live member count.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { motion } from 'framer-motion'
import { Camera, Check, LoaderCircle, MessageCircle, Radio, Search, UsersRound, X } from 'lucide-react'
import { toast } from 'sonner'
import type { AppUser, ChannelSummary, ConversationSummary } from '@/lib/types'
import { apiJson, jsonBody } from '@/lib/pulse-utils'
import { fileToSquareDataUrl, uploadDataUrlPhoto } from '@/lib/upload-photo'
import { cn } from '@/lib/utils'
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerTitle,
} from '@/components/ui/drawer'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Skeleton } from '@/components/ui/skeleton'
import { UserAvatar } from '@/components/chat/user-avatar'

interface UsersResponse {
  users: AppUser[]
}
interface CreateConversationResponse {
  conversation: ConversationSummary
}
interface CreateChannelResponse {
  channel: ChannelSummary & { conversationId: string }
}

const CHANNEL_NAME_MIN = 2
const CHANNEL_NAME_MAX = 40
const CHANNEL_DESCRIPTION_MAX = 200

export function NewChatSheet({
  me,
  open,
  onOpenChange,
  initialMode,
  onConversationOpened,
}: {
  me: AppUser
  open: boolean
  onOpenChange: (open: boolean) => void
  initialMode: 'dm' | 'group' | 'channel'
  onConversationOpened: (conversationId: string) => void
}) {
  const queryClient = useQueryClient()
  // NOTE: the parent remounts this sheet per open-session (generation key),
  // so local state initializers below are intentionally fresh every time.
  const [mode, setMode] = useState<'dm' | 'group' | 'channel'>(initialMode)
  const [search, setSearch] = useState('')
  const [groupName, setGroupName] = useState('')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  // R30-c channel builder state (two-field step)
  const [channelName, setChannelName] = useState('')
  const [channelDescription, setChannelDescription] = useState('')
  const [channelError, setChannelError] = useState<string | null>(null)
  // R33-b: optional channel photo, uploaded through the REAL /api/uploads
  // chain at pick time so Create posts a validated "/api/uploads/<file>" path.
  const channelPhotoInputRef = useRef<HTMLInputElement | null>(null)
  const [channelPhoto, setChannelPhoto] = useState<string | null>(null)
  const [channelPhotoBusy, setChannelPhotoBusy] = useState(false)
  const [channelPhotoPreview, setChannelPhotoPreview] = useState<string | null>(null)

  const users = useQuery({
    queryKey: ['users'],
    queryFn: async (): Promise<AppUser[]> => {
      const res = await apiJson<UsersResponse>('/api/users')
      return res.users
    },
    enabled: open,
  })

  const others = useMemo(() => {
    const q = search.trim().toLowerCase()
    return (users.data ?? [])
      .filter((u) => u.id !== me.id)
      .filter((u) => (q ? u.name.toLowerCase().includes(q) : true))
  }, [users.data, me.id, search])

  const create = useMutation({
    mutationFn: async (payload: { memberIds: string[]; name?: string }) => {
      return apiJson<CreateConversationResponse>('/api/conversations', jsonBody({
        creatorId: me.id,
        memberIds: payload.memberIds,
        isGroup: mode === 'group',
        ...(mode === 'group' && payload.name ? { name: payload.name } : {}),
      }))
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(mode === 'group' ? `Group “${data.conversation.name ?? 'Group'}” created` : 'Chat ready')
      onConversationOpened(data.conversation.id)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Could not start that conversation')
    },
  })

  const toggleSelected = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const totalMembers = selectedIds.size + 1
  const groupValid = totalMembers >= 3

  const handleCreateGroup = () => {
    if (!groupValid || create.isPending) return
    create.mutate({ memberIds: [...selectedIds], name: groupName.trim() })
  }

  // ── R30-c: broadcast channel creation (two-field step) ──────
  const createChannel = useMutation({
    mutationFn: async (payload: { name: string; description: string; photo?: string }) => {
      return apiJson<CreateChannelResponse>('/api/channels', jsonBody({
        userId: me.id,
        name: payload.name,
        ...(payload.description ? { description: payload.description } : {}),
        ...(payload.photo ? { photo: payload.photo } : {}),
      }))
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      queryClient.invalidateQueries({ queryKey: ['channels', me.id] })
      toast.success(`Channel “${data.channel.name}” created`)
      onConversationOpened(data.channel.conversationId)
    },
    onError: (error: Error) => {
      // server-side validation (name length, description cap) surfaces inline
      setChannelError(error.message || 'Could not create the channel')
    },
  })

  const channelNameLength = channelName.trim().length
  const channelValid =
    channelNameLength >= CHANNEL_NAME_MIN && channelNameLength <= CHANNEL_NAME_MAX

  const handleCreateChannel = () => {
    if (createChannel.isPending) return
    if (channelNameLength < CHANNEL_NAME_MIN) {
      setChannelError(`Name needs at least ${CHANNEL_NAME_MIN} characters.`)
      return
    }
    if (channelNameLength > CHANNEL_NAME_MAX) {
      setChannelError(`Keep the name under ${CHANNEL_NAME_MAX + 1} characters.`)
      return
    }
    if (channelPhotoBusy) return
    setChannelError(null)
    createChannel.mutate({
      name: channelName.trim(),
      description: channelDescription.trim(),
      ...(channelPhoto ? { photo: channelPhoto } : {}),
    })
  }

  /** R33-b: pick → optimize (local preview) → upload now; Create posts the stored path. */
  const handleChannelPhotoPicked = async (file: File) => {
    if (channelPhotoBusy) return
    setChannelPhotoBusy(true)
    try {
      const dataUrl = await fileToSquareDataUrl(file)
      setChannelPhotoPreview(dataUrl)
      const path = await uploadDataUrlPhoto(dataUrl)
      setChannelPhoto(path)
      setChannelPhotoPreview(null)
    } catch (error) {
      setChannelPhotoPreview(null)
      toast.error(error instanceof Error ? error.message : 'Could not upload that photo')
    } finally {
      setChannelPhotoBusy(false)
    }
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent className="mx-auto w-full max-w-[420px] rounded-t-[1.75rem] border-t border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
        <div className="mx-4 flex min-h-0 flex-col pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          <div className="flex items-center justify-between pb-1">
            <div>
              <DrawerTitle className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                New chat
              </DrawerTitle>
              <DrawerDescription className="sr-only">
                Start a direct message or build a new group
              </DrawerDescription>
            </div>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close"
              onClick={() => onOpenChange(false)}
              className="size-9 rounded-full text-zinc-400 hover:text-zinc-600 active:scale-95 dark:hover:text-zinc-300"
            >
              <X className="size-[18px]" aria-hidden />
            </Button>
          </div>

          {/* segmented control — R30-c adds the New channel action */}
          <div
            role="tablist"
            aria-label="Conversation type"
            className="mb-3 grid grid-cols-3 gap-1 rounded-full bg-zinc-100 p-1 dark:bg-zinc-800"
          >
            {(
              [
                { id: 'dm', label: 'Direct', icon: MessageCircle },
                { id: 'group', label: 'Group', icon: UsersRound },
                { id: 'channel', label: 'Channel', icon: Radio },
              ] as const
            ).map((seg) => {
              const isActive = mode === seg.id
              return (
                <button
                  key={seg.id}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  onClick={() => setMode(seg.id)}
                  className={cn(
                    'relative rounded-full py-2 text-[13px] font-semibold tracking-tight outline-none transition-colors active:scale-[0.98]',
                    isActive ? 'text-emerald-700 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
                  )}
                >
                  {isActive ? (
                    <motion.span
                      layoutId="pulse-newchat-seg"
                      transition={{ type: 'spring', stiffness: 500, damping: 36 }}
                      className="absolute inset-0 rounded-full bg-white shadow-sm dark:bg-zinc-700"
                    />
                  ) : null}
                  <span className="relative flex items-center justify-center gap-1">
                    <seg.icon className="size-3.5" aria-hidden />
                    {seg.label}
                  </span>
                </button>
              )
            })}
          </div>

          {/* search (DM) / group name (group) / channel fields (channel) */}
          {mode === 'dm' ? (
            <div className="relative mb-2">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400" aria-hidden />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search people…"
                aria-label="Search people"
                className="h-10 rounded-xl border-zinc-200 bg-zinc-50 pl-9 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
              />
            </div>
          ) : mode === 'group' ? (
            <div className="mb-2 space-y-1.5">
              <Input
                value={groupName}
                onChange={(e) => setGroupName(e.target.value.slice(0, 48))}
                placeholder={`Group of ${totalMembers}`}
                aria-label="Group name"
                maxLength={48}
                autoComplete="off"
                className="h-10 rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
              />
              <p className={cn('text-xs', groupValid ? 'text-zinc-400 dark:text-zinc-500' : 'font-medium text-emerald-600 dark:text-emerald-400')}>
                {groupValid ? `${totalMembers} ${totalMembers === 1 ? 'member' : 'members'} selected` : `${totalMembers} of 3+ members picked`} · include yourself plus at least 2 people
              </p>
            </div>
          ) : (
            <div className="mb-2 space-y-1.5">
              {/* R33-b — optional channel photo: round glass tile (Camera icon)
                  that uploads through the real /api/uploads chain at pick time */}
              <div className="flex items-center gap-3 pb-0.5">
                <button
                  type="button"
                  aria-label={channelPhoto ? 'Replace channel photo' : 'Add a channel photo'}
                  disabled={channelPhotoBusy}
                  onClick={() => {
                    channelPhotoInputRef.current?.click()
                  }}
                  className="relative size-14 shrink-0 overflow-hidden rounded-full ring-1 ring-zinc-200 outline-none transition-transform active:scale-95 disabled:opacity-60 dark:ring-zinc-700"
                >
                  {channelPhotoPreview || channelPhoto ? (
                    <img
                      src={channelPhotoPreview ?? channelPhoto ?? undefined}
                      alt="Channel photo preview"
                      className="size-full object-cover"
                    />
                  ) : (
                    <span className="flex size-full items-center justify-center bg-zinc-100 dark:bg-zinc-800">
                      <Camera className="size-5 text-zinc-400" aria-hidden />
                    </span>
                  )}
                  {channelPhotoBusy ? (
                    <span className="absolute inset-0 flex items-center justify-center bg-black/40">
                      <LoaderCircle className="size-4 animate-spin text-white" aria-hidden />
                    </span>
                  ) : null}
                </button>
                <div className="min-w-0 flex-1">
                  <p className="text-[13px] font-semibold text-zinc-700 dark:text-zinc-200">
                    Channel photo
                  </p>
                  <p className="text-xs text-zinc-400 dark:text-zinc-500">
                    {channelPhoto ? 'Uploaded — shown everywhere' : 'Optional — you can add one later from the channel info'}
                  </p>
                </div>
                {channelPhoto ? (
                  <button
                    type="button"
                    aria-label="Remove channel photo"
                    onClick={() => setChannelPhoto(null)}
                    className="flex size-8 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90"
                  >
                    <X className="size-4" aria-hidden />
                  </button>
                ) : null}
              </div>
              <div className="relative">
                <Radio className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400" aria-hidden />
                <Input
                  value={channelName}
                  onChange={(e) => {
                    setChannelName(e.target.value.slice(0, CHANNEL_NAME_MAX))
                    if (channelError) setChannelError(null)
                  }}
                  placeholder="Channel name"
                  aria-label="Channel name"
                  aria-invalid={channelError !== null}
                  maxLength={CHANNEL_NAME_MAX}
                  autoComplete="off"
                  className="h-10 rounded-xl border-zinc-200 bg-zinc-50 pl-9 text-sm focus-visible:ring-emerald-500/60 aria-invalid:border-rose-400 aria-invalid:ring-rose-500/30 dark:border-zinc-700 dark:bg-zinc-800"
                />
              </div>
              <Textarea
                value={channelDescription}
                onChange={(e) => setChannelDescription(e.target.value.slice(0, CHANNEL_DESCRIPTION_MAX))}
                placeholder="Description — what is this channel about? (optional)"
                aria-label="Channel description"
                rows={2}
                className="min-h-0 rounded-xl border-zinc-200 bg-zinc-50 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
              />
              {channelError ? (
                <p role="alert" className="text-xs font-medium text-rose-600 dark:text-rose-400">
                  {channelError}
                </p>
              ) : (
                <p className="text-xs text-zinc-400 dark:text-zinc-500">
                  {channelDescription.length > 0
                    ? `${CHANNEL_DESCRIPTION_MAX - channelDescription.length} characters left`
                    : 'You will be the admin — only admins can post in a channel.'}
                </p>
              )}
              {/* hidden picker for the optional channel photo (R33-b) */}
              <input
                ref={channelPhotoInputRef}
                type="file"
                accept="image/*"
                disabled={channelPhotoBusy}
                className="sr-only"
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  e.target.value = '' // allow re-picking the same file
                  if (file) void handleChannelPhotoPicked(file)
                }}
              />
            </div>
          )}

          {/* people list — hidden on the channel step */}
          {mode !== 'channel' ? (
          <div className="pulse-scroll max-h-[46dvh] min-h-0 overflow-y-auto overscroll-contain rounded-xl">
            {users.isPending ? (
              <div role="status" aria-label="Loading people">
                <MiniSkeleton /><MiniSkeleton /><MiniSkeleton /><MiniSkeleton /><MiniSkeleton />
              </div>
            ) : others.length === 0 ? (
              <div className="flex flex-col items-center gap-2 py-10 text-center">
                <UsersRound className="size-7 text-zinc-300 dark:text-zinc-600" aria-hidden />
                <p className="max-w-[220px] text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
                  No one else has joined yet. Open a second browser tab and create another account.
                </p>
              </div>
            ) : (
              <ul>
                {others.map((person) => {
                  const checked = selectedIds.has(person.id)
                  return (
                    <li key={person.id}>
                      <button
                        type="button"
                        onClick={() =>
                          mode === 'dm'
                            ? !create.isPending &&
                              create.mutate({ memberIds: [person.id] })
                            : toggleSelected(person.id)
                        }
                        aria-label={
                          mode === 'dm' ? `Message ${person.name}` : `${checked ? 'Remove' : 'Add'} ${person.name}`
                        }
                        className="flex w-full touch-manipulation items-center gap-3 rounded-xl px-2 py-2 text-left outline-none transition-colors hover:bg-zinc-50 active:bg-zinc-100 dark:hover:bg-zinc-800/60 dark:active:bg-zinc-800"
                      >
                        <UserAvatar name={person.name} color={person.color} size={40} showPresence />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium tracking-tight text-zinc-900 dark:text-zinc-100">
                            {person.name}
                          </span>
                          <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">
                            {person.about}
                          </span>
                        </span>
                        {mode === 'group' ? (
                          <span
                            aria-hidden
                            className={cn(
                              'flex size-6 items-center justify-center rounded-full border-2 transition-all',
                              checked
                                ? 'border-emerald-500 bg-emerald-500 text-white'
                                : 'border-zinc-300 dark:border-zinc-600',
                            )}
                          >
                            {checked ? <Check className="size-3.5" strokeWidth={3} /> : null}
                          </span>
                        ) : null}
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
          ) : null}

          {/* footer action */}
          {mode === 'group' ? (
            <Button
              onClick={handleCreateGroup}
              disabled={!groupValid || create.isPending}
              className="mt-3 h-11 w-full rounded-xl bg-emerald-600 text-sm font-semibold text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-500 active:scale-[0.98]"
            >
              {create.isPending ? (
                <>
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                  Creating group…
                </>
              ) : (
                `Create group · ${totalMembers} ${totalMembers === 1 ? 'member' : 'members'}`
              )}
            </Button>
          ) : null}
          {mode === 'channel' ? (
            <Button
              onClick={handleCreateChannel}
              disabled={!channelValid || createChannel.isPending}
              className="mt-3 h-11 w-full gap-1.5 rounded-xl bg-emerald-600 text-sm font-semibold text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-500 active:scale-[0.98]"
            >
              {createChannel.isPending ? (
                <>
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                  Creating channel…
                </>
              ) : (
                <>
                  <Radio className="size-4" aria-hidden />
                  Create channel
                </>
              )}
            </Button>
          ) : null}
        </div>
      </DrawerContent>
    </Drawer>
  )
}

function MiniSkeleton() {
  return (
    <div className="flex items-center gap-3 px-2 py-2.5">
      <Skeleton className="size-10 rounded-full" />
      <div className="flex-1 space-y-1.5">
        <Skeleton className="h-3 w-1/3" />
        <Skeleton className="h-2.5 w-2/5" />
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse — room info sub-page (#/room/<id>/info) — R27-c · R28-b.
// Full-screen glass sheet above the chat room: hero by room type,
// member list with roles (Lucide Crown) + live socket presence,
// tap-to-profile (routes to the global #/user/:id page), REAL
// admin actions (add members sub-view · remove member · promote/
// demote via the members API · invite link per /invite API),
// mute toggle (real mute API), disappearing-TTL display and
// honest quick-stats computed from the room's loaded messages.
// ─────────────────────────────────────────────────────────────
'use client'

import { useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  BellOff,
  ChevronLeft,
  ChevronRight,
  Copy,
  Crown,
  Image as ImageIcon,
  Link2,
  LoaderCircle,
  Megaphone,
  Palette,
  Pin,
  Search,
  SearchX,
  Timer,
  UserRoundMinus,
  UserRoundPlus,
  Users,
  VolumeX,
} from 'lucide-react'
import type { AppUser, ChatMessage, ConversationDetail, GroupRole } from '@/lib/types'
import { navigateHash } from '@/lib/hash-router'
import { apiJson, gradientFor, groupGradientFor, jsonBody, formatListStamp } from '@/lib/pulse-utils'
import { ease, spring, stagger } from '@/lib/motion'
import { haptic } from '@/lib/pulse-settings'
import { toast } from 'sonner'
import { GroupAvatar, UserAvatar } from '@/components/chat/user-avatar'
import { RoomMemberAddPage } from '@/components/chat/room-member-add'
import { ConvThemePicker } from '@/components/chat/conv-theme-picker'
import { convThemeSummary } from '@/lib/conv-theme'
import { usePrefsValues } from '@/lib/prefs'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

export interface RoomInfoPageProps {
  me: AppUser
  conversationId: string
  /** cache-shared with the room's ['conversation', id] query */
  detail?: ConversationDetail
  /** live presence from the socket context */
  onlineIds: ReadonlySet<string>
  /** the room's real loaded message window — quick-stats source */
  loadedMessages: ChatMessage[]
  /** authoritative pinned count (GET /pinned cache) */
  pinnedCount: number
  /** true when older history exists beyond the loaded window */
  historyPartial: boolean
  reducedMotion?: boolean
  /** backHash() — returns to the room */
  onClose: () => void
  /** open the classic full group-manager sheet (kept reachable) */
  onOpenManager: () => void
}

/** Disappearing-message TTL → short label (mirrors the room header menu). */
function ttlLabel(ttlSeconds: number): string {
  if (ttlSeconds <= 0) return 'Off'
  if (ttlSeconds === 86_400) return '24h'
  if (ttlSeconds === 604_800) return '7d'
  if (ttlSeconds === 2_592_000) return '30d'
  return `${Math.max(1, Math.round(ttlSeconds / 86_400))}d`
}

export function RoomInfoPage({
  me,
  conversationId,
  detail,
  onlineIds,
  loadedMessages,
  pinnedCount,
  historyPartial,
  reducedMotion = false,
  onClose,
  onOpenManager,
}: RoomInfoPageProps) {
  const queryClient = useQueryClient()

  const isGroup = detail?.isGroup ?? false
  const other = !isGroup ? detail?.members.find((m) => m.id !== me.id) : undefined
  const title = isGroup ? detail?.name || 'Group' : other?.name ?? 'Chat'
  const myRole = detail?.members.find((m) => m.id === me.id)?.role
  const isAdmin = myRole === 'admin'

  const isMuted =
    detail?.myMutedUntil != null && Date.parse(detail.myMutedUntil) > Date.now()

  /** honest quick-stats — counts over the real loaded messages only */
  const stats = useMemo(() => {
    let media = 0
    let links = 0
    for (const m of loadedMessages) {
      if (m.deletedAt !== null) continue
      if (m.imagePath !== null) media += 1
      if (m.linkUrl !== null || /https?:\/\//i.test(m.content)) links += 1
    }
    return { media, links }
  }, [loadedMessages])

  const onlineCount = useMemo(
    () => (detail?.members ?? []).filter((m) => onlineIds.has(m.id)).length,
    [detail, onlineIds],
  )

  /** admins first, then name — stable view order */
  const members = useMemo(() => {
    const list = [...(detail?.members ?? [])]
    list.sort((a, b) => {
      const adminDelta = (a.role === 'admin' ? 0 : 1) - (b.role === 'admin' ? 0 : 1)
      if (adminDelta !== 0) return adminDelta
      return a.name.localeCompare(b.name)
    })
    return list
  }, [detail])

  const adminCount = useMemo(
    () => members.filter((m) => m.role === 'admin').length,
    [members],
  )

  /** directory ids already in the group — the add-members view subtracts them */
  const existingIds = useMemo(
    () => new Set((detail?.members ?? []).map((m) => m.id)),
    [detail],
  )

  // ── member-list search (only surfaced past 8 members) ─────

  const [memberFilter, setMemberFilter] = useState('')
  const memberQuery = memberFilter.trim().toLowerCase()
  const visibleMembers = useMemo(() => {
    if (members.length <= 8 || memberQuery.length === 0) return members
    return members.filter(
      (m) =>
        m.name.toLowerCase().includes(memberQuery) ||
        (m.username ?? '').toLowerCase().includes(memberQuery),
    )
  }, [members, memberQuery])

  // ── real actions ─────────────────────────────────────────

  const muteMutation = useMutation({
    mutationFn: async (until: '8h' | '1w' | 'always' | null) => {
      return apiJson<{ ok: boolean; mutedUntil: string | null }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/mute`,
        jsonBody({ userId: me.id, until }),
      )
    },
    onSuccess: (data) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, myMutedUntil: data.mutedUntil } : old,
      )
      toast.success(data.mutedUntil === null ? 'Notifications unmuted' : 'Notifications muted')
      haptic(12)
    },
    onError: () => toast.error('Could not update the mute'),
  })

  const inviteMutation = useMutation({
    mutationFn: async () => {
      return apiJson<{ inviteCode: string }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/invite`,
        jsonBody({ requesterId: me.id, regenerate: false }),
      )
    },
    onSuccess: (res) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, inviteCode: res.inviteCode } : old,
      )
      void copyInvite(res.inviteCode)
    },
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'Could not create the invite link'),
  })

  // POST /members/{userId} is DELETE-only (PATCH = role change) — the R27
  // draft sent method:POST via jsonBody and got a silent 405. Fixed here.
  const removeMutation = useMutation({
    mutationFn: async (userId: string) => {
      return apiJson<{ ok: boolean }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members/${encodeURIComponent(userId)}`,
        { method: 'DELETE', body: JSON.stringify({ requesterId: me.id }) },
      )
    },
    onMutate: async (userId: string) => {
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, members: old.members.filter((m) => m.id !== userId) } : old,
      )
      return { prev }
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success('Removed from the group')
      haptic(14)
    },
    onError: (error, _userId, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      toast.error(error instanceof Error ? error.message : 'Could not remove the member')
    },
  })

  // PATCH /members/{userId} { requesterId, action: 'promote' | 'demote' }
  // Admin-only server-side; demote 400s when the target is the last admin.
  const roleMutation = useMutation({
    mutationFn: async ({ userId, action }: { userId: string; action: 'promote' | 'demote' }) => {
      return apiJson<{ conversation: ConversationDetail }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/members/${encodeURIComponent(userId)}`,
        { method: 'PATCH', body: JSON.stringify({ requesterId: me.id, action }) },
      )
    },
    onSuccess: (_data, vars) => {
      // Surgical role patch — the response detail is built for the TARGET
      // viewer (its myMutedUntil is not ours), so never cache it wholesale.
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old
          ? {
              ...old,
              members: old.members.map((m) =>
                m.id === vars.userId
                  ? { ...m, role: (vars.action === 'promote' ? 'admin' : 'member') as GroupRole }
                  : m,
              ),
            }
          : old,
      )
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      toast.success(vars.action === 'promote' ? 'Promoted to admin' : 'Role set to member')
      haptic(12)
    },
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'Could not update the role'),
  })

  const copyInvite = async (code: string) => {
    const link = `${window.location.origin}/?join=${code}`
    try {
      await navigator.clipboard.writeText(link)
      toast.success('Invite link copied to clipboard')
    } catch {
      toast.error('Could not copy the link')
    }
  }

  // two-tap confirm for destructive row actions (remove / dismiss-admin)
  type ArmedConfirm = { id: string; kind: 'remove' | 'demote' }
  const [confirm, setConfirm] = useState<ArmedConfirm | null>(null)
  const confirmTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const armConfirm = (kind: ArmedConfirm['kind'], userId: string) => {
    if (confirmTimerRef.current !== null) clearTimeout(confirmTimerRef.current)
    setConfirm({ id: userId, kind })
    haptic(8)
    confirmTimerRef.current = setTimeout(() => {
      confirmTimerRef.current = null
      setConfirm(null)
    }, 2600)
  }
  const tapConfirm = (kind: ArmedConfirm['kind'], userId: string, run: () => void) => {
    if (confirm?.id === userId && confirm.kind === kind) {
      if (confirmTimerRef.current !== null) {
        clearTimeout(confirmTimerRef.current)
        confirmTimerRef.current = null
      }
      setConfirm(null)
      run()
    } else {
      armConfirm(kind, userId)
    }
  }
  // remove keeps the R27 two-tap shape — armRemove / tapRemove
  const armRemove = (userId: string) => armConfirm('remove', userId)
  const tapRemove = (userId: string) =>
    tapConfirm('remove', userId, () => removeMutation.mutate(userId))
  const tapDemote = (userId: string) =>
    tapConfirm('demote', userId, () => roleMutation.mutate({ userId, action: 'demote' }))

  // R28-b: full-screen add-members sub-view INSIDE the info page
  const [addOpen, setAddOpen] = useState(false)

  // R29-a: per-conversation chat theme (wallpaper/tint) — inline picker
  const prefs = usePrefsValues()
  const themeSummary = convThemeSummary(prefs, conversationId)
  const [themeOpen, setThemeOpen] = useState(false)

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
      aria-label={isGroup ? `About ${title}` : `About ${title}`}
      className="absolute inset-0 z-[55] flex flex-col bg-zinc-100/85 backdrop-blur-2xl dark:bg-black/70"
    >
      {/* header */}
      <div className="flex min-h-14 shrink-0 items-center gap-1.5 px-2 pt-[env(safe-area-inset-top)]">
        <button
          type="button"
          aria-label="Back to conversation"
          onClick={() => {
            haptic(6)
            onClose()
          }}
          className="glass-pill flex size-10 shrink-0 items-center justify-center text-zinc-600 outline-none transition-transform active:scale-90 dark:text-zinc-300"
        >
          <ChevronLeft className="size-6" aria-hidden />
        </button>
        <p className="text-sm font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
          {isGroup ? 'Group info' : 'Chat info'}
        </p>
      </div>

      <div className="pulse-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 pt-1 pb-8">
        {/* hero */}
        <motion.div
          {...anim}
          transition={spring.soft}
          className="glass-deep glass-sheen relative overflow-hidden rounded-3xl p-5"
        >
          <div
            aria-hidden
            className="pointer-events-none absolute -top-16 -right-14 size-44 rounded-full opacity-25 blur-2xl"
            style={{
              backgroundImage: isGroup
                ? groupGradientFor(conversationId)
                : gradientFor(other?.color ?? 'emerald'),
            }}
          />
          <div className="flex items-center gap-4">
            {isGroup ? (
              <GroupAvatar title={title} id={conversationId} size={68} />
            ) : (
              <UserAvatar
                name={other?.name ?? title}
                color={other?.color}
                avatar={other?.avatar}
                size={68}
                showPresence
                online={other ? onlineIds.has(other.id) : false}
              />
            )}
            <div className="min-w-0 flex-1">
              <div className="truncate text-lg font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
                {detail === undefined ? <Skeleton className="h-6 w-36 rounded-lg" /> : title}
              </div>
              {detail === undefined ? (
                <Skeleton className="mt-1.5 h-4 w-24 rounded-md" />
              ) : isGroup ? (
                <p className="mt-0.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
                  {members.length} {members.length === 1 ? 'member' : 'members'} · {onlineCount} online
                </p>
              ) : (
                <p className="mt-0.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
                  {other ? (onlineIds.has(other.id) ? 'Online now' : 'Offline') : 'Direct chat'}
                </p>
              )}
              <div className="mt-2 flex flex-wrap gap-1.5">
                {isGroup && (detail?.broadcastMode ?? false) ? (
                  <span className="flex items-center gap-1 rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                    <Megaphone className="size-3" aria-hidden />
                    Announcements only
                  </span>
                ) : null}
                {detail && detail.ttlSeconds > 0 ? (
                  <span className="flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                    <Timer className="size-3" aria-hidden />
                    Disappearing · {ttlLabel(detail.ttlSeconds)}
                  </span>
                ) : null}
              </div>
            </div>
          </div>
        </motion.div>

        {/* quick stats — real counts over the loaded window */}
        <motion.div {...anim} transition={{ ...spring.soft, delay: stagger(1) }} className="mt-3 grid grid-cols-3 gap-2">
          {[
            { icon: ImageIcon, label: 'Photos', value: stats.media },
            { icon: Link2, label: 'Links', value: stats.links },
            { icon: Pin, label: 'Pinned', value: pinnedCount },
          ].map((tile) => (
            <div
              key={tile.label}
              className="glass-deep glass-sheen flex flex-col items-center gap-0.5 rounded-2xl px-2 py-3"
            >
              <tile.icon className="size-4 text-emerald-500" aria-hidden />
              <span className="text-base font-bold tabular-nums text-zinc-900 dark:text-zinc-50">
                {tile.value}
              </span>
              <span className="text-[10px] font-semibold tracking-wide text-zinc-400 uppercase dark:text-zinc-500">
                {tile.label}
              </span>
            </div>
          ))}
        </motion.div>
        {historyPartial ? (
          <p className="mt-1.5 px-1 text-center text-[10px] text-zinc-400 dark:text-zinc-500">
            Counted from loaded history — older messages exist.
          </p>
        ) : null}

        {/* actions */}
        <motion.div
          {...anim}
          transition={{ ...spring.soft, delay: stagger(2) }}
          className="glass-deep glass-sheen mt-3 overflow-hidden rounded-3xl p-1.5"
        >
          {/* mute — real per-user watermark API */}
          <div className="glass-row-hover flex items-center gap-3 rounded-2xl px-3 py-2.5">
            {isMuted ? (
              <VolumeX className="size-4 shrink-0 text-emerald-500" aria-hidden />
            ) : (
              <BellOff className="size-4 shrink-0 text-zinc-400" aria-hidden />
            )}
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                {isMuted ? 'Notifications muted' : 'Mute notifications'}
              </p>
              {isMuted && detail?.myMutedUntil ? (
                <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
                  until {formatListStamp(detail.myMutedUntil)}
                </p>
              ) : null}
            </div>
            {isMuted ? (
              <button
                type="button"
                disabled={muteMutation.isPending}
                onClick={() => muteMutation.mutate(null)}
                className="glass-pill h-8 shrink-0 px-3 text-xs font-bold text-emerald-600 outline-none transition-transform active:scale-95 disabled:opacity-50 dark:text-emerald-400"
              >
                Unmute
              </button>
            ) : (
              <div className="flex shrink-0 gap-1" role="group" aria-label="Mute duration">
                {(['8h', '1w', 'always'] as const).map((preset) => (
                  <button
                    key={preset}
                    type="button"
                    disabled={muteMutation.isPending}
                    onClick={() => muteMutation.mutate(preset)}
                    className="h-8 rounded-full bg-zinc-900/[0.05] px-2.5 text-[11px] font-bold text-zinc-600 outline-none transition-transform hover:bg-emerald-500/15 hover:text-emerald-700 active:scale-95 disabled:opacity-50 dark:bg-white/[0.07] dark:text-zinc-300 dark:hover:text-emerald-400"
                  >
                    {preset === 'always' ? 'Always' : preset}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* disappearing TTL — display-only (changing lives in the room menu) */}
          <div className="glass-row-hover flex items-center gap-3 rounded-2xl px-3 py-2.5">
            <Timer className="size-4 shrink-0 text-zinc-400" aria-hidden />
            <p className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
              Disappearing messages
            </p>
            <span
              className={cn(
                'shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold',
                (detail?.ttlSeconds ?? 0) > 0
                  ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                  : 'bg-zinc-900/[0.05] text-zinc-500 dark:bg-white/[0.07] dark:text-zinc-400',
              )}
            >
              {detail === undefined ? '…' : ttlLabel(detail.ttlSeconds)}
            </span>
          </div>

          {/* R29-a: chat theme — per-conversation wallpaper/tint override.
              Row shows the effective theme; Customize expands the picker
              inline (slide inside the page, not a route/overlay). */}
          <div className="glass-row-hover flex items-center gap-3 rounded-2xl px-3 py-2.5">
            <Palette className="size-4 shrink-0 text-zinc-400" aria-hidden />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                Chat theme
              </p>
              <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                {themeSummary.text}
              </p>
            </div>
            <button
              type="button"
              aria-expanded={themeOpen}
              onClick={() => {
                haptic(8)
                setThemeOpen((v) => !v)
              }}
              className="glass-pill h-8 shrink-0 px-3 text-xs font-bold text-emerald-600 outline-none transition-transform active:scale-95 dark:text-emerald-400"
            >
              {themeOpen ? 'Close' : 'Customize'}
            </button>
          </div>
          <AnimatePresence initial={false}>
            {themeOpen ? (
              <motion.div
                key="conv-theme-picker"
                initial={reducedMotion ? false : { opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={reducedMotion ? undefined : { opacity: 0, height: 0 }}
                transition={spring.soft}
                className="overflow-hidden"
              >
                <ConvThemePicker conversationId={conversationId} reducedMotion={reducedMotion} />
              </motion.div>
            ) : null}
          </AnimatePresence>

          {/* invite link — real /invite API, groups + admin only (hidden honestly) */}
          {isGroup && isAdmin ? (
            <div className="glass-row-hover flex items-center gap-3 rounded-2xl px-3 py-2.5">
              <Users className="size-4 shrink-0 text-zinc-400" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                  Invite link
                </p>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  {detail?.inviteCode ? `Code ${detail.inviteCode}` : 'No active link yet'}
                </p>
              </div>
              <button
                type="button"
                disabled={inviteMutation.isPending}
                onClick={() => {
                  haptic(8)
                  if (detail?.inviteCode) void copyInvite(detail.inviteCode)
                  else inviteMutation.mutate()
                }}
                className="glass-pill flex h-8 shrink-0 items-center gap-1 px-3 text-xs font-bold text-emerald-600 outline-none transition-transform active:scale-95 disabled:opacity-50 dark:text-emerald-400"
              >
                {inviteMutation.isPending ? (
                  <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                ) : (
                  <Copy className="size-3.5" aria-hidden />
                )}
                {detail?.inviteCode ? 'Copy' : 'Create'}
              </button>
            </div>
          ) : null}

          {/* add members — POST /members is admins-only server-side (403 otherwise),
              so the row renders for admins only — no dead UI for members */}
          {isGroup && isAdmin && detail ? (
            <button
              type="button"
              onClick={() => {
                haptic(8)
                setAddOpen(true)
              }}
              className="glass-row-hover flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none"
            >
              <UserRoundPlus className="size-4 shrink-0 text-emerald-500" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                  Add members
                </p>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  From the Pulse directory
                </p>
              </div>
              <ChevronRight className="size-4 shrink-0 text-zinc-400" aria-hidden />
            </button>
          ) : null}

          {/* classic group manager — kept reachable from its natural home */}
          {isGroup ? (
            <button
              type="button"
              onClick={() => {
                haptic(8)
                onClose()
                onOpenManager()
              }}
              className="glass-row-hover flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none"
            >
              <Users className="size-4 shrink-0 text-zinc-400" aria-hidden />
              <p className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                Manage group — roles, webhooks, more
              </p>
              <ChevronRight className="size-4 shrink-0 text-zinc-400" aria-hidden />
            </button>
          ) : null}
        </motion.div>

        {/* members */}
        <motion.p
          {...anim}
          transition={{ ...spring.soft, delay: stagger(3) }}
          className="px-2 pt-4 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500"
        >
          Members{detail ? ` · ${members.length}` : ''}
        </motion.p>
        <motion.div
          {...anim}
          transition={{ ...spring.soft, delay: stagger(3) }}
          className="glass-deep glass-sheen overflow-hidden rounded-3xl p-1.5"
        >
          {detail === undefined ? (
            <div className="space-y-2 p-1" role="status" aria-label="Loading members">
              <Skeleton className="h-11 w-full rounded-2xl" />
              <Skeleton className="h-11 w-full rounded-2xl" />
              <Skeleton className="h-11 w-4/5 rounded-2xl" />
            </div>
          ) : (
            <>
              {/* R28-b: glass filter — only surfaced past 8 members */}
              {members.length > 8 ? (
                <motion.div
                  initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.2, ease: ease.out }}
                  className="glass-pill relative m-0.5 mb-1"
                >
                  <Search
                    className="pointer-events-none absolute top-1/2 left-3 size-3.5 -translate-y-1/2 text-zinc-400"
                    aria-hidden
                  />
                  <input
                    value={memberFilter}
                    onChange={(e) => setMemberFilter(e.target.value.slice(0, 40))}
                    placeholder="Search members"
                    aria-label="Search members"
                    autoComplete="off"
                    className="h-9 w-full rounded-full bg-transparent pr-8 pl-9 text-[13px] text-zinc-900 placeholder:text-zinc-400 outline-none dark:text-zinc-100"
                  />
                  {memberFilter.length > 0 ? (
                    <button
                      type="button"
                      aria-label="Clear member search"
                      onClick={() => setMemberFilter('')}
                      className="absolute top-1/2 right-1.5 -translate-y-1/2 rounded-full p-1.5 text-zinc-400 outline-none transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
                    >
                      <SearchX className="size-3.5" aria-hidden />
                    </button>
                  ) : null}
                </motion.div>
              ) : null}
              <ul>
                {visibleMembers.map((member, i) => {
                  const isMe = member.id === me.id
                  const online = onlineIds.has(member.id)
                  // mirror of the API's real guards: DELETE /members/[userId] is
                  // admin-only, 400s on self, 403s on admin targets — no dead UI
                  const canRemove =
                    isAdmin && !isMe && member.role !== 'admin' && !removeMutation.isPending
                  const rolePendingHere =
                    roleMutation.isPending && roleMutation.variables?.userId === member.id
                  const roleBusy = roleMutation.isPending && !rolePendingHere
                  const demoteArmed = confirm?.id === member.id && confirm.kind === 'demote'
                  const removeArmed = confirm?.id === member.id && confirm.kind === 'remove'
                  return (
                    <motion.li
                      key={member.id}
                      initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{
                        duration: 0.2,
                        delay: Math.min(i * 0.03, 0.24),
                        ease: ease.out,
                      }}
                      className="glass-row-hover flex items-center gap-3 rounded-2xl px-2 py-2"
                    >
                      <button
                        type="button"
                        aria-label={isMe ? 'Your profile' : `View ${member.name}'s profile`}
                        onClick={() => {
                          haptic(8)
                          // push straight through: info unmounts, browser back
                          // returns to #/room/<id>/info — no async back-race
                          navigateHash(`#/user/${encodeURIComponent(member.id)}`)
                        }}
                        className="shrink-0 rounded-full outline-none transition-transform active:scale-90"
                      >
                        <UserAvatar
                          name={member.name}
                          color={member.color}
                          avatar={member.avatar}
                          size={38}
                          showPresence
                          online={online}
                        />
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          haptic(8)
                          // push straight through: info unmounts, browser back
                          // returns to #/room/<id>/info — no async back-race
                          navigateHash(`#/user/${encodeURIComponent(member.id)}`)
                        }}
                        className="min-w-0 flex-1 text-left outline-none"
                      >
                        <span className="flex min-w-0 items-center gap-1.5">
                          <span className="truncate text-sm font-semibold text-zinc-900 dark:text-zinc-50">
                            {member.name}
                          </span>
                          {member.role === 'admin' ? (
                            <Crown className="size-3.5 shrink-0 text-amber-500" aria-label="Admin" />
                          ) : null}
                          {isMe ? (
                            <span className="shrink-0 text-[11px] font-medium text-zinc-400">(you)</span>
                          ) : null}
                        </span>
                        <span className="block truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                          {member.role === 'admin' ? 'Admin · ' : ''}
                          {member.username ? `@${member.username}` : member.about || 'Member'}
                        </span>
                      </button>
                      {/* R28-b: role actions — PATCH /members/[userId] is admin-only;
                          the last admin is never dismissable (server 400s, we hide) */}
                      {isAdmin && !isMe ? (
                        member.role === 'member' ? (
                          <button
                            type="button"
                            disabled={roleBusy}
                            aria-label={`Make ${member.name} an admin`}
                            onClick={() => {
                              haptic(8)
                              roleMutation.mutate({ userId: member.id, action: 'promote' })
                            }}
                            className="flex h-8 shrink-0 items-center gap-1 rounded-full px-2.5 text-[11px] font-bold text-zinc-400 outline-none transition-all hover:bg-emerald-500/10 hover:text-emerald-600 active:scale-95 disabled:opacity-40 dark:text-zinc-500 dark:hover:text-emerald-400"
                          >
                            {rolePendingHere ? (
                              <LoaderCircle className="size-3 animate-spin" aria-hidden />
                            ) : (
                              <Crown className="size-3" aria-hidden />
                            )}
                            Make admin
                          </button>
                        ) : adminCount > 1 ? (
                          <button
                            type="button"
                            disabled={roleBusy}
                            aria-label={demoteArmed ? `Confirm demoting ${member.name}` : `Demote ${member.name}`}
                            onClick={() => tapDemote(member.id)}
                            className={cn(
                              'flex h-8 shrink-0 items-center gap-1 rounded-full px-2.5 text-[11px] font-bold outline-none transition-all active:scale-95 disabled:opacity-40',
                              demoteArmed
                                ? 'bg-rose-500/15 text-rose-600 dark:text-rose-400'
                                : 'bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 dark:text-amber-400',
                            )}
                          >
                            {rolePendingHere ? (
                              <LoaderCircle className="size-3 animate-spin" aria-hidden />
                            ) : (
                              <Crown className="size-3" aria-hidden />
                            )}
                            {demoteArmed ? 'Dismiss?' : 'Admin'}
                          </button>
                        ) : (
                          <span
                            aria-label="Last admin of this group"
                            className="flex h-8 shrink-0 items-center gap-1 rounded-full bg-amber-500/10 px-2.5 text-[11px] font-bold text-amber-600 dark:text-amber-400"
                          >
                            <Crown className="size-3" aria-hidden />
                            Admin
                          </span>
                        )
                      ) : null}
                      {canRemove ? (
                        removeArmed ? (
                          <button
                            type="button"
                            onClick={() => tapRemove(member.id)}
                            className="shrink-0 rounded-full bg-rose-500/15 px-2.5 py-1 text-[11px] font-bold text-rose-600 outline-none transition-transform active:scale-95 dark:text-rose-400"
                          >
                            Remove?
                          </button>
                        ) : (
                          <button
                            type="button"
                            aria-label={`Remove ${member.name}`}
                            onClick={() => tapRemove(member.id)}
                            className="flex size-8 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-90"
                          >
                            <UserRoundMinus className="size-4" aria-hidden />
                          </button>
                        )
                      ) : null}
                    </motion.li>
                  )
                })}
              </ul>
              {visibleMembers.length === 0 && members.length > 0 ? (
                <p className="flex items-center gap-2 px-3 py-4 text-xs text-zinc-400 dark:text-zinc-500">
                  <SearchX className="size-3.5 shrink-0" aria-hidden />
                  No members match “{memberFilter.trim()}”
                </p>
              ) : null}
            </>
          )}
        </motion.div>
      </div>

      {/* R28-b: add-members glass sub-view — slides INSIDE the info page (not a route) */}
      <AnimatePresence>
        {addOpen ? (
          <RoomMemberAddPage
            key="room-member-add"
            me={me}
            conversationId={conversationId}
            existingIds={existingIds}
            reducedMotion={reducedMotion}
            onClose={() => setAddOpen(false)}
          />
        ) : null}
      </AnimatePresence>
    </motion.div>
  )
}

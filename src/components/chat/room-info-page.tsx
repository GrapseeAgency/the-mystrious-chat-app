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

import { useRef, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BadgeCheck,
  BellOff,
  Camera,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Copy,
  Crown,
  Flame,
  Image as ImageIcon,
  Hourglass,
  Link2,
  LoaderCircle,
  LogOut,
  Megaphone,
  Palette,
  Pin,
  Search,
  SearchX,
  ShieldCheck,
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
import { uploadConversationPhoto } from '@/lib/upload-photo'
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

/** TTL → spoken length for toasts/subtitles (mirrors the room header menu). */
function ttlLong(ttlSeconds: number): string {
  if (ttlSeconds === 86_400) return '24 hours'
  if (ttlSeconds === 604_800) return '7 days'
  if (ttlSeconds === 2_592_000) return '30 days'
  return `${Math.max(1, Math.round(ttlSeconds / 86_400))} days`
}

// ── R34-b: Signal-style disappearing timer ──────────────────
/** The EXACT TTL presets the disappearing API accepts (seconds). 0 = off. */
const TTL_STOPS = [0, 86_400, 604_800, 2_592_000] as const // off · 24h · 7d · 30d
const TTL_STOP_LABELS = ['Off', '24h', '7d', '30d'] as const

/**
 * Horizontal drag-to-set timer track. Draggable springy thumb over the
 * supported TTL stops, a live label riding under the thumb, and the old
 * preset pills kept as tap-to-commit tick labels below. Pointer events
 * only (touch + mouse), `touch-none` so the page never scrolls mid-drag.
 */
function DisappearingSlider({
  ttlSeconds,
  disabled,
  reducedMotion,
  onCommit,
}: {
  ttlSeconds: number
  disabled: boolean
  reducedMotion: boolean
  onCommit: (ttlSeconds: number) => void
}) {
  const trackRef = useRef<HTMLDivElement | null>(null)
  const [draftIdx, setDraftIdx] = useState(() =>
    Math.max(0, TTL_STOPS.indexOf(ttlSeconds as (typeof TTL_STOPS)[number])),
  )
  const [dragging, setDragging] = useState(false)

  // follow the committed server truth whenever a drag is not in flight
  const committedIdx = Math.max(
    0,
    TTL_STOPS.indexOf(ttlSeconds as (typeof TTL_STOPS)[number]),
  )
  const shownIdx = dragging ? draftIdx : committedIdx

  const idxFromClientX = (clientX: number): number => {
    const el = trackRef.current
    if (!el) return committedIdx
    const rect = el.getBoundingClientRect()
    if (rect.width <= 0) return committedIdx
    const ratio = (clientX - rect.left) / rect.width
    return Math.min(TTL_STOPS.length - 1, Math.max(0, Math.round(ratio * (TTL_STOPS.length - 1))))
  }

  const stopPct = (shownIdx / (TTL_STOPS.length - 1)) * 100
  // keep the riding label inside the card at the extreme stops
  const labelPct = Math.min(86, Math.max(14, stopPct))
  const slideTransition = reducedMotion ? { duration: 0.12 } : spring.snappy

  return (
    <div className="mt-3 px-0.5 pb-0.5">
      {/* live label riding under the thumb */}
      <div className="relative mb-0.5 h-4" aria-hidden>
        <motion.span
          animate={{ left: `${labelPct}%` }}
          transition={slideTransition}
          style={{ x: '-50%' }}
          className="absolute top-0 whitespace-nowrap rounded-full bg-emerald-500/15 px-1.5 py-px text-[9.5px] font-bold text-emerald-600 ring-1 ring-inset ring-emerald-500/25 dark:text-emerald-400"
        >
          {TTL_STOP_LABELS[shownIdx]}
        </motion.span>
      </div>

      <div
        ref={trackRef}
        role="slider"
        aria-label="Disappearing message timer"
        aria-valuemin={0}
        aria-valuemax={TTL_STOPS.length - 1}
        aria-valuenow={shownIdx}
        aria-valuetext={TTL_STOP_LABELS[shownIdx]}
        aria-disabled={disabled}
        onPointerDown={(event) => {
          if (disabled) return
          event.currentTarget.setPointerCapture(event.pointerId)
          setDragging(true)
          setDraftIdx(idxFromClientX(event.clientX))
          haptic(6)
        }}
        onPointerMove={(event) => {
          if (!dragging || disabled) return
          const idx = idxFromClientX(event.clientX)
          if (idx !== draftIdx) {
            setDraftIdx(idx)
            haptic(4)
          }
        }}
        onPointerUp={(event) => {
          if (!dragging) return
          setDragging(false)
          const idx = idxFromClientX(event.clientX)
          setDraftIdx(idx)
          haptic(10)
          const next = TTL_STOPS[idx]
          if (next !== ttlSeconds) onCommit(next)
        }}
        onPointerCancel={() => setDragging(false)}
        className={cn('relative h-7 touch-none select-none', disabled && 'pointer-events-none opacity-50')}
      >
        {/* resting track + emerald fill up to the thumb */}
        <div className="absolute top-1/2 h-1.5 w-full -translate-y-1/2 rounded-full bg-zinc-900/[0.07] dark:bg-white/10" />
        <div
          className="absolute top-1/2 h-1.5 -translate-y-1/2 rounded-full bg-gradient-to-r from-emerald-500/60 to-emerald-500 transition-[width] duration-150"
          style={{ width: `${stopPct}%` }}
        />
        {/* tick stops */}
        {TTL_STOPS.map((_, idx) => (
          <span
            key={idx}
            className={cn(
              'absolute top-1/2 size-2 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-white dark:ring-zinc-900',
              idx <= shownIdx ? 'bg-emerald-500' : 'bg-zinc-300 dark:bg-zinc-600',
            )}
            style={{ left: `${(idx / (TTL_STOPS.length - 1)) * 100}%` }}
          />
        ))}
        {/* springy thumb — scales on grab for the haptic feel */}
        <motion.span
          aria-hidden
          animate={{ left: `${stopPct}%`, scale: dragging ? 1.18 : 1 }}
          transition={slideTransition}
          style={{ x: '-50%', y: '-50%' }}
          className="absolute top-1/2 z-10 flex size-5 items-center justify-center rounded-full bg-white shadow-[0_2px_10px_rgba(0,0,0,0.28)] ring-1 ring-black/10 dark:bg-zinc-100"
        >
          <span className="block size-2 rounded-full bg-emerald-500" />
        </motion.span>
      </div>

      {/* the old preset pills — kept as tap-to-commit tick labels */}
      <div className="mt-1.5 grid grid-cols-4 gap-1" role="group" aria-label="Timer presets">
        {TTL_STOPS.map((t, idx) => (
          <button
            key={t}
            type="button"
            disabled={disabled}
            onClick={() => onCommit(t)}
            className={cn(
              'h-7 rounded-full text-[11px] font-bold outline-none transition-colors active:scale-95 disabled:opacity-50',
              idx === shownIdx
                ? 'bg-emerald-500/15 text-emerald-700 ring-1 ring-emerald-400 dark:text-emerald-300'
                : 'bg-zinc-900/[0.05] text-zinc-600 hover:bg-emerald-500/15 hover:text-emerald-700 dark:bg-white/[0.07] dark:text-zinc-300 dark:hover:text-emerald-400',
            )}
          >
            {TTL_STOP_LABELS[idx]}
          </button>
        ))}
      </div>
    </div>
  )
}

// ── R35-a: Signal-style safety-number verification (DMs only) ──

/** GET/POST payload shape of /api/users/[peerId]/safety. */
interface SafetyState {
  peerId: string
  safetyNumber: string
  verified: boolean
  verifiedAt: string | null
}

/** Shared TanStack cache key for one viewer→peer verification pair. */
function safetyKey(peerId: string, meId: string): ['safety', string, string] {
  return ['safety', peerId, meId]
}

/**
 * "##### ##### …" → the 12 five-digit group strings for the sheet's grid.
 * Pure local split — src/lib/safety.ts is server-only (node:crypto), so the
 * client never imports it; the API already returns the formatted string.
 */
function safetyGroups(safetyNumber: string): string[] {
  const digits = safetyNumber.replace(/\D/g, '').padStart(60, '0')
  const groups: string[] = []
  for (let i = 0; i < 12; i += 1) groups.push(digits.slice(i * 5, i * 5 + 5))
  return groups
}

/**
 * Bottom glass sheet: the pair's 60-digit safety number as a 3×4 grid of
 * glass tiles, the honest compare-in-person caption and the real verify /
 * reset actions over /api/users/[peerId]/safety. Backdrop tap dismisses;
 * spring entrance mirrors the reminders-sheet conventions.
 */
function SafetyNumberSheet({
  peer,
  meId,
  reducedMotion,
  onClose,
}: {
  peer: AppUser
  meId: string
  reducedMotion: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()

  const safetyQuery = useQuery({
    queryKey: safetyKey(peer.id, meId),
    queryFn: () =>
      apiJson<SafetyState>(
        `/api/users/${encodeURIComponent(peer.id)}/safety?userId=${encodeURIComponent(meId)}`,
      ),
    staleTime: 10_000,
  })
  const state = safetyQuery.data
  const groups = useMemo(() => safetyGroups(state?.safetyNumber ?? ''), [state?.safetyNumber])
  const verified = state?.verified ?? false

  const verifyMutation = useMutation({
    mutationFn: () =>
      apiJson<{ verified: true; verifiedAt: string }>(
        `/api/users/${encodeURIComponent(peer.id)}/safety`,
        { method: 'POST', body: JSON.stringify({ userId: meId }) },
      ),
    onSuccess: () => {
      toast.success('Safety number verified')
      haptic(14)
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : 'Could not verify the safety number',
      ),
    // No optimistic lies — refetch the server truth on settle.
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: safetyKey(peer.id, meId) }),
  })

  const resetMutation = useMutation({
    mutationFn: () =>
      apiJson<{ verified: false }>(
        `/api/users/${encodeURIComponent(peer.id)}/safety?userId=${encodeURIComponent(meId)}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => {
      toast.success('Verification reset')
      haptic(10)
    },
    onError: (error) =>
      toast.error(
        error instanceof Error ? error.message : 'Could not reset the verification',
      ),
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: safetyKey(peer.id, meId) }),
  })

  const busy = verifyMutation.isPending || resetMutation.isPending

  return (
    <>
      {/* backdrop — tap anywhere outside to dismiss */}
      <motion.button
        type="button"
        aria-hidden
        tabIndex={-1}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.16 }}
        onClick={onClose}
        className="absolute inset-0 z-[60] cursor-default bg-zinc-950/25 outline-none backdrop-blur-[2px] dark:bg-black/45"
      />
      {/* glass bottom sheet — rounded top, spring entrance */}
      <motion.div
        role="dialog"
        aria-label={`Safety number with ${peer.name}`}
        initial={reducedMotion ? false : { y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={spring.soft}
        className="glass-deep glass-sheen absolute inset-x-0 bottom-0 z-[61] mx-auto flex max-h-[86%] w-full flex-col overflow-hidden rounded-t-3xl px-4 pt-2.5 pb-[max(1rem,env(safe-area-inset-bottom))]"
      >
        {/* grab rail */}
        <div
          aria-hidden
          className="mx-auto mb-2 h-1 w-10 shrink-0 rounded-full bg-zinc-900/15 dark:bg-white/20"
        />

        {/* peer identity */}
        <div className="flex items-center gap-3 px-1">
          <UserAvatar name={peer.name} color={peer.color} avatar={peer.avatar} size={40} />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-bold text-zinc-900 dark:text-zinc-50">
              {peer.name}
            </p>
            <p className="text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500">
              Encryption
            </p>
          </div>
        </div>

        {/* the 60 digits — 12 groups of 5, 3 × 4 glass tiles */}
        {safetyQuery.isPending && !state ? (
          <div
            className="mt-3 grid grid-cols-3 gap-1.5"
            role="status"
            aria-label="Loading safety number"
          >
            {Array.from({ length: 12 }).map((_, i) => (
              <Skeleton key={i} className="h-10 rounded-xl" />
            ))}
          </div>
        ) : (
          <div className="mt-3 grid grid-cols-3 gap-1.5">
            {groups.map((group, i) => (
              <motion.span
                key={i}
                initial={reducedMotion ? false : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{
                  duration: 0.18,
                  delay: Math.min(i * 0.025, 0.2),
                  ease: ease.out,
                }}
                className="flex h-10 items-center justify-center rounded-xl border border-zinc-900/[0.06] bg-white/45 font-mono text-[13px] font-semibold tabular-nums tracking-[0.14em] text-zinc-800 dark:border-white/[0.09] dark:bg-white/[0.06] dark:text-zinc-100"
              >
                {group}
              </motion.span>
            ))}
          </div>
        )}

        <p className="mt-2.5 px-1 text-center text-[11px] leading-relaxed text-zinc-500 dark:text-zinc-400">
          Compare these 60 digits with {peer.name} in person. If they match, mark this
          contact as verified.
        </p>

        {/* actions — real API calls, loading states, no optimistic lies */}
        <div className="mt-3 flex flex-col gap-2">
          {verified ? (
            <>
              <span
                className="mx-auto flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-3 py-1.5 text-xs font-bold text-emerald-600 dark:text-emerald-400"
                aria-label="Contact verified"
              >
                <BadgeCheck className="size-3.5" aria-hidden />
                Verified
                {state?.verifiedAt ? (
                  <span className="font-semibold opacity-70">
                    · {formatListStamp(state.verifiedAt)}
                  </span>
                ) : null}
              </span>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  haptic(8)
                  resetMutation.mutate()
                }}
                className="h-9 w-full rounded-full bg-zinc-900/[0.05] text-xs font-bold text-zinc-500 outline-none transition-colors hover:bg-rose-500/10 hover:text-rose-500 active:scale-[0.98] disabled:opacity-50 dark:bg-white/[0.07] dark:text-zinc-400"
              >
                {resetMutation.isPending ? (
                  <LoaderCircle className="mx-auto size-3.5 animate-spin" aria-hidden />
                ) : (
                  'Reset verification'
                )}
              </button>
            </>
          ) : (
            <button
              type="button"
              disabled={busy || safetyQuery.isPending}
              onClick={() => {
                haptic(8)
                verifyMutation.mutate()
              }}
              className="flex h-11 w-full shrink-0 items-center justify-center gap-1.5 rounded-full bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 outline-none transition-all hover:bg-emerald-500/90 active:scale-[0.98] disabled:opacity-60"
            >
              {verifyMutation.isPending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <ShieldCheck className="size-4" aria-hidden />
              )}
              Mark as verified
            </button>
          )}
        </div>
      </motion.div>
    </>
  )
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
  /** R30-c — broadcast channel (WhatsApp-Channels style): subscribers language */
  const isChannel = isGroup && (detail?.broadcastMode ?? false)

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

  // two-tap confirm for destructive row actions (remove / dismiss-admin / leave)
  type ArmedConfirm = { id: string; kind: 'remove' | 'demote' | 'leave' }
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

  // R33-b — channel/group photo: admins get an "Edit photo" overlay on the
  // hero avatar (real upload chain: file → /api/uploads → PATCH photo).
  const photoInputRef = useRef<HTMLInputElement | null>(null)
  const [photoBusy, setPhotoBusy] = useState(false)
  const setPhotoMutation = useMutation({
    mutationFn: async (photo: string) => {
      return apiJson<{ conversation: ConversationDetail }>(
        `/api/conversations/${encodeURIComponent(conversationId)}`,
        { method: 'PATCH', body: JSON.stringify({ requesterId: me.id, photo }) },
      )
    },
    onMutate: async (photo: string) => {
      await queryClient.cancelQueries({ queryKey: ['conversation', conversationId] })
      const prev = queryClient.getQueryData<ConversationDetail>(['conversation', conversationId])
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], (old) =>
        old ? { ...old, photo } : old,
      )
      return { prev }
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      void queryClient.invalidateQueries({ queryKey: ['channels', me.id] })
      toast.success('Photo updated')
      haptic(12)
    },
    onError: (error, _photo, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['conversation', conversationId], ctx.prev)
      toast.error(error instanceof Error ? error.message : 'Could not update the photo')
    },
  })

  const handlePhotoPicked = async (file: File) => {
    if (photoBusy) return
    haptic(10)
    setPhotoBusy(true)
    try {
      const path = await uploadConversationPhoto(file)
      setPhotoMutation.mutate(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not upload that photo')
    } finally {
      setPhotoBusy(false)
    }
  }

  // R33-b — honest channel succession: leaving a broadcast channel. An admin
  // may leave while another admin remains; the LAST admin is blocked (the
  // server 403s — the UI says so before it ever fires).
  const leaveMutation = useMutation({
    mutationFn: async () => {
      return apiJson<{ ok: boolean }>(
        `/api/channels/${encodeURIComponent(conversationId)}/subscribe`,
        { method: 'DELETE', body: JSON.stringify({ userId: me.id }) },
      )
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      void queryClient.invalidateQueries({ queryKey: ['channels', me.id] })
      void queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] })
      toast.success(`You left ${title}`)
      haptic(14)
      onClose()
    },
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'Could not leave the channel'),
  })
  const leaveArmed = confirm?.id === me.id && confirm.kind === 'leave'

  // R29-a: per-conversation chat theme (wallpaper/tint) — inline picker
  const prefs = usePrefsValues()
  const themeSummary = convThemeSummary(prefs, conversationId)
  const [themeOpen, setThemeOpen] = useState(false)

  // R34-b: Signal-style disappearing-timer slider — any participant may
  // change it (the disappearing API allows every participant; mirrors the
  // room header menu's TTL submenu, which stays reachable).
  const [ttlOpen, setTtlOpen] = useState(false)
  const ttlMutation = useMutation({
    mutationFn: async (ttlSeconds: number) =>
      apiJson<{ conversation: ConversationDetail }>(
        `/api/conversations/${encodeURIComponent(conversationId)}/disappearing`,
        { method: 'PATCH', body: JSON.stringify({ userId: me.id, ttlSeconds }) },
      ),
    onSuccess: (data) => {
      queryClient.setQueryData<ConversationDetail>(['conversation', conversationId], data.conversation)
      void queryClient.invalidateQueries({ queryKey: ['conversations', me.id] })
      const t = data.conversation.ttlSeconds
      toast.success(t === 0 ? 'Disappearing messages off' : `New messages vanish after ${ttlLong(t)}`)
      haptic(12)
    },
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'Could not update disappearing messages'),
  })

  // R35-a — Signal-style safety number (DMs ONLY): the shared GET feeds the
  // Encryption row's trailing state; the sheet reads the same cache key.
  // Groups render nothing extra — honest per-room-type UI.
  const peer = !isGroup ? other : undefined
  const [safetyOpen, setSafetyOpen] = useState(false)
  const safetyQuery = useQuery({
    queryKey: peer ? safetyKey(peer.id, me.id) : ['safety', '-', me.id],
    queryFn: ({ queryKey }) =>
      apiJson<SafetyState>(
        `/api/users/${encodeURIComponent(queryKey[1])}/safety?userId=${encodeURIComponent(me.id)}`,
      ),
    enabled: peer !== undefined,
    staleTime: 10_000,
  })

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
              <span className="relative shrink-0">
                <GroupAvatar title={title} id={conversationId} size={68} photo={detail?.photo ?? null} />
                {/* R33-b — "Edit photo" overlay for admins: opens the real
                    upload chain (file → /api/uploads → PATCH conversation). */}
                {isAdmin && detail !== undefined ? (
                  <button
                    type="button"
                    aria-label={detail.photo ? 'Edit channel photo' : 'Add channel photo'}
                    disabled={photoBusy || setPhotoMutation.isPending}
                    onClick={() => {
                      haptic(8)
                      photoInputRef.current?.click()
                    }}
                    className="glass-pill absolute -right-1 -bottom-1 z-10 flex size-8 items-center justify-center rounded-full text-emerald-600 shadow-md outline-none transition-transform active:scale-90 disabled:opacity-60 dark:text-emerald-400"
                  >
                    {photoBusy || setPhotoMutation.isPending ? (
                      <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
                    ) : (
                      <Camera className="size-3.5" aria-hidden />
                    )}
                  </button>
                ) : null}
              </span>
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
                  {members.length} {members.length === 1 ? (isChannel ? 'subscriber' : 'member') : isChannel ? 'subscribers' : 'members'} · {onlineCount} online
                </p>
              ) : (
                <p className="mt-0.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
                  {other ? (onlineIds.has(other.id) ? 'Online now' : 'Offline') : 'Direct chat'}
                </p>
              )}
              {/* R30-c — channel purpose line (display-only: the info page has no
                  inline-edit pattern; renames live in the classic group manager) */}
              {detail !== undefined && detail.description ? (
                <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-zinc-500 dark:text-zinc-400">
                  {detail.description}
                </p>
              ) : null}
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

          {/* disappearing TTL — Signal-style inline slider (R34-b). The
              collapsed row shows the current state; expanding reveals a
              draggable track over the EXACT stops the disappearing API
              accepts (off / 24h / 7d / 30d), with the old preset pills kept
              as tap-to-commit tick labels. Persists via the existing PATCH
              /disappearing call. */}
          <div className="glass-row-hover rounded-2xl px-3 py-2.5">
            <div className="flex items-center gap-3">
              <Timer
                className={cn(
                  'size-4 shrink-0',
                  (detail?.ttlSeconds ?? 0) > 0 ? 'text-emerald-500' : 'text-zinc-400',
                )}
                aria-hidden
              />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                  Disappearing messages
                </p>
                <p className="truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  {detail === undefined
                    ? '…'
                    : detail.ttlSeconds > 0
                      ? `New messages vanish after ${ttlLong(detail.ttlSeconds)}`
                      : 'Messages stay in the chat'}
                </p>
              </div>
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
              <button
                type="button"
                aria-expanded={ttlOpen}
                aria-label={ttlOpen ? 'Hide timer options' : 'Adjust the disappearing timer'}
                disabled={detail === undefined}
                onClick={() => {
                  haptic(8)
                  setTtlOpen((v) => !v)
                }}
                className="glass-pill flex size-8 shrink-0 items-center justify-center text-zinc-500 outline-none transition-transform active:scale-90 disabled:opacity-50 dark:text-zinc-300"
              >
                <motion.span animate={{ rotate: ttlOpen ? 180 : 0 }} transition={spring.snappy} className="flex">
                  <ChevronDown className="size-4" aria-hidden />
                </motion.span>
              </button>
            </div>
            <AnimatePresence initial={false}>
              {ttlOpen && detail !== undefined ? (
                <motion.div
                  key="ttl-slider"
                  initial={reducedMotion ? false : { opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: 'auto' }}
                  exit={reducedMotion ? undefined : { opacity: 0, height: 0 }}
                  transition={spring.soft}
                  className="overflow-hidden"
                >
                  <DisappearingSlider
                    ttlSeconds={detail.ttlSeconds}
                    disabled={ttlMutation.isPending}
                    reducedMotion={reducedMotion}
                    onCommit={(ttl) => ttlMutation.mutate(ttl)}
                  />
                </motion.div>
              ) : null}
            </AnimatePresence>
          </div>

          {/* R31-a: viewer's chat streak in THIS conversation — R33-b adds the
              honest at-risk tone: a live chain whose lastDay is yesterday dies
              at tonight's UTC midnight unless the viewer sends a message. */}
          <div className="glass-row-hover flex items-center gap-3 rounded-2xl px-3 py-2.5">
            {detail?.deadStreak ? (
              <Hourglass className="size-4 shrink-0 text-amber-500" aria-hidden />
            ) : (
              <Flame
                className={cn(
                  'size-4 shrink-0',
                  detail?.myStreak ? 'text-amber-500' : 'text-zinc-400',
                )}
                aria-hidden
              />
            )}
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                Chat streak
              </p>
              <p
                className={cn(
                  'truncate text-[11px]',
                  detail?.deadStreak
                    ? 'font-semibold text-amber-600 dark:text-amber-400'
                    : 'text-zinc-400 dark:text-zinc-500',
                )}
              >
                {detail === undefined
                  ? '…'
                  : detail.deadStreak
                    ? `${detail.deadStreak.count}-day streak ends tonight — say something`
                    : detail.myStreak
                      ? `${detail.myStreak.count}-day streak · best ${detail.myStreak.best}`
                      : 'No active streak yet'}
              </p>
            </div>
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

          {/* R33-b — leave channel (broadcast rooms only; group leave lives in
              the classic manager). Honest succession: the LAST admin cannot
              leave until they promote a successor via "Make admin" above —
              the row says so, and the server 403s as the backstop. */}
          {isChannel ? (
            <button
              type="button"
              aria-label={leaveArmed ? 'Confirm leaving the channel' : 'Leave this channel'}
              disabled={leaveMutation.isPending}
              onClick={() => {
                haptic(8)
                if (isAdmin && adminCount <= 1) {
                  toast.error(
                    'You are the last admin — promote another admin ("Make admin") before leaving.',
                  )
                  return
                }
                tapConfirm('leave', me.id, () => leaveMutation.mutate())
              }}
              className={cn(
                'glass-row-hover flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none transition-colors',
                leaveArmed && 'bg-rose-500/10',
              )}
            >
              <LogOut className="size-4 shrink-0 text-rose-500" aria-hidden />
              <span className="min-w-0 flex-1">
                <span
                  className={cn(
                    'block truncate text-sm font-medium',
                    leaveArmed ? 'text-rose-600 dark:text-rose-400' : 'text-zinc-800 dark:text-zinc-100',
                  )}
                >
                  {leaveArmed ? 'Tap again to leave' : 'Leave channel'}
                </span>
                <span className="block truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                  {isAdmin && adminCount <= 1
                    ? 'You stay until another admin exists'
                    : isAdmin
                      ? 'Another admin will keep the channel running'
                      : 'You will stop receiving this channel'}
                </span>
              </span>
              {leaveMutation.isPending ? (
                <LoaderCircle className="size-4 shrink-0 animate-spin text-rose-500" aria-hidden />
              ) : null}
            </button>
          ) : null}
        </motion.div>

        {/* R35-a — Encryption section (DMs only): Signal-paradigm safety-number
            verification. One row; tap opens the compare-and-verify sheet. */}
        {!isGroup && peer ? (
          <>
            <motion.p
              {...anim}
              transition={{ ...spring.soft, delay: stagger(3) }}
              className="px-2 pt-4 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500"
            >
              Encryption
            </motion.p>
            <motion.div
              {...anim}
              transition={{ ...spring.soft, delay: stagger(3) }}
              className="glass-deep glass-sheen overflow-hidden rounded-3xl p-1.5"
            >
              <button
                type="button"
                aria-haspopup="dialog"
                onClick={() => {
                  haptic(8)
                  setSafetyOpen(true)
                }}
                className="glass-row-hover flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left outline-none"
              >
                <ShieldCheck
                  className={cn(
                    'size-4 shrink-0',
                    safetyQuery.data?.verified ? 'text-emerald-500' : 'text-zinc-400',
                  )}
                  aria-hidden
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-zinc-800 dark:text-zinc-100">
                    Safety number
                  </span>
                  <span className="block truncate text-[11px] text-zinc-400 dark:text-zinc-500">
                    Tap to compare with your contact
                  </span>
                </span>
                {safetyQuery.isPending && !safetyQuery.data ? (
                  <span className="shrink-0 text-[11px] font-bold text-zinc-400 dark:text-zinc-500">
                    …
                  </span>
                ) : safetyQuery.data?.verified ? (
                  <span className="flex shrink-0 items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold text-emerald-600 dark:text-emerald-400">
                    <BadgeCheck className="size-3" aria-hidden />
                    Verified
                  </span>
                ) : (
                  <span className="shrink-0 rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                    Unverified
                  </span>
                )}
              </button>
            </motion.div>
          </>
        ) : null}

        {/* members */}
        <motion.p
          {...anim}
          transition={{ ...spring.soft, delay: stagger(3) }}
          className="px-2 pt-4 pb-1.5 text-[10px] font-bold tracking-[0.14em] text-zinc-400 uppercase dark:text-zinc-500"
        >
          {isChannel ? 'Subscribers' : 'Members'}{detail ? ` · ${members.length}` : ''}
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

      {/* R35-a — safety-number glass sheet (DMs only, mounts over this page) */}
      <AnimatePresence>
        {safetyOpen && peer ? (
          <SafetyNumberSheet
            key="safety-number-sheet"
            peer={peer}
            meId={me.id}
            reducedMotion={reducedMotion}
            onClose={() => setSafetyOpen(false)}
          />
        ) : null}
      </AnimatePresence>

      {/* R33-b: hidden photo picker — the real upload chain handles the rest */}
      <input
        ref={photoInputRef}
        type="file"
        accept="image/*"
        disabled={photoBusy}
        className="sr-only"
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = '' // allow re-picking the same file
          if (file) void handlePhotoPicked(file)
        }}
      />
    </motion.div>
  )
}

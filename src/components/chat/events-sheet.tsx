// ─────────────────────────────────────────────────────────────
// Pulse — group events & RSVP sheet (Task R23-d "Beyond Chat" 2;
// R30-a adds BAND-style attendance check-in).
// A dark bottom sheet over the chat: schedule events, RSVP
// Going/Maybe/Can't, watch live countdowns, and CHECK IN during
// the event window (start −15 min … start +2 h). Everything is
// REAL — GroupEvent + EventRsvp rows via the events REST API,
// polled every 5s while open (paused when the tab is hidden),
// with an optimistic RSVP/check-in patch so pills snap instantly.
//
// Wiring contract for chat-room (lead): mount once per room —
//   const events = useEventsSheet(conversationId, me.id, members)
//   ... {events.node}
// and the /events slash entry opens it via the `pulse:open-events`
// CustomEvent (exported here — slash-palette may import it).
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AnimatePresence,
  motion,
  useReducedMotion,
  type Variants,
} from 'framer-motion'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BadgeCheck,
  CalendarDays,
  CalendarPlus,
  ChevronDown,
  Clock,
  LoaderCircle,
  MapPin,
  Trash2,
  UserCheck,
  X,
} from 'lucide-react'
import { toast } from 'sonner'
import {
  apiJson,
  gradientFor,
  initialsOf,
} from '@/lib/pulse-utils'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { spring, pressTap } from '@/lib/motion'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'

// ── wire types (mirror of the REST contract) ─────────────────

/** Window event fired by the /events entry — chat-room listens via useEventsSheet(). */
export const EVENTS_OPEN_EVENT = 'pulse:open-events'

export type EventRsvpStatus = 'going' | 'maybe' | 'no'

export interface EventRsvpWire {
  userId: string
  name: string
  status: EventRsvpStatus
  /** Attendance stamp (ISO) — null until the member checks in. */
  checkedInAt: string | null
}

export interface EventCounts {
  going: number
  maybe: number
  no: number
}

export interface GroupEventWire {
  id: string
  title: string
  description: string
  location: string
  startsAt: string
  createdById: string | null
  createdByName: string | null
  rsvps: EventRsvpWire[]
  counts: EventCounts
  myStatus: EventRsvpStatus | null
}

interface EventsGetResponse {
  events: GroupEventWire[]
}

interface EventsPostResponse {
  event: GroupEventWire
}

interface RsvpResponse {
  rsvp: {
    id: string
    eventId: string
    userId: string
    status: EventRsvpStatus
    createdAt: string
  }
  counts: EventCounts
}

interface CheckinResponse {
  checkIn: {
    id: string
    eventId: string
    userId: string
    status: string
    checkedInAt: string | null
    createdAt: string
  } | null
  xpAwarded: boolean
  checkedInCount: number
  alreadyCheckedIn: boolean
}

interface DeleteResponse {
  ok: boolean
}

/** Minimal member shape — chat-room passes its room members straight in. */
export interface EventMember {
  id: string
  name: string
  color?: string
}

type GroupRole = 'admin' | 'member'

// ── constants ────────────────────────────────────────────────

const POLL_MS = 5000
/** countdown chip recompute cadence (spec: 30s interval) */
const TICK_MS = 30_000
/** how long the two-tap delete confirm stays armed */
const DELETE_CONFIRM_MS = 2600
const TITLE_MAX = 120
const LOCATION_MAX = 200

/** Check-in window — mirrors POST /api/events/[id]/checkin exactly. */
const CHECKIN_OPEN_BEFORE_MS = 15 * 60 * 1000
const CHECKIN_CLOSE_AFTER_MS = 2 * 60 * 60 * 1000

const RSVP_CHOICES: ReadonlyArray<{ status: EventRsvpStatus; label: string }> = [
  { status: 'going', label: 'Going' },
  { status: 'maybe', label: 'Maybe' },
  { status: 'no', label: "Can't" },
]

// ── pure helpers ─────────────────────────────────────────────

function tallyOf(rsvps: EventRsvpWire[]): EventCounts {
  const counts: EventCounts = { going: 0, maybe: 0, no: 0 }
  for (const r of rsvps) counts[r.status] += 1
  return counts
}

/** 'in 2h 15m' · 'Starting now' under a minute · future only. */
function countdownLabel(startsAtMs: number, nowMs: number): string {
  const diff = startsAtMs - nowMs
  if (diff < 60_000) return 'Starting now'
  const mins = Math.floor(diff / 60_000)
  if (mins < 60) return `in ${mins}m`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return mins % 60 > 0 ? `in ${hours}h ${mins % 60}m` : `in ${hours}h`
  const days = Math.floor(hours / 24)
  return hours % 24 > 0 ? `in ${days}d ${hours % 24}h` : `in ${days}d`
}

/** '2d ago' relative chip for the collapsed Past section. */
function pastLabel(startsAtMs: number, nowMs: number): string {
  const mins = Math.max(0, Math.floor((nowMs - startsAtMs) / 60_000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}

/** Local `YYYY-MM-DDTHH:mm` for tomorrow 18:00 — datetime-local default. */
function defaultStartLocal(): string {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  d.setHours(18, 0, 0, 0)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const monthFormatter = new Intl.DateTimeFormat('en-US', { month: 'short' })

// ── shared motion variants (stagger recipe from @/lib/motion) ─

const listVariants: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.05 } },
}

const rowVariants: Variants = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: spring.soft },
}

// ── small pieces ─────────────────────────────────────────────

/** Calendar-tile date badge — day number + short month. */
function DateTile({ startsAtMs }: { startsAtMs: number }) {
  const d = new Date(startsAtMs)
  return (
    <div
      aria-hidden
      className="flex w-11 shrink-0 flex-col items-center justify-center rounded-xl border border-white/10 bg-white/[0.06] py-1.5"
    >
      <span className="text-[9px] font-bold uppercase tracking-widest text-emerald-400">
        {monthFormatter.format(d)}
      </span>
      <span className="text-lg font-bold leading-none text-zinc-50">{d.getDate()}</span>
    </div>
  )
}

/** Initials avatar stack of the going members (up to 4 + '+n');
 *  checked-in members wear a subtle emerald verification dot. */
function GoingStack({
  rsvps,
  memberById,
}: {
  rsvps: EventRsvpWire[]
  memberById: Map<string, EventMember>
}) {
  const going = rsvps.filter((r) => r.status === 'going')
  if (going.length === 0) return null
  const here = going.filter((r) => r.checkedInAt).length
  const shown = going.slice(0, 4)
  const extra = going.length - shown.length
  return (
    <div
      className="flex items-center"
      role="img"
      aria-label={here > 0 ? `${going.length} going · ${here} checked in` : `${going.length} going`}
    >
      {shown.map((r, i) => (
        <span
          key={r.userId}
          title={r.checkedInAt ? `${r.name} — checked in` : r.name}
          className={cn(
            'relative flex size-[22px] items-center justify-center rounded-full bg-gradient-to-br text-[9px] font-bold text-white ring-2 ring-zinc-950',
            gradientFor(memberById.get(r.userId)?.color ?? 'emerald'),
          )}
          style={{ marginLeft: i === 0 ? 0 : -6 }}
        >
          {initialsOf(r.name)}
          {r.checkedInAt ? (
            <span
              aria-hidden
              className="absolute right-0 bottom-0 size-2 rounded-full bg-emerald-400 ring-2 ring-zinc-950"
            />
          ) : null}
        </span>
      ))}
      {extra > 0 ? (
        <span
          className="flex size-[22px] items-center justify-center rounded-full bg-zinc-700 text-[9px] font-bold text-zinc-200 ring-2 ring-zinc-950"
          style={{ marginLeft: -6 }}
        >
          +{extra}
        </span>
      ) : null}
    </div>
  )
}

// ── component ────────────────────────────────────────────────

export function EventsSheet({
  open,
  onClose,
  conversationId,
  me,
  members,
  myRole = 'member',
}: {
  open: boolean
  onClose: () => void
  conversationId: string
  me: { id: string }
  members: EventMember[]
  myRole?: GroupRole
}) {
  const reduce = useReducedMotion()
  const queryClient = useQueryClient()

  // form state
  const [title, setTitle] = useState('')
  const [startsAtLocal, setStartsAtLocal] = useState(defaultStartLocal)
  const [location, setLocation] = useState('')
  // two-tap delete confirm (row id currently armed)
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)
  const deleteTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [pastOpen, setPastOpen] = useState(false)

  // live clock for countdown chips — recomputed every 30s while open
  const [nowMs, setNowMs] = useState(() => Date.now())
  useEffect(() => {
    if (!open) return
    // async first tick (fresh clock on open) then steady 30s cadence
    let timer: ReturnType<typeof setTimeout>
    const tick = () => {
      setNowMs(Date.now())
      timer = setTimeout(tick, TICK_MS)
    }
    timer = setTimeout(tick, 0)
    return () => clearTimeout(timer)
  }, [open])

  useEffect(() => {
    return () => {
      if (deleteTimerRef.current) clearTimeout(deleteTimerRef.current)
    }
  }, [])

  const memberById = useMemo(() => new Map(members.map((m) => [m.id, m])), [members])

  // ── data (5s poll, paused when document.hidden) ──────────────

  const eventsQuery = useQuery({
    queryKey: ['events', conversationId],
    enabled: open && conversationId.length > 0 && me.id.length > 0,
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: false, // tab hidden → poll pauses
    staleTime: 0,
    retry: 1,
    queryFn: async (): Promise<EventsGetResponse> =>
      apiJson<EventsGetResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/events?userId=${encodeURIComponent(me.id)}`,
      ),
  })

  const rsvpMutation = useMutation({
    mutationFn: ({ eventId, status }: { eventId: string; status: EventRsvpStatus }) =>
      apiJson<RsvpResponse>(`/api/events/${encodeURIComponent(eventId)}/rsvp`, {
        method: 'POST',
        body: JSON.stringify({ userId: me.id, status }),
      }),
    // optimistic: move my pill + tally immediately, the poll confirms
    onMutate: async ({ eventId, status }) => {
      await queryClient.cancelQueries({ queryKey: ['events', conversationId] })
      const prev = queryClient.getQueryData<EventsGetResponse>(['events', conversationId])
      queryClient.setQueryData<EventsGetResponse>(['events', conversationId], (old) => {
        if (!old) return old
        return {
          events: old.events.map((e) => {
            if (e.id !== eventId) return e
            const others = e.rsvps.filter((r) => r.userId !== me.id)
            const mine = e.rsvps.find((r) => r.userId === me.id)
            const rsvps: EventRsvpWire[] = [
              ...others,
              {
                userId: me.id,
                name: memberById.get(me.id)?.name ?? 'You',
                status,
                // a revote never wipes an existing attendance stamp
                checkedInAt: mine?.checkedInAt ?? null,
              },
            ]
            return { ...e, rsvps, counts: tallyOf(rsvps), myStatus: status }
          }),
        }
      })
      return { prev }
    },
    onError: (error, _vars, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['events', conversationId], ctx.prev)
      toast.error(error instanceof Error ? error.message : 'RSVP failed — try again.')
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['events', conversationId] })
    },
  })

  const createMutation = useMutation({
    mutationFn: (payload: { title: string; startsAt: string; location: string }) =>
      apiJson<EventsPostResponse>(
        `/api/conversations/${encodeURIComponent(conversationId)}/events`,
        {
          method: 'POST',
          body: JSON.stringify({ userId: me.id, ...payload }),
        },
      ),
    onSuccess: (res) => {
      void queryClient.invalidateQueries({ queryKey: ['events', conversationId] })
      setTitle('')
      setLocation('')
      setStartsAtLocal(defaultStartLocal())
      haptic(14)
      toast.success(`“${res.event.title}” is on the calendar.`)
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not schedule the event.')
    },
  })

  const checkinMutation = useMutation({
    mutationFn: ({ eventId }: { eventId: string }) =>
      apiJson<CheckinResponse>(`/api/events/${encodeURIComponent(eventId)}/checkin`, {
        method: 'POST',
        body: JSON.stringify({ userId: me.id }),
      }),
    // optimistic: stamp my row instantly — the roster chip + dot
    // pop on the next render, the 5s poll confirms server truth
    onMutate: async ({ eventId }) => {
      await queryClient.cancelQueries({ queryKey: ['events', conversationId] })
      const prev = queryClient.getQueryData<EventsGetResponse>(['events', conversationId])
      queryClient.setQueryData<EventsGetResponse>(['events', conversationId], (old) => {
        if (!old) return old
        const stamp = new Date().toISOString()
        return {
          events: old.events.map((e) =>
            e.id !== eventId
              ? e
              : {
                  ...e,
                  rsvps: e.rsvps.map((r) =>
                    r.userId === me.id && !r.checkedInAt ? { ...r, checkedInAt: stamp } : r,
                  ),
                },
          ),
        }
      })
      return { prev }
    },
    onError: (error, _vars, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(['events', conversationId], ctx.prev)
      toast.error(error instanceof Error ? error.message : 'Check-in failed — try again.')
    },
    onSuccess: (res) => {
      haptic(16)
      if (res.alreadyCheckedIn) {
        toast.info('Already checked in.')
      } else {
        toast.success(res.xpAwarded ? 'Checked in — see you there · +15 XP' : 'Checked in — see you there')
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['events', conversationId] })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (eventId: string) =>
      apiJson<DeleteResponse>(`/api/events/${encodeURIComponent(eventId)}?userId=${encodeURIComponent(me.id)}`, {
        method: 'DELETE',
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['events', conversationId] })
      haptic(14)
      toast.success('Event deleted.')
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : 'Could not delete the event.')
      void queryClient.invalidateQueries({ queryKey: ['events', conversationId] })
    },
  })

  // ── derived lists (server pre-sorts: upcoming asc, past desc) ─

  const events = eventsQuery.data?.events ?? []
  const upcoming = useMemo(
    () => events.filter((e) => new Date(e.startsAt).getTime() >= nowMs),
    [events, nowMs],
  )
  const past = useMemo(
    () => events.filter((e) => new Date(e.startsAt).getTime() < nowMs),
    [events, nowMs],
  )

  const offline = eventsQuery.isError

  const submitCreate = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const trimmed = title.trim()
    const ms = startsAtLocal.length > 0 ? Date.parse(startsAtLocal) : Number.NaN
    if (trimmed.length === 0 || Number.isNaN(ms)) return
    createMutation.mutate({
      title: trimmed,
      startsAt: new Date(ms).toISOString(),
      location: location.trim(),
    })
  }

  const handleDeleteTap = (eventId: string) => {
    if (confirmDeleteId !== eventId) {
      setConfirmDeleteId(eventId)
      haptic(12)
      if (deleteTimerRef.current) clearTimeout(deleteTimerRef.current)
      deleteTimerRef.current = setTimeout(() => setConfirmDeleteId(null), DELETE_CONFIRM_MS)
      return
    }
    if (deleteTimerRef.current) {
      clearTimeout(deleteTimerRef.current)
      deleteTimerRef.current = null
    }
    setConfirmDeleteId(null)
    deleteMutation.mutate(eventId)
  }

  const statusLine = offline ? 'Reconnecting…' : `${upcoming.length} upcoming · live`

  // ── row renderer (shared by upcoming + past) ─────────────────

  const renderRow = (e: GroupEventWire, kind: 'upcoming' | 'past') => {
    const startsAtMs = new Date(e.startsAt).getTime()
    const canDelete = me.id === e.createdById || myRole === 'admin'
    const creatorName = (e.createdById ? memberById.get(e.createdById)?.name : undefined) ?? e.createdByName
    // ── attendance (R30-a): window, eligibility, roster count ──
    const myCheckedInAt = e.rsvps.find((r) => r.userId === me.id)?.checkedInAt ?? null
    const windowOpen =
      startsAtMs - CHECKIN_OPEN_BEFORE_MS <= nowMs && nowMs <= startsAtMs + CHECKIN_CLOSE_AFTER_MS
    const canCheckIn = windowOpen && e.myStatus === 'going' && !myCheckedInAt
    const showCheckinHint =
      !windowOpen && !myCheckedInAt && e.myStatus === 'going' && nowMs < startsAtMs - CHECKIN_OPEN_BEFORE_MS
    const hereCount = e.rsvps.reduce((n, r) => (r.checkedInAt ? n + 1 : n), 0)
    return (
      <motion.div
        key={e.id}
        layout={!reduce}
        variants={rowVariants}
        initial={reduce ? false : 'hidden'}
        animate="show"
        exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.96, y: 6 }}
        transition={spring.soft}
        style={{ willChange: 'transform' }}
        className="rounded-2xl border border-white/10 bg-white/[0.04] p-3.5"
      >
        <div className="flex gap-3">
          <DateTile startsAtMs={startsAtMs} />
          <div className="min-w-0 flex-1">
            <div className="flex items-start justify-between gap-2">
              <p className="min-w-0 truncate text-[14px] font-bold leading-tight text-zinc-50">
                {e.title}
              </p>
              {canDelete ? (
                <motion.button
                  type="button"
                  whileTap={reduce ? undefined : pressTap}
                  disabled={deleteMutation.isPending}
                  aria-label={
                    confirmDeleteId === e.id
                      ? `Tap again to delete event ${e.title}`
                      : `Delete event ${e.title}`
                  }
                  onClick={() => handleDeleteTap(e.id)}
                  className={cn(
                    'flex size-7 shrink-0 items-center justify-center rounded-full outline-none ring-emerald-400/60 transition-colors focus-visible:ring-2 disabled:opacity-50',
                    confirmDeleteId === e.id
                      ? 'bg-rose-500 text-white'
                      : 'text-zinc-500 hover:bg-rose-500/10 hover:text-rose-400',
                  )}
                >
                  <Trash2 className="size-3.5" aria-hidden />
                </motion.button>
              ) : null}
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-0.5">
              <span
                className={cn(
                  'rounded-full px-2 py-0.5 text-[10.5px] font-bold tabular-nums',
                  kind === 'upcoming'
                    ? startsAtMs - nowMs < 60_000
                      ? 'bg-emerald-500 text-white'
                      : 'bg-emerald-500/15 text-emerald-400'
                    : 'bg-white/5 text-zinc-500',
                )}
              >
                {kind === 'upcoming' ? countdownLabel(startsAtMs, nowMs) : pastLabel(startsAtMs, nowMs)}
              </span>
              {e.location ? (
                <span className="flex min-w-0 items-center gap-1 text-[11px] font-medium text-zinc-400">
                  <MapPin className="size-3 shrink-0 text-zinc-500" aria-hidden />
                  <span className="truncate">{e.location}</span>
                </span>
              ) : null}
            </div>
            {creatorName ? (
              <p className="mt-1 text-[11px] font-medium text-zinc-500">by {creatorName}</p>
            ) : null}
          </div>
        </div>

        <div className="mt-2.5 flex items-center justify-between gap-2">
          <div role="group" aria-label={`RSVP for ${e.title}`} className="flex gap-1.5">
            {RSVP_CHOICES.map(({ status, label }) => {
              const mine = e.myStatus === status
              return (
                <motion.button
                  key={status}
                  type="button"
                  whileTap={reduce ? undefined : pressTap}
                  aria-pressed={mine}
                  aria-label={`RSVP ${status} ${e.title}`}
                  disabled={rsvpMutation.isPending}
                  onClick={() => {
                    if (e.myStatus !== status) {
                      haptic(8)
                      rsvpMutation.mutate({ eventId: e.id, status })
                    }
                  }}
                  className={cn(
                    'flex h-8 items-center gap-1 rounded-full px-2.5 text-[11px] font-bold outline-none ring-emerald-400/60 transition-colors duration-150 focus-visible:ring-2 disabled:opacity-60',
                    mine
                      ? 'bg-emerald-500 text-white shadow-lg shadow-emerald-600/25'
                      : 'bg-white/5 text-zinc-300 hover:bg-white/10',
                  )}
                >
                  {label}
                  <span className={cn('tabular-nums', mine ? 'text-white/90' : 'text-zinc-500')}>
                    {e.counts[status]}
                  </span>
                </motion.button>
              )
            })}
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {hereCount > 0 ? (
              <span
                aria-label={`${hereCount} checked in here`}
                className="flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10.5px] font-bold text-emerald-400"
              >
                <UserCheck className="size-3" aria-hidden />
                <span className="tabular-nums">{hereCount}</span> here
              </span>
            ) : null}
            <GoingStack rsvps={e.rsvps} memberById={memberById} />
          </div>
        </div>

        {/* attendance — prominent glass check-in while the window is open,
            spring-pop "Checked in" chip after, muted pre-window hint */}
        {canCheckIn ? (
          <motion.button
            type="button"
            whileTap={reduce ? undefined : pressTap}
            disabled={checkinMutation.isPending}
            aria-label={`Check in to ${e.title}`}
            onClick={() => {
              haptic(10)
              checkinMutation.mutate({ eventId: e.id })
            }}
            initial={reduce ? false : { opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={spring.soft}
            style={{ willChange: 'transform' }}
            className="relative mt-2.5 flex h-10 w-full items-center justify-center gap-1.5 overflow-hidden rounded-xl border border-emerald-400/30 bg-emerald-500/15 text-[13px] font-bold text-emerald-300 outline-none ring-emerald-400/60 backdrop-blur-md transition-colors duration-150 hover:bg-emerald-500/25 focus-visible:ring-2 disabled:opacity-60"
          >
            {!reduce ? (
              <motion.span
                aria-hidden
                className="absolute inset-0 rounded-xl border border-emerald-400/50"
                animate={{ opacity: [0.5, 0], scale: [1, 1.06] }}
                transition={{ duration: 1.8, repeat: Infinity, ease: 'easeOut' }}
              />
            ) : null}
            {checkinMutation.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <UserCheck className="size-4" aria-hidden />
            )}
            Check in
          </motion.button>
        ) : myCheckedInAt ? (
          <motion.div
            initial={reduce ? false : { opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={spring.bouncy}
            style={{ willChange: 'transform' }}
            role="status"
            aria-label={`Checked in to ${e.title}`}
            className="mt-2.5 flex h-10 w-full items-center justify-center gap-1.5 rounded-xl border border-emerald-400/25 bg-emerald-500/10 text-[12.5px] font-bold text-emerald-300"
          >
            <BadgeCheck className="size-4" aria-hidden />
            Checked in
          </motion.div>
        ) : showCheckinHint ? (
          <p className="mt-2.5 flex items-center gap-1.5 px-1 text-[11px] font-medium text-zinc-500">
            <Clock className="size-3 shrink-0" aria-hidden />
            Check-in opens 15 min before start
          </p>
        ) : null}
      </motion.div>
    )
  }

  return (
    <Drawer
      open={open}
      handleOnly
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent
        aria-label="Events"
        className="mx-auto h-[92dvh] max-h-[92dvh] max-w-[420px] rounded-t-3xl border-white/10 bg-zinc-950 px-0 pb-0 [&>div:first-child]:bg-white/20 dark:border-white/10 dark:bg-zinc-950"
      >
        <DrawerTitle className="sr-only">Events</DrawerTitle>
        <DrawerDescription className="sr-only">
          Schedule group events and RSVP with everyone in this chat
        </DrawerDescription>

        <motion.div
          initial={reduce ? false : { opacity: 0, y: 28, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={spring.soft}
          style={{ willChange: 'transform' }}
          className="flex min-h-0 flex-1 flex-col"
        >
          {/* header */}
          <header className="flex shrink-0 items-center gap-2.5 border-b border-white/10 px-4 pb-3 pt-1">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-2xl bg-emerald-500/15 text-emerald-400">
              <CalendarDays className="size-4" aria-hidden />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-bold leading-tight text-zinc-50">Events</p>
              <p className="flex items-center gap-1.5 text-[11px] font-medium leading-tight text-zinc-500">
                <span
                  aria-hidden
                  className={cn(
                    'size-1.5 rounded-full',
                    offline ? 'bg-rose-500' : 'animate-pulse bg-emerald-400',
                  )}
                />
                {statusLine}
              </p>
            </div>
            <button
              type="button"
              aria-label="Close events"
              onClick={() => {
                haptic(10)
                onClose()
              }}
              className="flex size-9 shrink-0 items-center justify-center rounded-full text-zinc-400 outline-none ring-emerald-400/60 transition-colors hover:bg-white/10 hover:text-zinc-100 focus-visible:ring-2 active:scale-90"
            >
              <X className="size-4.5" aria-hidden />
            </button>
          </header>

          {/* scrollable body */}
          <div
            className="pulse-scroll min-h-0 flex-1 space-y-4 overflow-y-auto px-4 pt-4"
            style={{ paddingBottom: 'max(1rem, env(safe-area-inset-bottom))' }}
          >
            {/* create form */}
            <motion.form
              initial={reduce ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={spring.soft}
              onSubmit={submitCreate}
              className="space-y-2 rounded-2xl border border-white/10 bg-white/[0.04] p-3"
            >
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                maxLength={TITLE_MAX}
                required
                aria-label="Event title"
                placeholder="Event title"
                className="h-10 w-full rounded-xl border border-white/10 bg-white/5 px-3 text-sm text-zinc-100 placeholder:text-zinc-500 outline-none transition-colors focus:border-emerald-400"
              />
              <div className="grid grid-cols-2 gap-2">
                <input
                  type="datetime-local"
                  value={startsAtLocal}
                  onChange={(e) => setStartsAtLocal(e.target.value)}
                  required
                  aria-label="Starts at"
                  className="h-10 w-full rounded-xl border border-white/10 bg-white/5 px-2 font-mono text-[12.5px] text-zinc-100 outline-none transition-colors [color-scheme:dark] focus:border-emerald-400"
                />
                <input
                  type="text"
                  value={location}
                  onChange={(e) => setLocation(e.target.value)}
                  maxLength={LOCATION_MAX}
                  aria-label="Location (optional)"
                  placeholder="Location (optional)"
                  className="h-10 w-full rounded-xl border border-white/10 bg-white/5 px-3 text-sm text-zinc-100 placeholder:text-zinc-500 outline-none transition-colors focus:border-emerald-400"
                />
              </div>
              <button
                type="submit"
                disabled={createMutation.isPending || title.trim().length === 0}
                className="flex h-10 w-full items-center justify-center gap-1.5 rounded-xl bg-emerald-500 text-[13px] font-bold text-white shadow-lg shadow-emerald-600/25 outline-none ring-emerald-300/60 transition-all duration-150 hover:bg-emerald-500/90 focus-visible:ring-2 active:scale-[0.98] disabled:opacity-50"
              >
                {createMutation.isPending ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden />
                ) : (
                  <CalendarPlus className="size-4" aria-hidden />
                )}
                Schedule event
              </button>
            </motion.form>

            {/* upcoming */}
            <section aria-label="Upcoming events">
              <p className="px-1 pb-2 text-[10px] font-bold uppercase tracking-widest text-zinc-500">
                Upcoming
              </p>
              {!eventsQuery.isLoading && events.length === 0 ? (
                <div className="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-white/10 py-8 text-center">
                  <span className="flex size-11 items-center justify-center rounded-2xl bg-white/5 text-zinc-500">
                    <CalendarDays className="size-5" aria-hidden />
                  </span>
                  <p className="max-w-[240px] text-[12.5px] font-medium leading-relaxed text-zinc-500">
                    No events yet — schedule the first one above.
                  </p>
                </div>
              ) : (
                <motion.div
                  variants={reduce ? undefined : listVariants}
                  initial={reduce ? false : 'hidden'}
                  animate="show"
                  className="space-y-2"
                >
                  <AnimatePresence initial={false}>
                    {upcoming.map((e) => (
                      <div key={e.id}>{renderRow(e, 'upcoming')}</div>
                    ))}
                  </AnimatePresence>
                  {upcoming.length === 0 && events.length > 0 ? (
                    <p className="py-3 text-center text-[12px] font-medium text-zinc-500">
                      Nothing upcoming — past events live below.
                    </p>
                  ) : null}
                </motion.div>
              )}
              {eventsQuery.isLoading ? (
                <div
                  role="status"
                  aria-label="Loading events"
                  className="flex items-center justify-center gap-2 py-6"
                >
                  <LoaderCircle className="size-5 animate-spin text-emerald-400" aria-hidden />
                  <span className="text-[12.5px] font-medium text-zinc-500">Loading events…</span>
                </div>
              ) : null}
            </section>

            {/* past (collapsed) */}
            <AnimatePresence initial={false}>
              {past.length > 0 ? (
                <motion.section
                  key="past"
                  aria-label="Past events"
                  initial={reduce ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0 }}
                  transition={spring.soft}
                >
                  <button
                    type="button"
                    aria-expanded={pastOpen}
                    onClick={() => {
                      haptic(6)
                      setPastOpen((v) => !v)
                    }}
                    className="flex w-full items-center gap-1.5 px-1 pb-2 text-[10px] font-bold uppercase tracking-widest text-zinc-500 outline-none ring-emerald-400/60 transition-colors hover:text-zinc-300 focus-visible:ring-2"
                  >
                    <ChevronDown
                      aria-hidden
                      className={cn(
                        'size-3.5 transition-transform duration-200',
                        pastOpen && 'rotate-180',
                      )}
                    />
                    Past ({past.length})
                  </button>
                  <AnimatePresence initial={false}>
                    {pastOpen ? (
                      <motion.div
                        key="past-list"
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: 'auto', opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        transition={spring.soft}
                        className="overflow-hidden"
                      >
                        <div className="space-y-2 pb-1">
                          {past.map((e) => (
                            <div key={e.id}>{renderRow(e, 'past')}</div>
                          ))}
                        </div>
                      </motion.div>
                    ) : null}
                  </AnimatePresence>
                </motion.section>
              ) : null}
            </AnimatePresence>
          </div>
        </motion.div>
      </DrawerContent>
    </Drawer>
  )
}

/**
 * Drop-in wiring for chat-room (lead, 2 lines):
 *   const events = useEventsSheet(conversationId, me.id, members, myRole?)
 *   ... {events.node}
 * Listens for the `pulse:open-events` CustomEvent dispatched by
 * the /events slash-palette entry.
 */
export function useEventsSheet(
  conversationId: string,
  meId: string,
  members: EventMember[],
  myRole: GroupRole = 'member',
) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handler = () => setOpen(true)
    window.addEventListener(EVENTS_OPEN_EVENT, handler)
    return () => window.removeEventListener(EVENTS_OPEN_EVENT, handler)
  }, [])

  const node = (
    <EventsSheet
      open={open}
      onClose={() => setOpen(false)}
      conversationId={conversationId}
      me={{ id: meId }}
      members={members}
      myRole={myRole}
    />
  )

  return { open, setOpen, node }
}

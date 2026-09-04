// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/events — group calendar + RSVP
// (Task R23-d "Beyond Chat" wave 2 — Pulse group events)
//
// Every event is a REAL persisted GroupEvent row; RSVPs are real
// EventRsvp rows upserted on @@unique(eventId, userId). No mocks.
//
// Contracts:
//   GET  ?userId=<member id>
//        → { events: [{ id, title, description, location, startsAt,
//              createdById, createdByName,
//              rsvps: [{ userId, name, status }],
//              counts: { going, maybe, no },
//              myStatus: 'going'|'maybe'|'no'|null }] }
//        Upcoming (startsAt >= now) ascending first, then past
//        descending, capped at 50 rows total. Non-member → 403.
//   POST body { userId, title (1..120), startsAt (ISO, parseable),
//               description?, location? }
//        → 201 { event } (rsvps: [] — creator does not auto-RSVP)
//
// All handlers verify conversation participation — no anonymous
// access, everything lands in Prisma/SQLite.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

// ── validation bounds (client mirrors them on the form) ──────
const TITLE_MAX = 120
const DESCRIPTION_MAX = 1000
const LOCATION_MAX = 200
const LIST_CAP = 50

const RSVP_STATUSES = ['going', 'maybe', 'no'] as const
type RsvpStatus = (typeof RSVP_STATUSES)[number]

function asStatus(raw: string): RsvpStatus {
  return (RSVP_STATUSES as readonly string[]).includes(raw) ? (raw as RsvpStatus) : 'going'
}

/** 403/404 response when the user is not a conversation member, else null. */
async function participantGuard(
  conversationId: string,
  userId: string,
): Promise<NextResponse | null> {
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } },
      select: { id: true },
    }),
  ])
  if (!conv) return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }
  return null
}

/** Tally { going, maybe, no } from raw status strings. */
function countsOf(statuses: string[]): { going: number; maybe: number; no: number } {
  const counts = { going: 0, maybe: 0, no: 0 }
  for (const status of statuses) {
    if (status === 'going') counts.going += 1
    else if (status === 'maybe') counts.maybe += 1
    else if (status === 'no') counts.no += 1
  }
  return counts
}

type EventRow = {
  id: string
  title: string
  description: string
  location: string
  startsAt: Date
  createdById: string | null
  rsvps: Array<{ userId: string; status: string }>
}

/** Shape a DB row for the wire against a userId→name map. */
function serializeEvent(
  row: EventRow,
  names: Map<string, string>,
  meId: string | null,
): {
  id: string
  title: string
  description: string
  location: string
  startsAt: string
  createdById: string | null
  createdByName: string | null
  rsvps: Array<{ userId: string; name: string; status: RsvpStatus }>
  counts: { going: number; maybe: number; no: number }
  myStatus: RsvpStatus | null
} {
  const rsvps = row.rsvps.map((r) => ({
    userId: r.userId,
    name: names.get(r.userId) ?? 'Former member',
    status: asStatus(r.status),
  }))
  return {
    id: row.id,
    title: row.title,
    description: row.description,
    location: row.location,
    startsAt: row.startsAt.toISOString(),
    createdById: row.createdById,
    createdByName: row.createdById ? (names.get(row.createdById) ?? null) : null,
    rsvps,
    counts: countsOf(row.rsvps.map((r) => r.status)),
    myStatus: meId ? (rsvps.find((r) => r.userId === meId)?.status ?? null) : null,
  }
}

/** Resolve display names for rsvp users + creators in one query. */
async function nameMapOf(userIds: Array<string | null>): Promise<Map<string, string>> {
  const ids = [
    ...new Set(userIds.filter((id): id is string => typeof id === 'string' && id.length > 0)),
  ]
  if (ids.length === 0) return new Map()
  const rows = await db.user.findMany({ where: { id: { in: ids } }, select: { id: true, name: true } })
  return new Map(rows.map((u) => [u.id, u.name]))
}

/**
 * GET /api/conversations/[id]/events?userId=
 * Upcoming events ascending first, then past descending, cap 50.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, userId)
  if (guard) return guard

  const now = new Date()
  const [upcoming, past] = await Promise.all([
    db.groupEvent.findMany({
      where: { conversationId: id, startsAt: { gte: now } },
      orderBy: [{ startsAt: 'asc' }, { id: 'asc' }],
      take: LIST_CAP,
      include: {
        rsvps: {
          orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
          select: { userId: true, status: true },
        },
      },
    }),
    db.groupEvent.findMany({
      where: { conversationId: id, startsAt: { lt: now } },
      orderBy: [{ startsAt: 'desc' }, { id: 'desc' }],
      take: LIST_CAP,
      include: {
        rsvps: {
          orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
          select: { userId: true, status: true },
        },
      },
    }),
  ])

  const merged = [...upcoming, ...past].slice(0, LIST_CAP)
  const names = await nameMapOf([
    ...merged.map((e) => e.createdById),
    ...merged.flatMap((e) => e.rsvps.map((r) => r.userId)),
  ])

  return NextResponse.json(
    { events: merged.map((e) => serializeEvent(e, names, userId)) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

/**
 * POST /api/conversations/[id]/events  body { userId, title, startsAt, description?, location? }
 * → 201 { event } — brand-new event, zero RSVPs.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, userId)
  if (guard) return guard

  const title = strField(body.title)
  if (title.length < 1 || title.length > TITLE_MAX) {
    return NextResponse.json({ error: `title must be 1-${TITLE_MAX} characters.` }, { status: 400 })
  }

  const startsAtRaw = typeof body.startsAt === 'string' ? body.startsAt.trim() : ''
  if (startsAtRaw.length === 0) {
    return NextResponse.json({ error: 'startsAt is required.' }, { status: 400 })
  }
  const startsAtMs = Date.parse(startsAtRaw)
  if (Number.isNaN(startsAtMs)) {
    return NextResponse.json(
      { error: 'startsAt must be a parseable ISO date string.' },
      { status: 400 },
    )
  }

  const description = strField(body.description).slice(0, DESCRIPTION_MAX)
  const location = strField(body.location).slice(0, LOCATION_MAX)

  const created = await db.groupEvent.create({
    data: {
      conversationId: id,
      title,
      description,
      location,
      startsAt: new Date(startsAtMs),
      createdById: userId,
    },
    include: {
      rsvps: {
        orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
        select: { userId: true, status: true },
      },
    },
  })

  const names = await nameMapOf([created.createdById])
  return NextResponse.json({ event: serializeEvent(created, names, userId) }, { status: 201 })
}

// ─────────────────────────────────────────────────────────────
// /api/events/[id]/rsvp — cast or move an event RSVP (Task R23-d)
//
//   POST body { userId, status: 'going' | 'maybe' | 'no' }
//   → 200 { rsvp: { id, eventId, userId, status, createdAt },
//           counts: { going, maybe, no } }
//
// Upsert on @@unique(eventId, userId): a revote MOVES the existing
// RSVP row instead of failing. The caller must be a participant of
// the conversation hosting the event — 403 otherwise.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const RSVP_STATUSES = ['going', 'maybe', 'no'] as const
type RsvpStatus = (typeof RSVP_STATUSES)[number]

/**
 * POST /api/events/[id]/rsvp  body { userId, status }
 * → 200 { rsvp, counts } — the fresh tally after the upsert.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  const status = strField(body.status)
  if (!userId || !status) {
    return NextResponse.json({ error: 'userId and status are required.' }, { status: 400 })
  }
  if (!(RSVP_STATUSES as readonly string[]).includes(status)) {
    return NextResponse.json(
      { error: "status must be one of 'going', 'maybe' or 'no'." },
      { status: 400 },
    )
  }

  const event = await db.groupEvent.findUnique({
    where: { id },
    select: { id: true, conversationId: true },
  })
  if (!event) {
    return NextResponse.json({ error: 'Event not found.' }, { status: 404 })
  }

  const membership = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: event.conversationId } },
    select: { id: true },
  })
  if (!membership) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  // revote = move the RSVP (upsert on the compound unique key)
  const rsvp = await db.eventRsvp.upsert({
    where: { eventId_userId: { eventId: id, userId } },
    update: { status: status as RsvpStatus },
    create: { eventId: id, userId, status: status as RsvpStatus },
  })

  const rows = await db.eventRsvp.findMany({ where: { eventId: id }, select: { status: true } })
  const counts = { going: 0, maybe: 0, no: 0 }
  for (const row of rows) {
    if (row.status === 'going') counts.going += 1
    else if (row.status === 'maybe') counts.maybe += 1
    else if (row.status === 'no') counts.no += 1
  }

  return NextResponse.json({
    rsvp: {
      id: rsvp.id,
      eventId: rsvp.eventId,
      userId: rsvp.userId,
      status: rsvp.status,
      createdAt: rsvp.createdAt.toISOString(),
    },
    counts,
  })
}

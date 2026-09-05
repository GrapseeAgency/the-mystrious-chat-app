// ─────────────────────────────────────────────────────────────
// /api/events/[id]/checkin — BAND-style event attendance (R30-a)
//
//   POST body { userId }
//   → 200 { checkIn: { id, eventId, userId, status, checkedInAt,
//           createdAt }, xpAwarded, checkedInCount, alreadyCheckedIn }
//
// Viewer resolution follows the events convention (userId in the
// JSON body — see /api/events/[id]/rsvp). Behavior:
//   · no RSVP row, or status 'no'          → 400 (clear error)
//   · outside the window (start −15 min …
//     start +2 h)                          → 409
//   · already stamped                      → 200 idempotent
//     (existing row + alreadyCheckedIn: true, no XP re-award)
//   · null → set transition                → stamps checkedInAt
//     and awards +15 XP ONCE, mirroring the User.xp increment
//     convention of /api/conversations/[id]/messages (channel
//     points). WalletLedger is PC/GEM-only money trail — XP is
//     not a wallet asset, so NO ledger row is written here.
//
// The stamp uses updateMany guarded on checkedInAt: null so two
// concurrent taps can never double-award XP; the loser of that
// race reads back the winner's stamp and answers idempotently.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Window: opens 15 minutes before startsAt, closes 2 hours after. */
const OPEN_BEFORE_MS = 15 * 60 * 1000
const CLOSE_AFTER_MS = 2 * 60 * 60 * 1000
/** Attendance bonus — matches the sheet's '+15 XP' feedback. */
const CHECKIN_XP = 15

/** Wire shape for an EventRsvp row (ISO strings, per types contract). */
function serializeRsvp(row: {
  id: string
  eventId: string
  userId: string
  status: string
  checkedInAt: Date | null
  createdAt: Date
}) {
  return {
    id: row.id,
    eventId: row.eventId,
    userId: row.userId,
    status: row.status,
    checkedInAt: row.checkedInAt ? row.checkedInAt.toISOString() : null,
    createdAt: row.createdAt.toISOString(),
  }
}

/** How many members are physically here for this event. */
function checkedInCountOf(eventId: string): Promise<number> {
  return db.eventRsvp.count({ where: { eventId, checkedInAt: { not: null } } })
}

/**
 * POST /api/events/[id]/checkin  body { userId }
 * → 200 { checkIn, xpAwarded, checkedInCount, alreadyCheckedIn } | 400 | 404 | 409
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const event = await db.groupEvent.findUnique({
    where: { id },
    select: { id: true, startsAt: true },
  })
  if (!event) {
    return NextResponse.json({ error: 'Event not found.' }, { status: 404 })
  }

  const rsvp = await db.eventRsvp.findUnique({
    where: { eventId_userId: { eventId: id, userId } },
  })
  if (!rsvp) {
    return NextResponse.json(
      { error: 'You need to RSVP before you can check in.' },
      { status: 400 },
    )
  }
  if (rsvp.status === 'no') {
    return NextResponse.json(
      { error: "You RSVP'd 'Can't' — update your RSVP to check in." },
      { status: 400 },
    )
  }

  // Idempotent: an existing stamp always wins, even outside the window.
  if (rsvp.checkedInAt) {
    return NextResponse.json({
      checkIn: serializeRsvp(rsvp),
      xpAwarded: false,
      checkedInCount: await checkedInCountOf(id),
      alreadyCheckedIn: true,
    })
  }

  const nowMs = Date.now()
  const startMs = event.startsAt.getTime()
  if (nowMs < startMs - OPEN_BEFORE_MS || nowMs > startMs + CLOSE_AFTER_MS) {
    return NextResponse.json(
      { error: 'Check-in is open from 15 minutes before start until 2 hours after.' },
      { status: 409 },
    )
  }

  // Atomic stamp — only lands while checkedInAt is still null, so a
  // double-tap race awards XP exactly once (count === 1 means we won).
  const now = new Date()
  const stamped = await db.eventRsvp.updateMany({
    where: { eventId: id, userId, checkedInAt: null },
    data: { checkedInAt: now },
  })

  let xpAwarded = false
  if (stamped.count === 1) {
    try {
      await db.user.update({ where: { id: userId }, data: { xp: { increment: CHECKIN_XP } } })
      xpAwarded = true
    } catch {
      xpAwarded = false // XP is a bonus — the attendance stamp still stands
    }
  }

  const row = await db.eventRsvp.findUnique({
    where: { eventId_userId: { eventId: id, userId } },
  })

  return NextResponse.json({
    checkIn: row ? serializeRsvp(row) : null,
    xpAwarded,
    checkedInCount: await checkedInCountOf(id),
    alreadyCheckedIn: stamped.count === 0, // lost a stamp race — the winner stamped first
  })
}

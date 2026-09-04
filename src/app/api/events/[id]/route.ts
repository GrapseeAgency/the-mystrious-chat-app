// ─────────────────────────────────────────────────────────────
// /api/events/[id] — delete a group event (Task R23-d)
//
//   DELETE ?userId=<member id>
//   → { ok: true } when the caller is the event creator OR a
//     group admin of the hosting conversation; 403 otherwise.
//     EventRsvp rows cascade-delete with the event (schema-level
//     onDelete: Cascade) — no orphans.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * DELETE /api/events/[id]?userId=
 * Creator or group admin only — plain members (even participants)
 * get a 403 with an actionable message.
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const userId = strField(new URL(req.url).searchParams.get('userId'))
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const event = await db.groupEvent.findUnique({
    where: { id },
    select: { id: true, title: true, conversationId: true, createdById: true },
  })
  if (!event) {
    return NextResponse.json({ error: 'Event not found.' }, { status: 404 })
  }

  const membership = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: event.conversationId } },
    select: { role: true },
  })
  if (!membership) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const isCreator = event.createdById === userId
  const isAdmin = membership.role === 'admin'
  if (!isCreator && !isAdmin) {
    return NextResponse.json(
      { error: 'Only the event creator or a group admin can delete this event.' },
      { status: 403 },
    )
  }

  await db.groupEvent.delete({ where: { id } }) // RSVPs cascade with it

  return NextResponse.json({ ok: true })
}

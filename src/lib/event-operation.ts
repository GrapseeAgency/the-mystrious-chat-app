import type { PrismaClient } from '../../prisma/generated-client'

export class EventOperationError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export type EventAction = { kind: 'rsvp'; status: string } | { kind: 'checkin' } | { kind: 'delete' }
const STATUSES = ['going', 'maybe', 'no']

/** Authorization, attendance, XP and returned snapshot share one commit. A departed
 * member's old RSVP is not access; a failed XP write must not strand attendance.
 * The caller binds a presented credential to userId before entering this helper. */
export async function mutateEvent(db: PrismaClient, eventId: string, userId: string, action: EventAction,
  clock: () => Date = () => new Date()) {
  if (action.kind === 'rsvp' && !STATUSES.includes(action.status)) {
    throw new EventOperationError(400, "status must be one of 'going', 'maybe' or 'no'.")
  }
  try {
    return await db.$transaction(async tx => {
      const event = await tx.groupEvent.findUnique({ where: { id: eventId } })
      if (!event) throw new EventOperationError(404, 'Event not found.')
      const member = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId, conversationId: event.conversationId } }, select: { role: true },
      })
      if (!member) throw new EventOperationError(403, 'You are not a participant of this conversation.')
      const identity = { eventReceiptVersion: 1 as const, eventId, userId }
      if (action.kind === 'delete') {
        if (event.createdById !== userId && member.role !== 'admin') {
          throw new EventOperationError(403, 'Only the event creator or a group admin can delete this event.')
        }
        await tx.groupEvent.delete({ where: { id: eventId } })
        return { ...identity, ok: true as const }
      }
      const key = { eventId_userId: { eventId, userId } }
      if (action.kind === 'rsvp') {
        const rsvp = await tx.eventRsvp.upsert({ where: key, update: { status: action.status },
          create: { eventId, userId, status: action.status } })
        const rows = await tx.eventRsvp.findMany({ where: { eventId }, select: { status: true } })
        const counts = { going: 0, maybe: 0, no: 0 }
        for (const row of rows) {
          if (row.status === 'going' || row.status === 'maybe' || row.status === 'no') counts[row.status]++
        }
        return { ...identity, rsvp, counts }
      }
      let row = await tx.eventRsvp.findUnique({ where: key })
      if (!row) throw new EventOperationError(400, 'You need to RSVP before you can check in.')
      // An accepted stamp is historical attendance, even if a subsequent RSVP
      // changed. Never erase it or award XP twice on a replay outside the window.
      const alreadyCheckedIn = row.checkedInAt !== null
      if (!alreadyCheckedIn) {
        if (row.status !== 'going' && row.status !== 'maybe') {
          throw new EventOperationError(400, "Update your RSVP to Going or Maybe before checking in.")
        }
        const now = clock(), start = event.startsAt.getTime()
        if (now.getTime() < start - 15 * 60_000 || now.getTime() > start + 2 * 60 * 60_000) {
          throw new EventOperationError(409, 'Check-in is open from 15 minutes before start until 2 hours after.')
        }
        const stamped = await tx.eventRsvp.updateMany({ where: { eventId, userId, checkedInAt: null,
          status: { in: ['going', 'maybe'] } }, data: { checkedInAt: now } })
        if (stamped.count !== 1) throw new EventOperationError(409, 'Attendance changed. Refresh and try again.')
        await tx.user.update({ where: { id: userId }, data: { xp: { increment: 15 } }, select: { id: true } })
        row = await tx.eventRsvp.findUnique({ where: key })
      }
      if (!row?.checkedInAt) throw new EventOperationError(409, 'Attendance could not be confirmed.')
      const checkedInCount = await tx.eventRsvp.count({ where: { eventId, checkedInAt: { not: null } } })
      return { ...identity, checkIn: row, xpAwarded: !alreadyCheckedIn, checkedInCount, alreadyCheckedIn }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
      throw new EventOperationError(409, 'This event is busy or changed. Refresh and retry the same action.')
    }
    throw error
  }
}

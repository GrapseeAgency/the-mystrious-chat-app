import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { messageDeliveryDecision } from './scheduled-delivery'

export class ScheduledError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export const scheduledHeaders = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }
export function scheduledErrorResponse(error: unknown): Response {
  if (error instanceof ScheduledError) return Response.json({ error: error.message }, { status: error.status, headers: scheduledHeaders })
  if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
    return Response.json({ error: 'Scheduled messages changed or are busy. Refresh before retrying.' }, { status: 409, headers: scheduledHeaders })
  }
  throw error
}
const scope = (userId: string, conversationId: string) => ({ scheduleVersion: 1, userId, conversationId })
const fields = { id: true, conversationId: true, content: true, scheduledAt: true, sentAt: true, cancelledAt: true, cancelledReason: true } as const
function wire(row: { id: string; conversationId: string; content: string; scheduledAt: Date; sentAt: Date | null; cancelledAt: Date | null; cancelledReason: string | null }) {
  return { ...row, scheduledAt: row.scheduledAt.toISOString(), sentAt: row.sentAt?.toISOString() ?? null,
    cancelledAt: row.cancelledAt?.toISOString() ?? null }
}
async function member(tx: Prisma.TransactionClient, userId: string, conversationId: string) {
  if (!await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId, conversationId } }, select: { id: true } })) {
    throw new ScheduledError(403, 'You are not a participant of this conversation.')
  }
}
function decodeCursor(raw: string | null): { at: Date; id: string } | null {
  if (raw === null) return null
  try {
    if (!raw || raw.length > 1024) throw new Error()
    const value = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'))
    if (typeof value.at !== 'string' || typeof value.id !== 'string' || !value.id || value.id.length > 256) throw new Error()
    const at = new Date(value.at)
    if (!Number.isFinite(at.getTime()) || at.toISOString() !== value.at) throw new Error()
    return { at, id: value.id }
  } catch { throw new ScheduledError(400, 'Invalid scheduled-message cursor.') }
}
/** Seek by time+ID, not row offsets/cursor existence: cancelling an earlier page
 * must not skip the next row. Each page checks current membership in its snapshot. */
export async function scheduledPage(db: PrismaClient, userId: string, conversationId: string, cursor: string | null) {
  const after = decodeCursor(cursor)
  return db.$transaction(async tx => {
    await member(tx, userId, conversationId)
    const rows = await tx.scheduledMessage.findMany({ where: { senderId: userId, conversationId, sentAt: null,
      ...(after ? { OR: [{ scheduledAt: { gt: after.at } }, { scheduledAt: after.at, id: { gt: after.id } }] } : {}),
    }, orderBy: [{ scheduledAt: 'asc' }, { id: 'asc' }], take: 51, select: fields })
    const page = rows.slice(0, 50), hasMore = rows.length > 50, last = page.at(-1)
    return { ...scope(userId, conversationId), items: page.map(wire), hasMore,
      nextCursor: hasMore && last ? Buffer.from(JSON.stringify({ at: last.scheduledAt.toISOString(), id: last.id })).toString('base64url') : null }
  }, { isolationLevel: 'Serializable' })
}
export async function createScheduled(db: PrismaClient, userId: string, conversationId: string, content: string, when: Date) {
  return db.$transaction(async tx => {
    const now = new Date()
    if (when.getTime() < now.getTime() + 30_000 || when.getTime() > now.getTime() + 30 * 86400_000) {
      throw new ScheduledError(400, 'scheduledAt must be between 30 seconds and 30 days from now.')
    }
    const decision = await messageDeliveryDecision(tx, conversationId, userId, now)
    if (decision.state === 'cancel') throw new ScheduledError(403, `Cannot schedule this message: ${decision.reason}.`)
    const row = await tx.scheduledMessage.create({ data: { conversationId, senderId: userId, content, scheduledAt: when }, select: fields })
    return { ...scope(userId, conversationId), item: wire(row) }
  }, { isolationLevel: 'Serializable' })
}
/** Dispatch's sentAt write and cancellation compete inside Serializable transactions.
 * Never delete a row based on a stale sentAt read outside the write transaction. */
export async function cancelScheduled(db: PrismaClient, userId: string, scheduledId: string) {
  return db.$transaction(async tx => {
    const row = await tx.scheduledMessage.findUnique({ where: { id: scheduledId }, select: { senderId: true, conversationId: true, sentAt: true } })
    if (!row) throw new ScheduledError(404, 'Scheduled message not found. Refresh to see its current state.')
    if (row.senderId !== userId) throw new ScheduledError(403, 'Only the sender can cancel this message.')
    if (row.sentAt !== null) throw new ScheduledError(409, 'This message was already sent. It was not cancelled.')
    // Owners can cancel their own pending sends even after leaving the room.
    const deleted = await tx.scheduledMessage.deleteMany({ where: { id: scheduledId, senderId: userId, sentAt: null } })
    if (deleted.count !== 1) throw new ScheduledError(409, 'The scheduled message changed. Refresh before retrying.')
    return { ...scope(userId, row.conversationId), scheduledId, cancelled: true, ok: true }
  }, { isolationLevel: 'Serializable' })
}

/** Cursor progresses over waiting/failed candidates too, so one slow-mode room
 * cannot monopolize every dispatch tick. The ticker retains it until a scan ends. */
export async function scheduledDispatchPage(db: PrismaClient, cursor: string | null) {
  const after = decodeCursor(cursor)
  const rows = await db.scheduledMessage.findMany({ where: { sentAt: null, cancelledAt: null, scheduledAt: { lte: new Date() },
    ...(after ? { OR: [{ scheduledAt: { gt: after.at } }, { scheduledAt: after.at, id: { gt: after.id } }] } : {}),
  }, orderBy: [{ scheduledAt: 'asc' }, { id: 'asc' }], take: 26, select: { id: true, scheduledAt: true } })
  const page = rows.slice(0, 25), last = page.at(-1)
  return { rows: page, nextCursor: rows.length > 25 && last
    ? Buffer.from(JSON.stringify({ at: last.scheduledAt.toISOString(), id: last.id })).toString('base64url') : null }
}

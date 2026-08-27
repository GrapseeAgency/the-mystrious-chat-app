// ─────────────────────────────────────────────────────────────
// /api/maintenance/dispatch — flush DUE scheduled messages.
// Called on an interval by the socket mini-service (no DB access
// over there), and safe to call manually. Replays each due row
// through the exact same pipeline as a live send: real message
// row, list bump, archive-unlock, socket relay.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapMessage, memberIdsOf, notifySocket, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Shared-secret gate (matches the interval caller). */
function authorized(req: Request): boolean {
  const expected = process.env.CRON_SECRET ?? 'pulse-dispatch-key'
  return req.headers.get('x-pulse-key') === expected
}

/**
 * POST /api/maintenance/dispatch → { dispatched: number }
 * Takes up to 25 due rows (oldest first). Failures skip that row
 * (retried next tick) without blocking the rest.
 */
export async function POST(req: Request) {
  if (!authorized(req)) {
    return NextResponse.json({ error: 'Unauthorized.' }, { status: 401 })
  }

  const due = await db.scheduledMessage.findMany({
    where: { sentAt: null, scheduledAt: { lte: new Date() } },
    orderBy: { scheduledAt: 'asc' },
    take: 25,
    select: { id: true },
  })
  if (due.length === 0) {
    return NextResponse.json({ dispatched: 0 })
  }

  let dispatched = 0
  for (const { id } of due) {
    try {
      const result = await db.$transaction(async (tx) => {
        const row = await tx.scheduledMessage.findUnique({ where: { id } })
        if (!row || row.sentAt !== null || row.scheduledAt > new Date()) return null

        const created = await tx.message.create({
          data: { conversationId: row.conversationId, senderId: row.senderId, content: row.content },
          include: MESSAGE_FULL_INCLUDE,
        })
        const now = new Date()
        await tx.scheduledMessage.update({ where: { id }, data: { sentAt: now } })
        await tx.conversation.update({
          where: { id: row.conversationId },
          data: { updatedAt: now },
        })
        await tx.conversationParticipant.update({
          where: {
            userId_conversationId: { userId: row.senderId, conversationId: row.conversationId },
          },
          data: { lastReadAt: now },
        })
        await tx.conversationParticipant.updateMany({
          where: { conversationId: row.conversationId, userId: { not: row.senderId }, archivedAt: { not: null } },
          data: { archivedAt: null },
        })
        return created
      })
      if (!result) continue

      const recipients = (await memberIdsOf(result.conversationId)).filter(
        (memberId) => memberId !== result.senderId,
      )
      const mapped = mapMessage(result)
      await notifySocket('message:new', recipients, {
        type: 'message:new',
        message: mapped,
        recipientIds: recipients,
        conversationId: result.conversationId,
      })
      dispatched += 1
    } catch (error) {
      console.error('[dispatch] failed for', id, error instanceof Error ? error.message : error)
    }
  }

  return NextResponse.json({ dispatched })
}

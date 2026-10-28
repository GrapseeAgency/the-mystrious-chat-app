// /api/maintenance/dispatch - flush DUE scheduled messages.
// Called on an interval by the socket mini-service (no DB access
// over there), and safe to call manually. Delivers due rows using current
// send policy, list/unarchive updates, socket relay and the same
// privacy-planned push as live messages.
import { NextResponse } from 'next/server'
import { deliverMessageNotification } from '@/lib/message-notification'
import { fanoutPush } from '@/lib/push/transport'
import { db } from '@/lib/db'
import { scheduledDeliveryDecision } from '@/lib/scheduled-delivery'
import { scheduledDispatchPage, scheduledErrorResponse } from '@/lib/scheduled-operation'
import { enqueueScheduledNotice, drainScheduledNotices, scheduledPushDelivered, recordScheduledPush } from '@/lib/scheduled-notice'
import { mapMessage, memberIdsOf, notifySocket, MESSAGE_FULL_INCLUDE } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Shared-secret gate (matches the interval caller). */
function authorized(req: Request): boolean {
  // Fail-closed: no default secret. CRON_SECRET must be set in the environment;
  // an unset/empty secret refuses every request (audit finding S5).
  const expected = process.env.CRON_SECRET
  if (!expected) return false
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

  let page: Awaited<ReturnType<typeof scheduledDispatchPage>>
  try { page = await scheduledDispatchPage(db, new URL(req.url).searchParams.get('cursor')) }
  catch (error) { return scheduledErrorResponse(error) }
  const due = page.rows

  let dispatched = 0
  for (const { id } of due) {
    try {
      const result = await db.$transaction(async (tx) => {
        const row = await tx.scheduledMessage.findUnique({ where: { id } })
        if (!row || row.sentAt !== null || row.cancelledAt !== null || row.scheduledAt > new Date()) return null

        const now = new Date()
        const decision = await scheduledDeliveryDecision(tx, row.conversationId, row.senderId, now)
        if (decision.state === 'wait') return null // remain queued until slow mode allows it
        if (decision.state === 'cancel') {
          await tx.scheduledMessage.update({ where: { id }, data: { cancelledAt: now, cancelledReason: decision.reason } })
          return null
        }

        const created = await tx.message.create({
          data: { conversationId: row.conversationId, senderId: row.senderId, content: row.content,
            createdAt: now, expiresAt: decision.expiresAt },
          include: MESSAGE_FULL_INCLUDE,
        })
        await tx.scheduledMessage.update({ where: { id }, data: { sentAt: now, content: '' } })
        await tx.conversation.update({
          where: { id: row.conversationId },
          data: { updatedAt: now },
        })
        // A scheduled send must not mark unseen incoming messages as read.
        await tx.conversationParticipant.updateMany({
          where: { conversationId: row.conversationId, userId: { not: row.senderId }, archivedAt: { not: null } },
          data: { archivedAt: null },
        })
        await enqueueScheduledNotice(tx,created.id,now.getTime())
        return created
      }, { isolationLevel: 'Serializable' })
      if (!result) continue
      // Count durable message commits, not best-effort transport successes.
      dispatched += 1
    } catch (error) {
      console.error('[dispatch] failed for', id, error instanceof Error ? error.message : error)
    }
  }

  await drainScheduledNotices(db, async id => {
    const message = await db.message.findUnique({ where: { id }, include: MESSAGE_FULL_INCLUDE })
    if (!message || message.deletedAt || (message.expiresAt && message.expiresAt <= new Date())) return
    const recipients = await memberIdsOf(message.conversationId)
    await notifySocket('message:new',recipients,{ type: 'message:new',message: mapMessage(message),
      recipientIds: recipients,conversationId: message.conversationId },true)
  }, async (id,cursor) => {
    let next: string | null = null
    await deliverMessageNotification(db,async (recipients,payload,options) => {
      const report = await fanoutPush(recipients,payload,{ ...options,limit: 25,afterTokenId: cursor,
        alreadyDelivered: binding => scheduledPushDelivered(db,id,binding),
        recordDelivered: binding => recordScheduledPush(db,id,binding) })
      if (report?.failed) throw new Error('Scheduled push delivery remains pending')
      next = report?.nextCursor ?? null
      return report
    },id)
    return next
  })

  return NextResponse.json({ dispatched, nextCursor: page.nextCursor })
}

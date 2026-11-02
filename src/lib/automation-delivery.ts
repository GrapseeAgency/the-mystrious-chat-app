import type { Prisma } from '../../prisma/generated-client'
import { MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
import type { ChatMessage } from './types'
import { messageDeliveryDecision } from './scheduled-delivery'
import { threadRootAvailable } from './message-visibility'
import { AUTOMATION_SCAN_MAX } from './integration-contracts.ts'

/** Literal, Unicode-aware word boundaries (including combining marks and
 * join controls). An ASCII-only boundary fires Bengali triggers inside words. */
export function triggerMatches(trigger: string, content: string): boolean {
  const text = trigger.trim().normalize('NFC')
  if (!text) return false
  const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const word = '[\\p{L}\\p{N}\\p{M}\\p{Pc}\\u200c\\u200d]'
  return new RegExp(`(?<!${word})${escaped}(?!${word})`, 'iu').test(content.normalize('NFC'))
}

/** Caller must use one Serializable transaction. No socket/network work here:
 * source visibility, current rule/author policy, message, execution receipt
 * and notification outbox row commit together (Section 12 items 3/4). */
export async function createAutomationReply(
  tx: Prisma.TransactionClient, conversationId: string, sourceId: string, expectedContent: string, now = new Date(),
) {
  const source = await tx.message.findFirst({ where: {
    id: sourceId, conversationId, deletedAt: null, viaAutomation: false,
    OR: [{ expiresAt: null }, { expiresAt: { gt: now } }],
  }, select: { senderId: true, content: true, parentId: true, topicId: true } })
  if (!source || source.content !== expectedContent) return null
  const sourceMember = await tx.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: source.senderId, conversationId } }, select: { id: true },
  })
  if (!sourceMember) return null
  const root = source.parentId ? await tx.message.findUnique({ where: { id: source.parentId },
    select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } }) : null
  if (source.parentId && !threadRootAvailable(root, conversationId, now)) return null

  // Large-rule scans are bounded: a room can hold at most
  // AUTOMATIONS_PER_ROOM_MAX rules (enforced on create) and the evaluation
  // never walks more than AUTOMATION_SCAN_MAX rows even if legacy data
  // somehow exceeds the cap.
  const rules = await tx.automation.findMany({ where: { conversationId, enabled: true },
    orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    take: AUTOMATION_SCAN_MAX,
    select: { id: true, trigger: true, reply: true, createdById: true },
  })
  const rule = rules.find(row => triggerMatches(row.trigger, source.content))
  if (!rule || !rule.reply.trim() || rule.reply.length > 500) return null

  // Item 3: durable exactly-once execution. The (automationId, sourceId)
  // receipt is checked before any write; the UNIQUE constraint is the
  // cross-instance backstop behind this Serializable pre-check.
  const prior = await tx.automationExecution.findUnique({
    where: { automationId_sourceId: { automationId: rule.id, sourceId } },
    select: { id: true },
  })
  if (prior) return null

  const decision = await messageDeliveryDecision(tx, conversationId, rule.createdById, now)
  if (decision.state !== 'send') return null
  let expiresAt = decision.expiresAt
  if (root?.expiresAt && (!expiresAt || root.expiresAt < expiresAt)) expiresAt = root.expiresAt
  const message = await tx.message.create({ data: {
    conversationId, senderId: rule.createdById, content: rule.reply, kind: 'text', viaAutomation: true,
    parentId: source.parentId, topicId: source.topicId, createdAt: now, expiresAt,
  }, include: MESSAGE_FULL_INCLUDE })
  await tx.automation.update({ where: { id: rule.id }, data: { hits: { increment: 1 }, lastFiredAt: now } })
  await tx.automationExecution.create({ data: {
    conversationId, automationId: rule.id, sourceId, messageId: message.id, createdAt: now,
  } })
  // Item 4: the notification debt is written in the SAME commit as the
  // execution - a crash between commit and relay still owes the room the
  // message:new event, and the dispatch ticker settles it.
  await tx.automationNotifyOutbox.create({ data: { conversationId, messageId: message.id, createdAt: now } })
  await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
  await tx.conversationParticipant.updateMany({
    where: { conversationId, userId: { not: rule.createdById }, archivedAt: { not: null } },
    data: { archivedAt: null },
  })
  return message
}

// ── item 4: notification outbox drain ───────────────────────────────

type OutboxDb = Omit<Prisma.PrismaClient, '$transaction' | '$connect' | '$disconnect' | '$on' | '$transactionMeta' | '$extends'>

/**
 * The inline fast path calls this after a successful socket relay so the
 * ticker never re-notifies. Idempotent.
 */
export async function markAutomationNotifyDelivered(db: OutboxDb, messageId: string, now = new Date()): Promise<void> {
  await db.automationNotifyOutbox.updateMany({
    where: { messageId, state: 'pending' },
    data: { state: 'delivered', deliveredAt: now },
  })
}

/**
 * Settle pending notification debts. `notify` receives the current message
 * shape and must throw to leave the row pending (attempted again next tick).
 * A source that was deleted or expired before delivery is settled as
 * delivered - the room no longer owes an event for a message nobody can see.
 * Returns the number of rows settled. Bounded sweep: delivered rows older
 * than seven days are pruned.
 */
export async function drainAutomationNotifyOutbox(
  db: OutboxDb,
  notify: (message: ChatMessage) => Promise<void>,
): Promise<number> {
  return drainAutomationNotify(db, notify, Date.now)
}

/** Testable core with an injectable clock. */
export async function drainAutomationNotify(
  db: OutboxDb,
  notify: (message: ChatMessage) => Promise<void>,
  clock: () => number = Date.now,
): Promise<number> {
  const now = new Date(clock())
  const pending = await db.automationNotifyOutbox.findMany({
    where: { state: 'pending' },
    orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    take: 25,
    select: { id: true, messageId: true },
  })
  let settled = 0
  for (const row of pending) {
    const message = await db.message.findUnique({ where: { id: row.messageId }, include: MESSAGE_FULL_INCLUDE })
    const deliverable = message && message.deletedAt === null && (!message.expiresAt || message.expiresAt > now)
    await db.automationNotifyOutbox.update({
      where: { id: row.id },
      data: { attempts: { increment: 1 } },
    })
    if (!deliverable) {
      await db.automationNotifyOutbox.update({
        where: { id: row.id },
        data: { state: 'delivered', deliveredAt: now },
      })
      settled += 1
      continue
    }
    try {
      await notify(message)
      await db.automationNotifyOutbox.update({
        where: { id: row.id },
        data: { state: 'delivered', deliveredAt: now },
      })
      settled += 1
    } catch {
      // Stays pending; attempts keeps climbing; next tick retries oldest-first.
    }
  }
  const cutoff = new Date(now.getTime() - 7 * 86_400_000)
  await db.automationNotifyOutbox.deleteMany({
    where: { state: 'delivered', deliveredAt: { lt: cutoff } },
  })
  return settled
}

import type { Prisma } from '../../prisma/generated-client'
import { MESSAGE_FULL_INCLUDE } from '@/lib/serializers'
import { messageDeliveryDecision } from './scheduled-delivery'
import { threadRootAvailable } from './message-visibility'

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
 * source visibility, current rule/author policy, message and hit count commit
 * together. This is best-effort execution, not durable exactly-once delivery. */
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

  const rules = await tx.automation.findMany({ where: { conversationId, enabled: true },
    orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    select: { id: true, trigger: true, reply: true, createdById: true },
  })
  const rule = rules.find(row => triggerMatches(row.trigger, source.content))
  if (!rule || !rule.reply.trim() || rule.reply.length > 500) return null
  const decision = await messageDeliveryDecision(tx, conversationId, rule.createdById, now)
  if (decision.state !== 'send') return null
  let expiresAt = decision.expiresAt
  if (root?.expiresAt && (!expiresAt || root.expiresAt < expiresAt)) expiresAt = root.expiresAt
  const message = await tx.message.create({ data: {
    conversationId, senderId: rule.createdById, content: rule.reply, kind: 'text', viaAutomation: true,
    parentId: source.parentId, topicId: source.topicId, createdAt: now, expiresAt,
  }, include: MESSAGE_FULL_INCLUDE })
  await tx.automation.update({ where: { id: rule.id }, data: { hits: { increment: 1 }, lastFiredAt: now } })
  await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now } })
  await tx.conversationParticipant.updateMany({
    where: { conversationId, userId: { not: rule.createdById }, archivedAt: { not: null } },
    data: { archivedAt: null },
  })
  return message
}

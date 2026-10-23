import { createHash } from 'node:crypto'
import type { Prisma } from '../../prisma/generated-client'
import { MESSAGE_FULL_INCLUDE, MESSAGE_MAX } from '@/lib/serializers'
import { messageDeliveryDecision } from '@/lib/scheduled-delivery'
import { threadRootAvailable } from '@/lib/message-visibility'

export type BotTrigger = { id: string; senderId: string; content: string }
export type BotReply = { content: string; payload?: string; poll?: { question: string; options: string[] } }

/** Recheck after external work as well as before it; source and bot authority
 * belong to this transaction, not to an earlier HTTP handler snapshot. */
export async function botReplyContext(tx: Prisma.TransactionClient, room: string, botId: string, trigger: BotTrigger, now = new Date()) {
  const source = await tx.message.findFirst({ where: {
    id: trigger.id, conversationId: room, senderId: trigger.senderId, deletedAt: null,
    viaAutomation: false, viewOnce: false, OR: [{ expiresAt: null }, { expiresAt: { gt: now } }],
  } })
  if (!source || source.senderId === botId || source.content !== trigger.content) return null
  const member = await tx.conversationParticipant.findUnique({ where: {
    userId_conversationId: { userId: source.senderId, conversationId: room },
  }, select: { id: true } })
  if (!member) return null
  const root = source.parentId ? await tx.message.findUnique({ where: { id: source.parentId },
    select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true, viewOnce: true } }) : null
  if (source.parentId && (!threadRootAvailable(root, room, now) || root?.viewOnce)) return null
  const decision = await messageDeliveryDecision(tx, room, botId, now, true)
  if (decision.state !== 'send') return null
  let expiresAt = decision.expiresAt
  for (const deadline of [source.expiresAt, root?.expiresAt]) {
    if (deadline && (!expiresAt || deadline < expiresAt)) expiresAt = deadline
  }
  return { source, expiresAt }
}

/** Source-bound command/AI message, poll, timestamp and archive update are one
 * atomic write. No read marker is advanced and no notification occurs here. */
export async function createBotReply(tx: Prisma.TransactionClient, room: string, botId: string, trigger: BotTrigger, data: BotReply, now = new Date()) {
  const context = await botReplyContext(tx, room, botId, trigger, now)
  if (!context || (!data.poll && !data.content.trim())) return null
  const message = await tx.message.create({ data: {
    conversationId: room, senderId: botId, content: data.content.slice(0, MESSAGE_MAX),
    kind: data.poll ? 'poll' : 'text', viaAutomation: true, payload: data.payload ?? JSON.stringify({ bot: true }),
    parentId: context.source.parentId, topicId: context.source.topicId,
    createdAt: now, expiresAt: context.expiresAt,
    ...(data.poll ? { poll: { create: { question: data.poll.question, options: {
      createMany: { data: data.poll.options.map((text, position) => ({ text, position })) },
    } } } } : {}),
  }, include: MESSAGE_FULL_INCLUDE })
  await tx.conversation.update({ where: { id: room }, data: { updatedAt: now } })
  await tx.conversationParticipant.updateMany({ where: {
    conversationId: room, userId: { not: botId }, archivedAt: { not: null },
  }, data: { archivedAt: null } })
  return message
}

/** A bounded, scoped transcript. Its fingerprint is compared again before an
 * LLM answer is committed, preventing deleted/expired/edited context from being
 * reintroduced by a late completion. Already-transmitted prompts cannot be recalled. */
export async function prepareAiContext(tx: Prisma.TransactionClient, room: string, botId: string, trigger: BotTrigger, now = new Date()) {
  const context = await botReplyContext(tx, room, botId, trigger, now)
  if (!context) return null
  const conversation = await tx.conversation.findUnique({ where: { id: room }, select: { isGroup: true } })
  if (!conversation) return null
  const source = context.source
  const recent = await tx.message.findMany({ where: {
    conversationId: room, deletedAt: null, viewOnce: false,
    AND: [
      { OR: [{ expiresAt: null }, { expiresAt: { gt: now } }] },
      source.parentId ? { OR: [{ id: source.parentId }, { parentId: source.parentId }] } : { parentId: null },
      { OR: [{ createdAt: { lt: source.createdAt } }, { createdAt: source.createdAt, id: { lte: source.id } }] },
    ],
  }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: 16,
    select: { id: true, content: true, imagePath: true, createdAt: true, editedAt: true, expiresAt: true,
      anon: true, anonAlias: true, sender: { select: { name: true } } },
  })
  const roster = await tx.conversationParticipant.findMany({ where: { conversationId: room },
    orderBy: { userId: 'asc' }, select: { userId: true, joinedAt: true, role: true } })
  const rows = recent.reverse().map(row => ({ ...row,
    sender: { name: row.anon ? row.anonAlias || 'Anonymous' : row.sender.name },
  }))
  const transcript = rows.map(row => `${row.sender.name}: ${row.content.trim() ? row.content.slice(0, 500) : row.imagePath ? '[sent a photo]' : '[attachment]'}`).join('\n')
  return { isGroup: conversation.isGroup, transcript,
    fingerprint: createHash('sha256').update(JSON.stringify({ rows, roster, conversation })).digest('hex') }
}

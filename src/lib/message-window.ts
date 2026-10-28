import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { inboxMessagesWhere, threadRootAvailable, visibleInboxPreview } from './message-visibility.ts'

export class MessageWindowError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
type Row = { id: string; conversationId: string; createdAt: Date; deletedAt: Date | null; expiresAt: Date | null; parentId: string | null;
  parent?: { conversationId: string; parentId: string | null; deletedAt: Date | null; expiresAt: Date | null } | null }
export type WindowPosition = { id: string; createdAt: string }
export function windowBoundary(position: WindowPosition, direction: 'before' | 'after'): Prisma.MessageWhereInput {
  if (!position.id || position.id.length > 256 || !Number.isFinite(Date.parse(position.createdAt))) {
    throw new MessageWindowError(400, 'Invalid history position.')
  }
  const at = new Date(position.createdAt)
  return direction === 'before'
    ? { OR: [{ createdAt: { lt: at } }, { createdAt: at, id: { lt: position.id } }] }
    : { OR: [{ createdAt: { gt: at } }, { createdAt: at, id: { gt: position.id } }] }
}

/** Indexed exact lookup, not a scan from the newest page. Authorization, target,
 * parent lifetime and both sides of the window share one database snapshot.
 * Tuple positions remain usable after the boundary message is deleted. */
export async function readMessageWindow<M extends Row>(db: PrismaClient, room: string, viewer: string,
  options: { anchorId?: string; threadId?: string; before?: WindowPosition; after?: WindowPosition; limit?: number },
  read: (tx: Prisma.TransactionClient, where: Prisma.MessageWhereInput, order: 'asc' | 'desc', take: number) => Promise<M[]>,
  clock: () => Date = () => new Date()) {
  const limit = options.limit ?? 60
  if (!Number.isInteger(limit) || limit < 2 || limit > 200 ||
      [options.anchorId, options.before, options.after].filter(Boolean).length > 1) {
    throw new MessageWindowError(400, 'Choose one anchor/direction and a limit between 2 and 200.')
  }
  return db.$transaction(async tx => {
    if (!await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: viewer, conversationId: room } }, select: { id: true } })) {
      throw new MessageWindowError(403, 'You are not a participant of this conversation.')
    }
    const now = clock()
    const anchor = options.anchorId ? (await read(tx, { id: options.anchorId, conversationId: room }, 'asc', 1))[0] : null
    if (options.anchorId && !anchor) throw new MessageWindowError(404, 'Message not found in this conversation.')
    if (anchor && (anchor.deletedAt || !visibleInboxPreview(anchor, room, now))) throw new MessageWindowError(410, 'Message is no longer available.')
    const threadId = options.threadId ?? anchor?.parentId ?? null
    if (options.threadId && anchor && anchor.parentId !== options.threadId) throw new MessageWindowError(400, 'Message is not in this thread.')
    const parent = threadId ? (await read(tx, { id: threadId, conversationId: room }, 'asc', 1))[0] : null
    if (threadId && !threadRootAvailable(parent ?? null, room, now)) throw new MessageWindowError(410, 'Thread is no longer available.')
    const scope: Prisma.MessageWhereInput = { AND: [inboxMessagesWhere(room, now), { parentId: threadId }] }
    let messages: M[]
    if (anchor) {
      const position = { id: anchor.id, createdAt: anchor.createdAt.toISOString() }
      const older = await read(tx, { AND: [scope, windowBoundary(position, 'before')] }, 'desc', Math.floor((limit - 1) / 2))
      const newer = await read(tx, { AND: [scope, windowBoundary(position, 'after')] }, 'asc', limit - 1 - older.length)
      messages = [...older.reverse(), anchor, ...newer]
    } else {
      const boundary = options.before ? windowBoundary(options.before, 'before') : options.after ? windowBoundary(options.after, 'after') : {}
      messages = await read(tx, { AND: [scope, boundary] }, options.after ? 'asc' : 'desc', limit)
      if (!options.after) messages.reverse()
    }
    const position = (row: M): WindowPosition => ({ id: row.id, createdAt: row.createdAt.toISOString() })
    const first = messages[0], last = messages.at(-1)
    const older = first ? position(first) : options.before ?? null
    const newer = last ? position(last) : options.after ?? null
    const hasOlder = older ? !!await tx.message.findFirst({ where: { AND: [scope, windowBoundary(older, 'before')] }, select: { id: true } }) : false
    const hasNewer = newer ? !!await tx.message.findFirst({ where: { AND: [scope, windowBoundary(newer, 'after')] }, select: { id: true } }) : false
    const delivered = clock()
    if (parent && !threadRootAvailable(parent, room, delivered)) throw new MessageWindowError(410, 'Thread expired during lookup.')
    if (anchor && !visibleInboxPreview(anchor, room, delivered)) throw new MessageWindowError(410, 'Message expired during lookup.')
    return { messageWindowVersion: 1 as const, conversationId: room, anchorId: anchor?.id ?? null, threadId,
      parent, messages: messages.flatMap(row => { const live = visibleInboxPreview(row, room, delivered); return live ? [live] : [] }),
      hasOlder, hasNewer, olderCursor: older, newerCursor: newer }
  }, { isolationLevel: 'Serializable' })
}

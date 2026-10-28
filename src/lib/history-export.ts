import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { inboxMessagesWhere, visibleInboxPreview } from './message-visibility.ts'
import { decodeMessageSyncCursor, encodeMessageSyncCursor, messageSyncWhere, type MessageSyncCursor } from './message-sync.ts'
import { MessageWindowError } from './message-window.ts'

type Row = { id: string; createdAt: Date; conversationId: string; parentId: string | null; expiresAt: Date | null;
  parent?: { conversationId: string; parentId: string | null; deletedAt: Date | null; expiresAt: Date | null } | null }
const START = '0001-01-01T00:00:00.000Z'
/** Bounded reads for a disk-streamed transcript. Every page reauthorizes; no
 * long-running database transaction is held while a phone writes/shares a file. */
export async function readExportPage<M extends Row>(db: PrismaClient, room: string, owner: string, raw: string | null,
  read: (tx: Prisma.TransactionClient, where: Prisma.MessageWhereInput, take: number) => Promise<M[]>, clock: () => Date = () => new Date()) {
  return db.$transaction(async tx => {
    if (!await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: owner, conversationId: room } }, select: { id: true } })) {
      throw new MessageWindowError(403, 'You are not a participant of this conversation.')
    }
    const available = inboxMessagesWhere(room, clock())
    let cursor: MessageSyncCursor
    if (raw !== null) {
      try {
        cursor = decodeMessageSyncCursor(raw, room, null)
        if (cursor.from !== START || !cursor.after) throw Error('Invalid cursor')
      } catch { throw new MessageWindowError(400, 'Invalid export cursor.') }
    } else {
      const last = await tx.message.findFirst({ where: available, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], select: { id: true, createdAt: true } })
      if (!last) return { exportVersion: 1, syncVersion: 1, messages: [], hasMore: false, total: 0, nextSyncCursor: null, exportPosition: null }
      cursor = { v: 1, room, topic: null, from: START, upper: { id: last.id, at: last.createdAt.toISOString() } }
    }
    const rawRows = await read(tx, { AND: [available, messageSyncWhere(cursor)] }, 201)
    const rows = rawRows.slice(0, 200), tail = rows.at(-1), hasMore = rawRows.length > 200
    const at = clock()
    const messages = rows.flatMap(row => { const live = visibleInboxPreview(row, room, at); return live ? [live] : [] })
    return { exportVersion: 1, syncVersion: 1, messages, hasMore, total: messages.length,
      // Position advances by scanned rows, including rows expiring in transit.
      exportPosition: tail ? { id: tail.id, createdAt: tail.createdAt.toISOString() } : null,
      nextSyncCursor: hasMore && tail ? encodeMessageSyncCursor({ ...cursor, after: { id: tail.id, at: tail.createdAt.toISOString() } }) : null }
  }, { isolationLevel: 'Serializable' })
}

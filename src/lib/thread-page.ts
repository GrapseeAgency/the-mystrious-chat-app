import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { readThreadSnapshot, ThreadReadError } from './message-visibility.ts'
import { decodeMessageSyncCursor, encodeMessageSyncCursor, messageSyncWhere, type MessageSyncCursor } from './message-sync.ts'

type Root = { id: string; conversationId: string; parentId: string | null; deletedAt: Date | null; expiresAt: Date | null }
type Reply = { id: string; createdAt: Date; expiresAt: Date | null }
type Cursor = MessageSyncCursor & { root: string }

/** Bounded pages, reauthorized on every request. The first page fixes an upper
 * tuple so ordinary later arrivals cannot keep a reader chasing the tail. This
 * is not one long-lived DB snapshot: edits/expiry may change between pages. */
export async function readThreadPage<R extends Root, M extends Reply>(
  db: PrismaClient, rootId: string, userId: string, limit: number, rawCursor: string | null,
  readRoot: (tx: Prisma.TransactionClient, id: string) => Promise<R | null>,
  readReplies: (tx: Prisma.TransactionClient, where: Prisma.MessageWhereInput, take: number) => Promise<M[]>,
  clock: () => Date = () => new Date(),
): Promise<{ parent: R; replies: M[]; hasMore: boolean; nextCursor: string | null; threadPageVersion: 1 }> {
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) throw new ThreadReadError(400, 'Thread page limit must be 1..200.')
  let root: R | null = null
  let nextCursor: string | null = null
  let hasMore = false
  const page = await readThreadSnapshot(db, rootId, userId,
    async (tx, id) => { root = await readRoot(tx, id); return root },
    async (tx, available) => {
      let cursor: Cursor
      if (rawCursor !== null) {
        try {
          cursor = decodeMessageSyncCursor(rawCursor, root!.conversationId, null) as Cursor
          if (cursor.root !== rootId || cursor.from !== '0001-01-01T00:00:00.000Z' || !cursor.after) throw Error('Invalid cursor')
          const after = new Date(cursor.after.at).getTime(), upper = new Date(cursor.upper.at).getTime()
          if (after > upper || (after === upper && cursor.after.id > cursor.upper.id)) throw Error('Invalid cursor')
        } catch { throw new ThreadReadError(400, 'Invalid thread cursor.') }
      } else {
        const last = await tx.message.findFirst({ where: available, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
          select: { createdAt: true, id: true } })
        if (!last) return []
        cursor = { v: 1, root: rootId, room: root!.conversationId, topic: null, from: '0001-01-01T00:00:00.000Z',
          upper: { at: last.createdAt.toISOString(), id: last.id } }
      }
      const rows = await readReplies(tx, { AND: [available, messageSyncWhere(cursor)] }, limit + 1)
      const batch = rows.slice(0, limit)
      hasMore = rows.length > limit
      // Advance by raw positions, not the expiry-filtered wire rows. An entire
      // page may expire during delivery without losing the remaining history.
      const tail = batch.at(-1)
      if (hasMore && tail) nextCursor = encodeMessageSyncCursor({ ...cursor, after: { at: tail.createdAt.toISOString(), id: tail.id } })
      return batch
    }, clock)
  return { ...page, hasMore, nextCursor, threadPageVersion: 1 }
}

import { readTupleSide, readTupleInterval } from './tuple-page.ts'
import type { Prisma } from '../../prisma/generated-client'

export type MessagePosition = { at: string; id: string }
export type MessageSyncCursor = {
  v: 1; room: string; topic: string | null; from: string;
  upper: MessagePosition; after?: MessagePosition;
}
export const MESSAGE_ORDER_ASC = [{ createdAt: 'asc' }, { id: 'asc' }] as const
export const MESSAGE_ORDER_DESC = [{ createdAt: 'desc' }, { id: 'desc' }] as const

function validPosition(value: unknown): value is MessagePosition {
  if (!value || typeof value !== 'object') return false
  const p = value as MessagePosition
  return typeof p.at === 'string' && Number.isFinite(Date.parse(p.at)) &&
    typeof p.id === 'string' && p.id.length > 0 && p.id.length <= 512
}
export function decodeMessageSyncCursor(raw: string, room: string, topic: string | null): MessageSyncCursor {
  if (raw.length > 4096 || !/^[A-Za-z0-9_-]+$/.test(raw)) throw Error('Invalid sync cursor')
  const cursor = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8')) as MessageSyncCursor
  if (cursor.v !== 1 || cursor.room !== room || cursor.topic !== topic ||
      typeof cursor.from !== 'string' || !Number.isFinite(Date.parse(cursor.from)) ||
      !validPosition(cursor.upper) || (cursor.after !== undefined && !validPosition(cursor.after))) {
    throw Error('Invalid sync cursor')
  }
  const from = Date.parse(cursor.from), upper = Date.parse(cursor.upper.at)
  const after = cursor.after ? Date.parse(cursor.after.at) : undefined
  if (from > upper || (after !== undefined && (after < from || after > upper ||
      (after === upper && cursor.after!.id > cursor.upper.id)))) throw Error('Invalid sync cursor bounds')
  return cursor
}
export function encodeMessageSyncCursor(cursor: MessageSyncCursor): string {
  return Buffer.from(JSON.stringify(cursor)).toString('base64url')
}

/** Inclusive lower bound revisits cached edits/tombstones; strict tuple paging avoids ties.
 * Split ranges preserve full composite seeks even when thousands of rows share a timestamp. */
export function readMessageSyncPage<T>(cursor: MessageSyncCursor, take: number,
  read: (where: Prisma.MessageWhereInput, take: number) => Promise<T[]>): Promise<T[]> {
  return readTupleInterval(cursor.after, cursor.upper, take, (range, count) => read({
    conversationId: cursor.room, ...(cursor.topic ? { topicId: cursor.topic } : {}),
    createdAt: range.time, id: range.id,
  }, count), cursor.from)
}
export function readMessagesBefore<T>(at: Date, id: string | undefined, take: number,
  read: (where: Prisma.MessageWhereInput, take: number) => Promise<T[]>): Promise<T[]> {
  if (!id) return read({ createdAt: { lt: at } }, take)
  return readTupleSide({ at: at.toISOString(), id }, 'before', take,
    (range, count) => read({ createdAt: range.time, id: range.id }, count))
}

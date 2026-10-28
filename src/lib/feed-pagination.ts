import { readTupleSide, type TupleRange } from './tuple-page.ts'
import { createHash } from 'node:crypto'

export type FeedPosition = { id: string; at: string }
export class FeedCursorError extends Error { readonly status = 400 }
type Cursor = { v: 1; scope: string; upper: FeedPosition; before: FeedPosition }
const stamp = (p: unknown): p is FeedPosition => {
  if (!p || typeof p !== 'object') return false
  const value = p as FeedPosition
  return typeof value.id === 'string' && value.id.length > 0 && value.id.length <= 256 &&
    typeof value.at === 'string' && Number.isFinite(Date.parse(value.at)) && new Date(value.at).toISOString() === value.at
}
const less = (a: FeedPosition, b: FeedPosition) => a.at < b.at || (a.at === b.at && a.id < b.id)
export function feedScope(...parts: string[]): string { return createHash('sha256').update(JSON.stringify(parts)).digest('hex') }
/** Fixed-upper descending feed. Once a before cursor exists, its strict
 * boundary is already below the upper fence. Split it into index-seekable pieces. */
export async function readFeedBatch<T>(before: FeedPosition | undefined, upper: FeedPosition | undefined,
  take: number, read: (range: TupleRange, take: number) => Promise<T[]>): Promise<T[]> {
  return readTupleSide(before ?? upper, 'before', take, read, before === undefined && upper !== undefined)
}

/** Run inside the caller's authorized transaction. Bounded work is a page, not
 * truncation: even zero matching/fully expired pages return their scan cursor.
 * Only one database batch and one result page are retained in memory. */
export async function scanFeed<Row, Item>(options: {
  cursor: string | null; scope: string; limit: number;
  load: (before: FeedPosition | undefined, upper: FeedPosition | undefined, take: number) => Promise<Row[]>;
  position: (row: Row) => FeedPosition; project: (row: Row) => Item | null;
}) {
  if (!Number.isInteger(options.limit) || options.limit < 1 || options.limit > 100) throw new FeedCursorError('Invalid page size.')
  let before: FeedPosition | undefined, upper: FeedPosition | undefined
  if (options.cursor !== null) {
    try {
      if (options.cursor.length > 2048 || !/^[A-Za-z0-9_-]+$/.test(options.cursor)) throw Error()
      const c = JSON.parse(Buffer.from(options.cursor, 'base64url').toString('utf8')) as Cursor
      if (c.v !== 1 || c.scope !== options.scope || !stamp(c.before) || !stamp(c.upper) || less(c.upper, c.before)) throw Error()
      before = c.before; upper = c.upper
    } catch { throw new FeedCursorError('This page belongs to another feed or is invalid. Start from the newest page.') }
  }
  const items: Item[] = []
  let scanned = 0, hasMore = false
  while (scanned < 1000 && items.length < options.limit) {
    const size = Math.min(200, 1000 - scanned)
    const rows = await options.load(before, upper, size + 1)
    if (rows.length > size + 1) throw Error('Feed loader exceeded its batch')
    if (!rows.length) { hasMore = false; break }
    upper ??= options.position(rows[0])
    for (let i = 0; i < Math.min(rows.length, size); i++) {
      const row = rows[i], position = options.position(row)
      if (!stamp(position) || (before && !less(position, before)) || less(upper, position)) throw Error('Feed order did not advance')
      before = position; scanned++
      const item = options.project(row)
      if (item !== null) items.push(item)
      hasMore = i + 1 < rows.length
      if (items.length === options.limit) break
    }
    if (!hasMore) break
  }
  const nextCursor = hasMore && before && upper
    ? Buffer.from(JSON.stringify({ v: 1, scope: options.scope, upper, before } satisfies Cursor)).toString('base64url') : null
  return { items, feedPageVersion: 1 as const, hasMore, nextCursor }
}

/** Separate timestamp equality from timestamp ranges. SQLite can seek the
 * complete (scope, timestamp, identity) index for each piece; the usual OR
 * tuple predicate can instead walk every newer/older equal-time identity. */
export type TuplePosition = { at: string; id: string }
export type TupleRange = {
  time?: Date | { lt?: Date; gt?: Date; gte?: Date }
  id?: { lt?: string; lte?: string; gt?: string; gte?: string }
}
type Reader<T> = (range: TupleRange, take: number) => Promise<T[]>
function checkSize(take: number): void {
  if (!Number.isInteger(take) || take < 0) throw Error('Invalid tuple page size')
}
async function piece<T>(read: Reader<T>, range: TupleRange, take: number): Promise<T[]> {
  checkSize(take)
  if (!take) return []
  const rows = await read(range, take)
  if (rows.length > take) throw Error('Tuple reader exceeded its requested range')
  return rows
}
export async function readTupleSide<T>(position: TuplePosition | undefined, direction: 'before' | 'after',
  take: number, read: Reader<T>, inclusive = false): Promise<T[]> {
  if (!position) return piece(read, {}, take)
  const at = new Date(position.at)
  if (!Number.isFinite(at.getTime()) || !position.id) throw Error('Invalid tuple position')
  const id = direction === 'before' ? (inclusive ? { lte: position.id } : { lt: position.id })
    : (inclusive ? { gte: position.id } : { gt: position.id })
  const tied = await piece(read, { time: at, id }, take)
  if (tied.length === take) return tied
  const rest = await piece(read, { time: direction === 'before' ? { lt: at } : { gt: at } }, take - tied.length)
  return [...tied, ...rest]
}

/** Ascending (after, upper], including an optional date floor for a first page.
 * At most three disjoint index ranges; no accumulated history or offset scan. */
export async function readTupleInterval<T>(after: TuplePosition | undefined, upper: TuplePosition,
  take: number, read: Reader<T>, floor?: string): Promise<T[]> {
  checkSize(take)
  const end = new Date(upper.at), endTime = end.getTime()
  const afterTime = after ? Date.parse(after.at) : undefined
  const floorTime = floor ? Date.parse(floor) : -8640000000000000
  if (!Number.isFinite(endTime) || !Number.isFinite(floorTime) || floorTime > endTime || !upper.id ||
      (after && (!Number.isFinite(afterTime) || !after.id || afterTime! < floorTime || afterTime! > endTime ||
        (afterTime === endTime && after.id > upper.id)))) throw Error('Invalid tuple interval')
  if (after && afterTime === endTime) {
    return piece(read, { time: end, id: { gt: after.id, lte: upper.id } }, take)
  }
  const rows: T[] = []
  if (after) rows.push(...await piece(read, { time: new Date(after.at), id: { gt: after.id } }, take))
  if (rows.length < take) {
    rows.push(...await piece(read, { time: { ...(after ? { gt: new Date(after.at) } : floor ? { gte: new Date(floor) } : {}), lt: end } }, take - rows.length))
  }
  if (rows.length < take) rows.push(...await piece(read, { time: end, id: { lte: upper.id } }, take - rows.length))
  return rows
}

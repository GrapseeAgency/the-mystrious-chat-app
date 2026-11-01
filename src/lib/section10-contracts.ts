import { createHash } from 'node:crypto'
import type { Prisma } from '../../prisma/generated-client'

// Section 10 shared contracts - events, tasks, Kanban, whiteboards,
// reminders, and packets. Three primitives every domain route composes:
//
//  1. Durable creation receipts: an operation row keyed (userId, clientId)
//     is written in the SAME Serializable tx as the row it vouches for; a
//     lost-ACK retry with the same clientId replays the SAME outcome and a
//     reused identity with different instructions is a 409.
//  2. Room list revisions (RoomListRevision): a per-room monotonic counter
//     for events and Kanban boards; every accepted mutation bumps it inside
//     its commit and every read carries the current value, so cross-device
//     writers are totally ordered and readers apply only strictly-greater revs.
//  3. Keyset pagination: opaque `<epochMs>.<id>` cursors over a stable
//     (createdAt, id) order, limit-bounded, nextCursor null exactly when
//     hasMore is false.

export const CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,128}$/

/** Validate an optional clientId straight off a parsed body. */
export function clientIdFrom(body: Record<string, unknown>): { ok: true; clientId: string | null } | { ok: false } {
  const raw = body.clientId
  if (raw === undefined || raw === null) return { ok: true, clientId: null }
  if (typeof raw !== 'string' || !CLIENT_ID_RE.test(raw)) return { ok: false }
  return { ok: true, clientId: raw }
}

/** Validate an optional clientId from a query string. */
export function clientIdFromQuery(raw: string | null): { ok: true; clientId: string | null } | { ok: false } {
  if (raw === null) return { ok: true, clientId: null }
  if (!CLIENT_ID_RE.test(raw)) return { ok: false }
  return { ok: true, clientId: raw }
}

/** Stable instruction binding for a replay receipt. */
export function opHash(parts: unknown[]): string {
  return createHash('sha256').update(JSON.stringify(parts)).digest('hex')
}

type RevTx = {
  $executeRaw: Prisma.TransactionClient['$executeRaw']
  $queryRaw: Prisma.TransactionClient['$queryRaw']
}

/** Current RoomListRevision for (conversationId, kind); 0 when never bumped. */
export async function readRoomRev(db: Pick<RevTx, '$queryRaw'>, conversationId: string, kind: 'events' | 'kanban'): Promise<number> {
  const rows = await db.$queryRaw<Array<{ rev: number }>>`
    SELECT rev FROM RoomListRevision WHERE conversationId = ${conversationId} AND kind = ${kind}`
  return rows[0]?.rev ?? 0
}

/** Bump the room revision inside the caller's tx and return the new value. */
export async function bumpRoomRev(tx: RevTx, conversationId: string, kind: 'events' | 'kanban'): Promise<number> {
  await tx.$executeRaw`INSERT INTO RoomListRevision (conversationId, kind, rev) VALUES (${conversationId}, ${kind}, 1)
    ON CONFLICT (conversationId, kind) DO UPDATE SET rev = rev + 1`
  return readRoomRev(tx, conversationId, kind)
}

/** Decode a `<epochMs>.<id>` keyset cursor into its parts, or null when absent. */
export function decodeKeysetCursor(raw: string | null): { ok: true; atMs: number; id: string } | { ok: false } | { ok: true; absent: true; atMs?: undefined; id?: undefined } {
  if (raw === null || raw === '') return { ok: true, absent: true }
  if (raw.length > 256) return { ok: false }
  const dot = raw.indexOf('.')
  if (dot <= 0 || dot === raw.length - 1) return { ok: false }
  const atMs = Number(raw.slice(0, dot))
  const id = raw.slice(dot + 1)
  if (!Number.isSafeInteger(atMs) || atMs < 0 || id.length === 0 || id.length > 128) return { ok: false }
  return { ok: true, atMs, id }
}

/** Encode the last row of a page into the next cursor. */
export function encodeKeysetCursor(atMs: number, id: string): string {
  return `${atMs}.${id}`
}

/** Clamp a `limit` query parameter into 1..max, or null when absent (legacy callers). */
export function pageLimit(raw: string | null, max: number): { ok: true; limit: number | null } | { ok: false } {
  if (raw === null) return { ok: true, limit: null }
  const value = Number(raw)
  if (!Number.isInteger(value) || value < 1 || value > max) return { ok: false }
  return { ok: true, limit: value }
}

/** (createdAt, id) DESC keyset continuation fragment - structurally valid for
 * every model that carries createdAt + id; assignability is checked at the
 * call site against that model's WhereInput. */
export function keysetBefore(atMs: number, id: string) {
  const at = new Date(atMs)
  return { OR: [{ createdAt: { lt: at } }, { createdAt: at, id: { lt: id } }] }
}

/** (createdAt, id) ASC keyset continuation fragment. */
export function keysetAfter(atMs: number, id: string) {
  const at = new Date(atMs)
  return { OR: [{ createdAt: { gt: at } }, { createdAt: at, id: { gt: id } }] }
}

/** nextCursor is null exactly when hasMore is false. */
export function pageVerdict<T extends { createdAt: Date; id: string }>(page: T[], limit: number, cursorOf: (row: T) => { atMs: number; id: string }) {
  const hasMore = page.length > limit
  const rows = hasMore ? page.slice(0, limit) : page
  const last = rows.at(-1)
  return { rows, hasMore, nextCursor: hasMore && last ? encodeKeysetCursor(cursorOf(last).atMs, cursorOf(last).id) : null }
}

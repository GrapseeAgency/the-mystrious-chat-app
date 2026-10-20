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
  return cursor
}
export function encodeMessageSyncCursor(cursor: MessageSyncCursor): string {
  return Buffer.from(JSON.stringify(cursor)).toString('base64url')
}

/** Inclusive lower bound revisits cached edits/tombstones; strict tuple paging avoids ties. */
export function messageSyncWhere(cursor: MessageSyncCursor): Prisma.MessageWhereInput {
  return { AND: [
    { conversationId: cursor.room, ...(cursor.topic ? { topicId: cursor.topic } : {}) },
    { createdAt: { gte: new Date(cursor.from) } },
    { OR: [
      { createdAt: { lt: new Date(cursor.upper.at) } },
      { createdAt: new Date(cursor.upper.at), id: { lte: cursor.upper.id } },
    ] },
    ...(cursor.after ? [{ OR: [
      { createdAt: { gt: new Date(cursor.after.at) } },
      { createdAt: new Date(cursor.after.at), id: { gt: cursor.after.id } },
    ] }] : []),
  ] }
}
export function messagesBefore(at: Date, id?: string): Prisma.MessageWhereInput {
  return id ? { OR: [ { createdAt: { lt: at } }, { createdAt: at, id: { lt: id } } ] }
    : { createdAt: { lt: at } }
}

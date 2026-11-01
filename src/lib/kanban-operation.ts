import type { PrismaClient, Prisma, KanbanCard } from '../../prisma/generated-client'
import { threadRootAvailable } from './message-visibility.ts'
import { bumpRoomRev, decodeKeysetCursor, keysetBefore, opHash, pageLimit, pageVerdict, readRoomRev } from './section10-contracts.ts'

export class KanbanError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
const COLUMNS = ['todo', 'doing', 'done']
const MAX_POSITION = 2_147_483_647
const BOARD_LIMIT = 500
// Section 10 item 8: bounded board pages (legacy snapshot mode unchanged);
// (createdAt, id) ASC keyset continuation, walked to completion by clients.
const BOARD_PAGE_MAX = 200
const CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,128}$/
const text = (value: unknown) => typeof value === 'string' ? value.trim() : ''
export type KanbanAction = { kind: 'list'; conversationId: string; limit?: string | null; cursor?: string | null } |
  { kind: 'create'; conversationId: string; input: Record<string, unknown> } |
  { kind: 'update'; cardId: string; input: Record<string, unknown> } | { kind: 'delete'; cardId: string }

function validTitle(value: unknown) {
  const title = text(value)
  if (!title || title.length > 120) throw new KanbanError(400, 'Title must be 1–120 characters.')
  return title
}
function validColumn(value: unknown) {
  const column = text(value)
  if (!COLUMNS.includes(column)) throw new KanbanError(400, 'Column must be todo, doing or done.')
  return column
}
async function appendPosition(tx: Prisma.TransactionClient, conversationId: string, column: string) {
  const aggregate = await tx.kanbanCard.aggregate({ where: { conversationId, column }, _max: { position: true } })
  const position = (aggregate._max.position ?? -1) + 1
  if (position > MAX_POSITION) throw new KanbanError(409, 'This column has reached its position limit.')
  return position
}
async function assignee(tx: Prisma.TransactionClient, conversationId: string, value: unknown) {
  if (value != null && typeof value !== 'string') throw new KanbanError(400, 'Assignee must be a member ID or null.')
  const id = text(value)
  if (!id) return null
  if (!await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: id, conversationId } }, select: { id: true } })) {
    throw new KanbanError(400, 'Assignee must be a participant of this conversation.')
  }
  return id
}
/** Section 10 item 9: live source availability for derived cards (one batched
 * query per page). `sourceRevoked` is true when the card's source message has
 * been deleted, hard-expired, or carries the persisted revocation stamp. */
async function annotateSources(tx: Prisma.TransactionClient, rows: KanbanCard[], clock: () => Date) {
  const sourceIds = [...new Set(rows.map(row => row.sourceMessageId).filter((id): id is string => !!id))]
  if (sourceIds.length === 0) return rows.map(row => sourceView(row, false))
  const sources = await tx.message.findMany({
    where: { id: { in: sourceIds } },
    select: { id: true, deletedAt: true, expiresAt: true },
  })
  const byId = new Map(sources.map(message => [message.id, message]))
  return rows.map(row => {
    if (!row.sourceMessageId) return sourceView(row, false)
    const source = byId.get(row.sourceMessageId)
    const available = !!source && source.deletedAt === null && !(source.expiresAt && source.expiresAt <= clock())
    return sourceView(row, !available)
  })
}
function sourceView(row: KanbanCard, sourceRevoked: boolean) {
  return { ...row, sourceRevoked, sourceAvailable: row.sourceMessageId ? !sourceRevoked : null }
}
async function serializeCards(tx: Prisma.TransactionClient, rows: KanbanCard[], clock: () => Date) {
  const ids = [...new Set(rows.flatMap(row => [row.assigneeId, row.createdById]).filter((id): id is string => !!id))]
  const names = new Map((await tx.user.findMany({ where: { id: { in: ids } }, select: { id: true, name: true } })).map(user => [user.id, user.name]))
  return (await annotateSources(tx, rows, clock)).map(row => ({ ...row, assigneeName: row.assigneeId ? names.get(row.assigneeId) ?? null : null,
    createdByName: row.createdById ? names.get(row.createdById) ?? null : null,
    createdAt: row.createdAt.toISOString(), updatedAt: row.updatedAt.toISOString() }))
}

/** Native board acceptance is a committed, owner-labelled snapshot, not a status
 * code. Caller must bind the credential to userId before invoking this helper. */
export async function kanbanOperation(db: PrismaClient, userId: string, action: KanbanAction, clock: () => Date = () => new Date()) {
  try {
    return await db.$transaction(async tx => {
      const current = 'cardId' in action ? await tx.kanbanCard.findUnique({ where: { id: action.cardId } }) : null
      if ('cardId' in action && !current) throw new KanbanError(404, 'Card not found.')
      const conversationId = 'conversationId' in action ? action.conversationId : current!.conversationId
      const room = await tx.conversation.findUnique({ where: { id: conversationId }, select: { isGroup: true } })
      if (!room) throw new KanbanError(404, 'Conversation not found.')
      const member = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId, conversationId } }, select: { role: true },
      })
      if (!member) throw new KanbanError(403, 'You are not a participant of this conversation.')
      const receipt = { boardReceiptVersion: 1 as const, userId, conversationId }
      if (action.kind === 'list') {
        const boardRev = await readRoomRev(tx, conversationId, 'kanban')
        // Section 10 item 8: paged mode - bounded keyset pages over
        // (createdAt, id) ASC, boardRev on every page, no 413 boundary.
        if (action.limit !== undefined) {
          const verdict = pageLimit(action.limit === null ? null : action.limit, BOARD_PAGE_MAX)
          if (!verdict.ok || verdict.limit === null) throw new KanbanError(400, `limit must be an integer from 1 to ${BOARD_PAGE_MAX}.`)
          const cursor = decodeKeysetCursor(action.cursor ?? null)
          if (!cursor.ok) throw new KanbanError(400, 'cursor must be a board continuation token.')
          const found = await tx.kanbanCard.findMany({
            where: { conversationId, ...('atMs' in cursor && cursor.atMs !== undefined ? keysetBefore(cursor.atMs, cursor.id) as Prisma.KanbanCardWhereInput : {}) },
            orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
            take: verdict.limit + 1,
          })
          const { rows, hasMore, nextCursor } = pageVerdict(found, verdict.limit, (row) => ({ atMs: row.createdAt.getTime(), id: row.id }))
          return { ...receipt, boardRev, cards: await serializeCards(tx, rows, clock), hasMore, nextCursor }
        }
        const rows = await tx.kanbanCard.findMany({ where: { conversationId }, take: BOARD_LIMIT + 1,
          orderBy: [{ position: 'asc' }, { createdAt: 'asc' }, { id: 'asc' }] })
        if (rows.length > BOARD_LIMIT) throw new KanbanError(413, 'This board exceeds the supported 500-card snapshot. No partial board was loaded.')
        rows.sort((a,b) => COLUMNS.indexOf(a.column) - COLUMNS.indexOf(b.column) || a.position-b.position ||
          a.createdAt.getTime()-b.createdAt.getTime() || (a.id < b.id ? -1 : a.id === b.id ? 0 : 1))
        return { ...receipt, boardRev, cards: await serializeCards(tx, rows, clock) }
      }
      if (action.kind === 'delete') {
        if (current!.createdById !== userId && !(room.isGroup && member.role === 'admin')) {
          throw new KanbanError(403, 'Only the card creator or a group admin can delete this card.')
        }
        await tx.kanbanCard.delete({ where: { id: action.cardId } })
        // Section 10 item 7: the revision moves inside the same commit as
        // the deletion, so cross-device readers order it deterministically.
        return { ...receipt, ok: true, cardId: action.cardId, boardRev: await bumpRoomRev(tx, conversationId, 'kanban') }
      }
      const input = action.input
      let result: KanbanCard
      if (action.kind === 'create') {
        // Section 10 item 6: durable card-creation identity. A lost ACK
        // replays the SAME clientId into the SAME stored card; mismatched
        // identity reuse is a 409 so two different cards never share one.
        const clientId = text(input.clientId)
        if (clientId && !CLIENT_ID_RE.test(clientId)) throw new KanbanError(400, 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.')
        const draftTitle = input.title === undefined ? null : validTitle(input.title)
        const draftColumn = input.column == null || input.column === '' ? 'todo' : validColumn(input.column)
        const sourceMessageId = text(input.messageId)
        if (clientId) {
          const prior = await tx.kanbanCardOperation.findUnique({ where: { userId_clientId: { userId, clientId } } })
          if (prior) {
            // The replay verdict binds the instructions: title/column/message.
            const replayHash = opHash(['kanban-create', conversationId, sourceMessageId, draftTitle, draftColumn])
            if (prior.requestHash !== replayHash) throw new KanbanError(409, 'This card operation ID was already used for different instructions.')
            const existing = await tx.kanbanCard.findUnique({ where: { id: prior.cardId } })
            if (!existing) throw new KanbanError(410, 'This created card no longer exists.')
            return { ...receipt, card: (await serializeCards(tx, [existing], clock))[0], boardRev: await readRoomRev(tx, conversationId, 'kanban'), replayed: true as const }
          }
        }
        let title: string
        if (sourceMessageId) {
          const source = await tx.message.findUnique({ where: { id: sourceMessageId }, select: {
            conversationId: true, content: true, deletedAt: true, viewOnce: true, expiresAt: true, parentId: true,
            parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true, viewOnce: true } },
          } })
          if (!source || source.conversationId !== conversationId) throw new KanbanError(400, 'Message must belong to this conversation.')
          if (source.deletedAt || (source.expiresAt && source.expiresAt <= clock()) ||
              (source.parentId && !threadRootAvailable(source.parent, conversationId, clock()))) {
            throw new KanbanError(410, 'This message or its thread is no longer available.')
          }
          // A permanent task cannot inherit a disappearing/view-once contract.
          if (source.viewOnce || source.expiresAt || (source.parentId && (source.parent?.expiresAt || source.parent?.viewOnce))) {
            throw new KanbanError(400, 'View-once or disappearing messages cannot be converted into permanent tasks.')
          }
          title = draftTitle ?? (source.content.replace(/\s+/g, ' ').trim().slice(0,80) || 'Task')
        } else { title = validTitle(input.title) }
        const column = draftColumn
        const assigneeId = await assignee(tx, conversationId, input.assigneeId)
        if (await tx.kanbanCard.count({ where: { conversationId } }) >= BOARD_LIMIT) throw new KanbanError(409, 'This board has reached 500 cards. Remove completed cards before adding more.')
        result = await tx.kanbanCard.create({ data: { conversationId, title, column, assigneeId,
          position: await appendPosition(tx, conversationId, column), createdById: userId, sourceMessageId: sourceMessageId || null } })
        if (clientId) {
          await tx.kanbanCardOperation.create({ data: {
            userId, clientId,
            requestHash: opHash(['kanban-create', conversationId, sourceMessageId, draftTitle, draftColumn]),
            cardId: result.id,
          } })
        }
        // Do not make another durable plaintext copy of a private message title.
        await tx.logEvent.create({ data: { userId, kind: 'message', message: sourceMessageId ? 'converted a message into a task' : 'created a board task',
          meta: JSON.stringify({ conversationId, cardId: result.id }) } })
      } else {
        const data: Prisma.KanbanCardUpdateInput = {}
        if (input.title !== undefined) data.title = validTitle(input.title)
        if (input.column !== undefined) data.column = validColumn(input.column)
        if (input.assigneeId !== undefined) data.assigneeId = await assignee(tx, conversationId, input.assigneeId)
        if (input.position !== undefined) {
          if (typeof input.position !== 'number' || !Number.isInteger(input.position) || input.position < 0 || input.position > MAX_POSITION) {
            throw new KanbanError(400, 'Position must be an integer from 0 to 2147483647.')
          }
          data.position = input.position
        } else if (typeof data.column === 'string' && data.column !== current!.column) {
          data.position = await appendPosition(tx, conversationId, data.column)
        }
        result = await tx.kanbanCard.update({ where: { id: action.cardId }, data })
      }
      // Name lookup and serialized final receipt are part of the same commit;
      // every accepted mutation advances the board revision (item 7).
      return { ...receipt, card: (await serializeCards(tx, [result], clock))[0], boardRev: await bumpRoomRev(tx, conversationId, 'kanban'), replayed: false as const }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008','P2028','P2034','P2002'].includes((error as { code?: string })?.code ?? '')) {
      throw new KanbanError(409, 'The board changed or is busy. Refresh before retrying.')
    }
    throw error
  }
}

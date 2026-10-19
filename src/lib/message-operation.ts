import { createHash } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class MessageOperationError extends Error {
  constructor(public readonly status: number, message: string, public readonly clientId?: string, public readonly code?: string) { super(message) }
}
export interface MessageOperation {
  senderId: string
  clientId: string
  conversationId: string
  requestHash: string
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return `{${Object.keys(record).sort().map(key => `${JSON.stringify(key)}:${canonical(record[key])}`).join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

/** An operation is scoped to its sender, not its text or delivery attempt. */
export function messageOperation(senderId: string, conversationId: string, body: Record<string, unknown>): MessageOperation | null {
  if (body.clientId === undefined || body.clientId === null) return null // legacy clients
  if (typeof body.clientId !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(body.clientId)) {
    throw new MessageOperationError(400, 'clientId must contain 16–128 ASCII letters, digits, underscores or hyphens.')
  }
  const text = (key: string) => typeof body[key] === 'string' ? (body[key] as string).trim() : ''
  const request = {
    conversationId, content: text('content'), kind: text('kind') || 'text',
    replyToId: text('replyToId'), parentId: text('parentId'), topicId: text('topicId'),
    imagePath: text('imagePath'), audioPath: text('audioPath'), filePath: text('filePath'),
    fileName: text('fileName').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' '),
    durationMs: typeof body.durationMs === 'number' ? Math.round(body.durationMs) : null,
    fileSize: body.fileSize ?? null, payload: body.payload ?? null,
    anon: body.anon === true, viewOnce: body.viewOnce === true,
  }
  return { senderId, conversationId, clientId: body.clientId,
    requestHash: createHash('sha256').update(canonical(request)).digest('hex') }
}

type Receipt = MessageOperation & { messageId: string }
export async function replayMessageOperation<T>(db: PrismaClient, op: MessageOperation | null, find: (id: string) => Promise<T | null>): Promise<T | null> {
  if (!op) return null
  let rows: Receipt[]
  try {
    rows = await db.$queryRaw<Receipt[]>`
      SELECT * FROM "MessageSendOperation" WHERE "senderId" = ${op.senderId} AND "clientId" = ${op.clientId}`
  } catch (error) {
    if (error instanceof Error && /no such table: (?:main\.)?MessageSendOperation/.test(error.message)) {
      throw new MessageOperationError(503, 'The server needs its delivery-receipt database upgrade. Nothing was created; retry after the upgrade.')
    }
    throw error
  }
  const receipt = rows[0]
  if (!receipt) return null
  if (receipt.requestHash !== op.requestHash || receipt.conversationId !== op.conversationId) {
    throw new MessageOperationError(409, 'clientId was already used for a different message.')
  }
  // Retain receipts after TTL/deletion. Never resurrect an expired send.
  const message = await find(receipt.messageId)
  if (!message) throw new MessageOperationError(410, 'This operation was delivered, but its message is no longer available.')
  return message
}

/** Message + receipt commit together. A concurrent loser rolls back its entire write. */
export async function commitMessageOperation<T extends { id: string }>(
  db: PrismaClient, op: MessageOperation | null,
  create: (tx: Prisma.TransactionClient) => Promise<T>, find: (id: string) => Promise<T | null>,
): Promise<{ message: T; replayed: boolean }> {
  const prior = await replayMessageOperation(db, op, find)
  if (prior) return { message: prior, replayed: true }
  try {
    const message = await db.$transaction(async tx => {
      const message = await create(tx)
      if (op) await tx.$executeRaw`
        INSERT INTO "MessageSendOperation" ("senderId", "clientId", "conversationId", "requestHash", "messageId")
        VALUES (${op.senderId}, ${op.clientId}, ${op.conversationId}, ${op.requestHash}, ${message.id})`
      return message
    })
    return { message, replayed: false }
  } catch (error) {
    // Covers uniqueness races without swallowing unrelated persistence failures.
    const winner = await replayMessageOperation(db, op, find)
    if (winner) return { message: winner, replayed: true }
    throw error
  }
}

export type RoomCreation = {
  userId: string
  conversationId: string
  clientId?: unknown
} & ({ kind: 'game'; opponentId?: string } | { kind: 'tournament'; name: string })

/** Match/season + carrier + delivery receipt are a single commit. The immutable
 * instruction (not the current board or standings) identifies a creation retry. */
export async function createRoomObject(db: PrismaClient, request: RoomCreation) {
  const { userId, conversationId, kind } = request
  const opponentId = kind === 'game' ? (request.opponentId ?? '').trim() : ''
  const name = kind === 'tournament' ? request.name.trim() : ''
  if (!userId || !conversationId) throw new MessageOperationError(400, 'userId and conversationId are required.')
  if (opponentId === userId) throw new MessageOperationError(400, 'opponentId must differ from userId.')
  if (kind === 'tournament' && (!name || name.length > 40)) throw new MessageOperationError(400, 'name must be between 1 and 40 characters.')
  const op = messageOperation(userId, conversationId, {
    clientId: request.clientId, kind: `room-create-${kind}`,
    payload: kind === 'game' ? { game: 'tictactoe', opponentId } : { game: 'tictactoe', name },
  })
  const authorize = async (tx: Prisma.TransactionClient) => {
    const conversation = await tx.conversation.findUnique({ where: { id: conversationId }, select: { ttlSeconds: true } })
    if (!conversation) throw new MessageOperationError(404, 'Conversation not found.')
    if (!await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId, conversationId } }, select: { id: true } })) {
      throw new MessageOperationError(403, 'You are not a participant of this conversation.')
    }
    return conversation
  }
  await authorize(db)
  const result = await commitMessageOperation(db, op, async tx => {
    const conversation = await authorize(tx)
    const now = new Date()
    let resourceId: string
    if (kind === 'game') {
      // Only a NEW invitation requires the opponent to still be a member.
      // Replaying a delivered invitation must work after that person leaves.
      if (opponentId && !await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: opponentId, conversationId } }, select: { id: true } })) {
        throw new MessageOperationError(400, 'opponentId must be a participant of this conversation.')
      }
      const match = await tx.gameMatch.create({ data: { conversationId, game: 'tictactoe', playerXId: userId, playerOId: opponentId || null } })
      resourceId = match.id
    } else {
      const season = await tx.tournament.create({ data: { conversationId, name, game: 'tictactoe', createdById: userId, createdAt: now } })
      resourceId = season.id
      await tx.tournamentPlayer.create({ data: { tournamentId: resourceId, userId, joinedAt: now } })
      await tx.logEvent.create({ data: { userId, kind, message: `started tournament "${name}" (tictactoe)`, meta: JSON.stringify({ tournamentId: resourceId, conversationId, game: 'tictactoe' }) } })
    }
    const message = await tx.message.create({ data: {
      conversationId, senderId: userId, kind,
      content: kind === 'game' ? (opponentId ? 'Tic-tac-toe challenge' : 'Tic-tac-toe open challenge') : `Tournament "${name}" started`,
      payload: JSON.stringify(kind === 'game' ? { matchId: resourceId, game: 'tictactoe' } : { tournamentId: resourceId, name, game: 'tictactoe' }),
      // Both kinds obey the room's disappearing-message setting.
      expiresAt: conversation.ttlSeconds > 0 ? new Date(now.getTime() + conversation.ttlSeconds * 1000) : null,
      createdAt: now,
    }, select: { id: true, payload: true } })
    await tx.conversation.update({ where: { id: conversationId }, data: { updatedAt: now }, select: { id: true } })
    await tx.conversationParticipant.updateMany({ where: { userId, conversationId }, data: { lastReadAt: now } })
    await tx.conversationParticipant.updateMany({ where: { conversationId, userId: { not: userId }, archivedAt: { not: null } }, data: { archivedAt: null } })
    return message
  }, id => db.message.findFirst({ where: {
    id, conversationId, senderId: userId, kind, deletedAt: null,
    OR: [{ expiresAt: null }, { expiresAt: { gt: new Date() } }],
  }, select: { id: true, payload: true } })).catch(error => {
    if (op && error instanceof MessageOperationError && error.status === 410) {
      throw new MessageOperationError(410, 'The earlier creation was delivered, but its message was removed or expired. Submit again to start a new one.', op.clientId, 'creation_gone')
    }
    throw error
  })
  let payload: Record<string, unknown>
  try { payload = JSON.parse(result.message.payload ?? '') }
  catch { throw new MessageOperationError(409, 'The creation receipt is unreadable. No new object was created.') }
  const resourceId = payload?.[kind === 'game' ? 'matchId' : 'tournamentId']
  if (typeof resourceId !== 'string' || !resourceId) throw new MessageOperationError(409, 'The creation receipt has no object. No new object was created.')
  return { messageId: result.message.id, resourceId, replayed: result.replayed, clientId: op?.clientId ?? null }
}

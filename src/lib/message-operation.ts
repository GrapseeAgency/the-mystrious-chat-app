import { createHash } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class MessageOperationError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
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
  const rows = await db.$queryRaw<Receipt[]>`
    SELECT * FROM "MessageSendOperation" WHERE "senderId" = ${op.senderId} AND "clientId" = ${op.clientId}`
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

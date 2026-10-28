import { createHash } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class UploadOperationError extends Error {
  constructor(public readonly status: number, message: string, public readonly retryAfter?: string) { super(message) }
}

export interface UploadOperation {
  clientKey: string
  contentHash: string
}

/**
 * R42 - stable upload receipt. Both native outboxes retry an upload when the
 * HTTP response is lost after the server already stored the bytes; the retry
 * re-POSTs the byte-identical data URL. A client-supplied stable key
 * identifies that retry so the replay returns the SAME stored filename
 * instead of minting a second orphan copy. The hash covers mime + payload:
 * one key must never silently acknowledge different bytes.
 */
export function uploadOperation(body: Record<string, unknown>, mime: string, payload: string): UploadOperation | null {
  if (body.clientKey === undefined || body.clientKey === null) return null // legacy callers (web, old builds)
  if (typeof body.clientKey !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(body.clientKey)) {
    throw new UploadOperationError(400, 'clientKey must contain 16-128 ASCII letters, digits, underscores or hyphens.')
  }
  return {
    clientKey: body.clientKey,
    contentHash: createHash('sha256').update(`${mime};base64,${payload}`).digest('hex'),
  }
}

export async function replayUploadOperation(db: PrismaClient, op: UploadOperation | null): Promise<string | null> {
  if (!op) return null
  let rows: { clientKey: string; contentHash: string; name: string }[]
  try {
    rows = await db.$queryRaw`SELECT clientKey, contentHash, name FROM UploadOperation WHERE clientKey = ${op.clientKey}`
  } catch (error) {
    if (error instanceof Error && /no such table: (?:main\.)?UploadOperation/.test(error.message)) {
      throw new UploadOperationError(503, 'The server needs its upload-receipt database upgrade. Nothing was stored; retry after the upgrade.', '3')
    }
    throw error
  }
  const receipt = rows[0]
  if (!receipt) return null
  if (receipt.contentHash !== op.contentHash) {
    throw new UploadOperationError(409, 'clientKey was already used for a different upload.')
  }
  return receipt.name
}

/**
 * Stored bytes + receipt commit together. A concurrent duplicate-key loser
 * rolls back its whole write (own UploadedFile row included) and resolves to
 * the winner's receipt instead of leaving a second file behind.
 */
export async function commitUploadOperation(
  db: PrismaClient,
  op: UploadOperation | null,
  name: string,
  store: (tx: Prisma.TransactionClient | PrismaClient) => Promise<void>,
): Promise<void> {
  if (!op) { await store(db); return }
  try {
    await db.$transaction(async tx => {
      await store(tx)
      await tx.$executeRaw`INSERT INTO UploadOperation (clientKey, contentHash, name) VALUES (${op.clientKey}, ${op.contentHash}, ${name})`
    })
  } catch (error) {
    // Uniqueness race (or a 409/503 replay finding): re-resolve through the
    // receipt. A winner exists => the loser's rolled-back store stays rolled
    // back and the caller answers with the winner's filename.
    const winner = await replayUploadOperation(db, op).catch(() => null)
    if (winner) return
    if (error instanceof UploadOperationError) throw error
    throw error
  }
}

import { createHash, randomUUID } from 'node:crypto'
import type { PrismaClient, Prisma } from '../../prisma/generated-client'
import { threadRootAvailable } from './message-visibility.ts'

export class SavedMessageError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
const readSaved = (tx: Prisma.TransactionClient, userId: string, messageId: string) =>
  tx.savedMessage.findUnique({ where: { userId_messageId: { userId, messageId } }, select: { savedAt: true } })

/** Explicit state is replay-safe; undefined retains the legacy web toggle.
 * Removing one's own bookmark is allowed after leaving/deletion. Adding requires
 * live message/root and current membership, in the same transaction as the write.
 * Caller must bind credentials to userId before invoking this helper. */
export async function mutateSavedMessage(db: PrismaClient, userId: string, messageId: string, desired?: boolean,
  readReceipt: (tx: Prisma.TransactionClient, userId: string, messageId: string) => Promise<{ savedAt: Date } | null> = readSaved, clock: () => Date = () => new Date()) {
  try {
    return await db.$transaction(async tx => {
      const receipt = await applySavedMessage(tx, userId, messageId, desired, readReceipt, clock)
      await tx.savedMessageState.upsert({ where: { userId_messageId: { userId, messageId } },
        create: { userId, messageId, revision: randomUUID() }, update: { revision: randomUUID() } })
      return receipt
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
      throw new SavedMessageError(409, 'Saved state changed or is busy. Retry the same action.')
    }
    throw error
  }
}

async function applySavedMessage(tx: Prisma.TransactionClient, userId: string, messageId: string, desired: boolean | undefined,
  readReceipt: (tx: Prisma.TransactionClient, userId: string, messageId: string) => Promise<{ savedAt: Date } | null>, clock: () => Date) {
      const existing = await readSaved(tx, userId, messageId)
      const saved = desired ?? !existing
      if (saved) {
        const message = await tx.message.findUnique({ where: { id: messageId }, select: {
          conversationId: true, deletedAt: true, expiresAt: true, viewOnce: true, parentId: true,
          parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
        } })
        if (!message) throw new SavedMessageError(404, 'Message not found.')
        const member = await tx.conversationParticipant.findUnique({
          where: { userId_conversationId: { userId, conversationId: message.conversationId } }, select: { id: true },
        })
        if (!member) throw new SavedMessageError(403, 'You are not a participant of this conversation.')
        const now = clock()
        if (message.deletedAt || (message.expiresAt && message.expiresAt <= now) ||
            (message.parentId && !threadRootAvailable(message.parent, message.conversationId, now))) {
          throw new SavedMessageError(410, 'This message or its thread is no longer available.')
        }
        if (message.viewOnce) throw new SavedMessageError(400, 'View-once messages cannot be saved.')
        if (!existing) await tx.savedMessage.create({ data: { userId, messageId, savedAt: now } })
      } else {
        await tx.savedMessage.deleteMany({ where: { userId, messageId } })
      }
      const receipt = await readReceipt(tx, userId, messageId)
      if (Boolean(receipt) !== saved) throw new SavedMessageError(409, 'Saved state was not confirmed.')
      return { saved, savedAt: receipt?.savedAt.toISOString() ?? null, userId, messageId, savedReceiptVersion: 1 as const }
}

export type SavedIntent = { clientId: string; expectedRevision?: string; predecessorClientId?: string }
const token = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value)
export function validateSavedIntent(value: SavedIntent) {
  if (!token(value.clientId) || (!!value.expectedRevision === !!value.predecessorClientId) ||
      (value.expectedRevision !== undefined && !token(value.expectedRevision)) ||
      (value.predecessorClientId !== undefined && (!token(value.predecessorClientId) || value.predecessorClientId === value.clientId))) {
    throw new SavedMessageError(400, 'A stable action identity and exactly one causal predecessor or revision are required.')
  }
}

async function savedSnapshot(tx: Prisma.TransactionClient, userId: string, messageId: string) {
  const [marker, state] = await Promise.all([readSaved(tx, userId, messageId),
    tx.savedMessageState.findUnique({ where: { userId_messageId: { userId, messageId } } })])
  let visible = Boolean(marker)
  if (marker) {
    const message = await tx.message.findUnique({ where: { id: messageId }, select: {
      conversationId: true, deletedAt: true, expiresAt: true, viewOnce: true, parentId: true,
      parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } },
    } })
    const member = message && await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: message.conversationId } }, select: { id: true },
    })
    const now = new Date()
    visible = Boolean(message && member && !message.deletedAt && !message.viewOnce &&
      (!message.expiresAt || message.expiresAt > now) &&
      (!message.parentId || threadRootAvailable(message.parent, message.conversationId, now)))
  }
  return { savedReceiptVersion: 2 as const, userId, messageId, saved: Boolean(marker), visible,
    savedAt: marker?.savedAt.toISOString() ?? null, revision: state?.revision ?? '0' }
}

export async function readSavedMessageStates(db: PrismaClient, userId: string, ids: string[]) {
  if (ids.length > 100 || new Set(ids).size !== ids.length || ids.some(id => typeof id !== 'string' || !id.trim() || id.length > 128)) {
    throw new SavedMessageError(400, 'Request at most 100 distinct message identities.')
  }
  return db.$transaction(async tx => {
    const states: Array<Awaited<ReturnType<typeof savedSnapshot>>> = []
    for (const id of ids) states.push(await savedSnapshot(tx, userId, id))
    return states
  }, { isolationLevel: 'Serializable', timeout: 15000 })
}

/** CAS is deliberate: stale offline device A cannot silently undo B's newer
 * action. Same-device queued actions name their predecessor, not wall clocks.
 * A lost response reuses its immutable identity and returns CURRENT truth. */
export async function mutateSavedIntent(db: PrismaClient, userId: string, messageId: string, saved: boolean, intent: SavedIntent) {
  validateSavedIntent(intent)
  const requestHash = createHash('sha256').update(JSON.stringify([messageId, saved, intent.expectedRevision ?? null, intent.predecessorClientId ?? null])).digest('hex')
  try {
    return await db.$transaction(async tx => {
      const where = { userId_clientId: { userId, clientId: intent.clientId } }
      const prior = await tx.savedMessageOperation.findUnique({ where })
      if (prior && (prior.requestHash !== requestHash || prior.messageId !== messageId)) throw new SavedMessageError(409, 'This saved action identity was reused with different content.')
      const current = await savedSnapshot(tx, userId, messageId)
      if (prior) return { ...current, clientId: intent.clientId,
        outcome: prior.outcome !== 'applied' ? prior.outcome : prior.revision === current.revision && current.saved === saved ? 'applied' : 'superseded' }
      let expected = intent.expectedRevision
      if (intent.predecessorClientId) {
        const predecessor = await tx.savedMessageOperation.findUnique({ where: { userId_clientId: { userId, clientId: intent.predecessorClientId } } })
        if (!predecessor) throw new SavedMessageError(409, 'The preceding saved action must finish first.')
        if (predecessor.messageId !== messageId) throw new SavedMessageError(400, 'Saved predecessor belongs to another message.')
        expected = predecessor.outcome === 'applied' ? predecessor.revision : undefined
      }
      if (expected !== current.revision) {
        await tx.savedMessageOperation.create({ data: { userId, clientId: intent.clientId, messageId, requestHash, revision: current.revision, outcome: 'conflict' } })
        return { ...current, clientId: intent.clientId, outcome: 'conflict' }
      }
      try { await applySavedMessage(tx, userId, messageId, saved, readSaved, () => new Date()) }
      catch (error) {
        if (!(error instanceof SavedMessageError) || ![400,403,404,410].includes(error.status)) throw error
        await tx.savedMessageOperation.create({ data: { userId, clientId: intent.clientId, messageId, requestHash, revision: current.revision, outcome: 'rejected' } })
        return { ...current, clientId: intent.clientId, outcome: 'rejected' }
      }
      const revision = randomUUID()
      await tx.savedMessageState.upsert({ where: { userId_messageId: { userId, messageId } }, create: { userId, messageId, revision }, update: { revision } })
      await tx.savedMessageOperation.create({ data: { userId, clientId: intent.clientId, messageId, requestHash, revision, outcome: 'applied' } })
      const confirmed = await savedSnapshot(tx, userId, messageId)
      return { ...confirmed, clientId: intent.clientId, outcome: confirmed.saved === saved ? 'applied' : 'superseded' }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) throw new SavedMessageError(409, 'Saved state is busy. Retry this same action.')
    throw error
  }
}

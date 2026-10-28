import { createHash } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { threadRootAvailable } from './message-visibility.ts'

export class ComposerError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export type ComposerKind = 'draft' | 'edit'
export type ComposerIntent = { clientId: string; expectedRevision?: string; predecessorClientId?: string }
const digest = (value: unknown) => createHash('sha256').update(JSON.stringify(value)).digest('hex')
const token = (value: unknown): value is string => typeof value === 'string' && value.length >= 1 && value.length <= 128 && !/[^A-Za-z0-9_-]/.test(value)
export function composerFailure(error: unknown): Response {
  if (error instanceof ComposerError) return Response.json({ error: error.message }, { status: error.status, headers: { 'Cache-Control': 'private, no-store' } })
  if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? ''))
    return Response.json({ error: 'Composer storage is busy. Retry the same operation.' }, { status: 409 })
  throw error
}
function validate(kind: ComposerKind, target: string, root: string) {
  if (!['draft','edit'].includes(kind) || typeof target !== 'string' || typeof root !== 'string' || !target ||
      target.length > 128 || root.length > 128 || target.trim() !== target || root.trim() !== root ||
      /[\u0000-\u001f\u007f]/.test(target + root) || (kind === 'edit' && root))
    throw new ComposerError(400, 'Invalid composer scope.')
}
const rootFields = { id: true, conversationId: true, parentId: true, deletedAt: true, expiresAt: true } as const
async function snapshot(tx: Prisma.TransactionClient, owner: string, kind: ComposerKind, target: string, root: string, now: Date) {
  validate(kind, target, root)
  const message = kind === 'edit' ? await tx.message.findUnique({ where: { id: target }, select: {
    ...rootFields, senderId: true, content: true, editedAt: true, parent: { select: rootFields },
  } }) : null
  if (kind === 'edit' && !message) throw new ComposerError(404, 'Message not found.')
  const room = message?.conversationId ?? target
  const member = await tx.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: owner, conversationId: room } },
    select: { id: true, draft: true, draftRevision: true } })
  if (!member) throw new ComposerError(403, 'You are not a participant of this conversation.')
  if (message) {
    if (message.senderId !== owner) throw new ComposerError(403, 'Only the sender can edit this message.')
    if (message.deletedAt || (message.expiresAt && message.expiresAt <= now) ||
        (message.parentId && !threadRootAvailable(message.parent, room, now))) throw new ComposerError(410, 'This message or its thread is no longer available.')
  }
  if (root) {
    const parent = await tx.message.findUnique({ where: { id: root }, select: rootFields })
    if (!threadRootAvailable(parent, room, now)) throw new ComposerError(410, 'This thread is no longer available.')
  }
  const thread = root ? await tx.threadDraftState.findUnique({ where: { userId_conversationId_rootId: { userId: owner, conversationId: room, rootId: root } } }) : null
  const text = message?.content ?? (root ? thread?.draft ?? '' : member.draft ?? '')
  const counter = root ? thread?.revision ?? '0' : member.draftRevision === '0' && member.draft ? '1' : member.draftRevision
  // Membership identity prevents an old command being applied after leave/rejoin.
  const revision = message ? digest([member.id, message.id, message.content, message.editedAt?.toISOString() ?? null]) : `${member.id}:${counter}`
  return { wire: { composerVersion: 1 as const, userId: owner, kind, targetId: target, rootId: root || null,
    conversationId: room, text, revision, editedAt: message?.editedAt?.toISOString() ?? null }, member, message, counter }
}
export function readComposer(db: PrismaClient, owner: string, kind: ComposerKind, target: string, root = '') {
  return db.$transaction(async tx => (await snapshot(tx, owner, kind, target, root, new Date())).wire, { isolationLevel: 'Serializable' })
}
/** Immutable operations + CAS. Conflict is terminal for this identity; neither
 * response loss nor a later retry rebases it onto another device's revision. */
export function writeComposer(db: PrismaClient, owner: string, kind: ComposerKind, target: string, root: string,
  text: string, intent: ComposerIntent, clock: () => Date = () => new Date()) {
  validate(kind, target, root)
  if (typeof text !== 'string' || text.length > 2000 || (kind === 'edit' && !text.trim())) throw new ComposerError(400, 'Invalid composer text.')
  if (!token(intent.clientId) || (intent.expectedRevision !== undefined) === (intent.predecessorClientId !== undefined) ||
      (intent.expectedRevision !== undefined && (typeof intent.expectedRevision !== 'string' || intent.expectedRevision.length < 1 || intent.expectedRevision.length > 256 || /[^A-Za-z0-9_.:-]/.test(intent.expectedRevision))) ||
      (intent.predecessorClientId !== undefined && (!token(intent.predecessorClientId) || intent.predecessorClientId === intent.clientId)))
    throw new ComposerError(400, 'Use an immutable operation ID and exactly one expected revision or predecessor.')
  const requestHash = digest([kind, target, root, text, intent.expectedRevision ?? null, intent.predecessorClientId ?? null])
  return db.$transaction(async tx => {
    const prior = await tx.composerOperation.findUnique({ where: { userId_clientId: { userId: owner, clientId: intent.clientId } } })
    if (prior && prior.requestHash !== requestHash) throw new ComposerError(409, 'This composer operation ID was reused with different instructions.')
    const current = await snapshot(tx, owner, kind, target, root, clock())
    if (prior) return { ...current.wire, clientId: intent.clientId,
      outcome: prior.outcome === 'applied' && prior.revision !== current.wire.revision ? 'superseded' : prior.outcome, changed: false }
    let expected = intent.expectedRevision
    if (intent.predecessorClientId) {
      const previous = await tx.composerOperation.findUnique({ where: { userId_clientId: { userId: owner, clientId: intent.predecessorClientId } } })
      if (!previous) throw new ComposerError(409, 'The preceding composer operation must finish first.')
      if (previous.kind !== kind || previous.targetId !== target || previous.rootId !== root) throw new ComposerError(400, 'The predecessor belongs to another composer.')
      expected = previous.outcome === 'applied' ? previous.revision : undefined
    }
    const outcome = expected === current.wire.revision ? 'applied' : 'conflict'
    if (outcome === 'applied') {
      if (kind === 'edit') {
        const editedAt = new Date(Math.max(clock().getTime(), (current.message!.editedAt?.getTime() ?? -1) + 1))
        await tx.message.update({ where: { id: target }, data: { content: text, editedAt }, select: { id: true } })
      } else {
        const revision = (BigInt(current.counter) + BigInt(1)).toString()
        if (revision.length > 40) throw new ComposerError(409, 'Draft revision exhausted; the draft was not changed.')
        if (root) {
          const scope = { userId: owner, conversationId: target, rootId: root }
          await tx.threadDraftState.upsert({ where: { userId_conversationId_rootId: scope }, create: { ...scope, draft: text, revision }, update: { draft: text, revision } })
        } else await tx.conversationParticipant.update({ where: { id: current.member.id }, data: { draft: text || null, draftRevision: revision }, select: { id: true } })
      }
    }
    const next = (await snapshot(tx, owner, kind, target, root, clock())).wire
    await tx.composerOperation.create({ data: { userId: owner, clientId: intent.clientId, kind, targetId: target, rootId: root,
      requestHash, revision: next.revision, outcome } })
    return { ...next, clientId: intent.clientId, outcome, changed: outcome === 'applied' }
  }, { isolationLevel: 'Serializable' })
}

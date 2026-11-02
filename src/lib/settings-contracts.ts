import type { Prisma } from '../../prisma/generated-client'
import { opHash } from './section10-contracts.ts'

// Section 13 shared contracts - settings, profile, drafts, and the delivery
// guarantees behind them. Composed from the same primitives as earlier
// sections (durable operation identities, per-user revisions, LWW watermarks):
//
//  - SettingsOperation: one durable write identity per settings/profile
//    patch, keyed (kind, clientId). A request that commits after the client
//    timed out replays its STORED result; the same clientId with different
//    instructions is a 409 (item 2 / item 9).
//  - SettingsRevision: per-user monotonic counter bumped by every accepted
//    settings patch, carried in every GET and write ACK (item 7).
//  - SettingsFieldStamp: per-field last-write-wins ledger resolving
//    conflicting writes from different devices by explicit intent time
//    instead of arrival order (item 1).
//
// The identity/checks mirror IntegrationOperation so clients reuse ONE
// journal replay pattern across sections 10-13.

export type SettingsOpKind = 'settings.patch' | 'profile.patch'

export type SettingsOpArgs = {
  kind: SettingsOpKind
  clientId: string
  userId: string
  identity: unknown[]
}

export type SettingsOpProbe<T> =
  | { status: 'fresh' }
  | { status: 'replay'; result: T; userId: string }
  | { status: 'mismatch' }

/**
 * Look up a prior write identity. 'fresh' means the caller executes its
 * write and MUST settle the op afterwards; 'replay' carries the stored
 * result of the original commit; 'mismatch' means the identity was reused
 * with different instructions and must be refused.
 */
export async function probeSettingsOp<T>(
  tx: Prisma.TransactionClient,
  args: SettingsOpArgs,
): Promise<SettingsOpProbe<T>> {
  const prior = await tx.settingsOperation.findUnique({
    where: { kind_clientId: { kind: args.kind, clientId: args.clientId } },
    select: { userId: true, requestHash: true, resultJson: true },
  })
  if (!prior) return { status: 'fresh' }
  if (prior.userId !== args.userId || prior.requestHash !== opHash(args.identity)) {
    return { status: 'mismatch' }
  }
  return { status: 'replay', result: JSON.parse(prior.resultJson) as T, userId: prior.userId }
}

/**
 * Persist the write identity with the exact response the first attempt
 * returned. Prefer calling INSIDE the tx that performs the write. A rare
 * concurrent duplicate (unique kind+clientId) re-reads the winner's row so
 * BOTH callers answer with the same receipt.
 */
export async function settleSettingsOp<T>(
  tx: Prisma.TransactionClient,
  args: SettingsOpArgs,
  result: T,
): Promise<{ result: T; replayed: boolean }> {
  try {
    await tx.settingsOperation.create({ data: {
      kind: args.kind,
      clientId: args.clientId,
      userId: args.userId,
      requestHash: opHash(args.identity),
      resultJson: JSON.stringify(result),
    } })
    return { result, replayed: false }
  } catch (error) {
    if ((error as { code?: string })?.code !== 'P2002') throw error
    const probe = await probeSettingsOp<T>(tx, args)
    if (probe.status === 'replay') return { result: probe.result, replayed: true }
    throw error
  }
}

/** Bump the user's settings revision inside the caller's tx (or standalone). */
export async function bumpSettingsRevision(
  tx: Prisma.TransactionClient | Prisma.PrismaClient,
  userId: string,
): Promise<number> {
  const row = await tx.settingsRevision.upsert({
    where: { userId },
    update: { rev: { increment: 1 } },
    create: { userId, rev: 1 },
  })
  return row.rev
}

/** Current revision without writing (GET path). */
export async function readSettingsRevision(
  db: Prisma.TransactionClient | Prisma.PrismaClient,
  userId: string,
): Promise<number> {
  const row = await db.settingsRevision.findUnique({ where: { userId }, select: { rev: true } })
  return row?.rev ?? 0
}

export type FieldIntent = { field: string; intentAt: Date }

/**
 * Resolve which patch fields WIN against the per-field LWW ledger (item 1):
 * a field is superseded when the stored stamp carries a STRICTLY newer
 * intent time than this write's (ties go to the caller so an honest retry
 * converges). Returns the accepted fields plus the superseded names so the
 * ACK can tell the device which local intents lost and must adopt server
 * truth.
 */
export async function resolveSettingsFields(
  tx: Prisma.TransactionClient,
  userId: string,
  intents: FieldIntent[],
): Promise<{ accepted: FieldIntent[]; superseded: string[] }> {
  if (intents.length === 0) return { accepted: [], superseded: [] }
  const stamps = await tx.settingsFieldStamp.findMany({
    where: { userId, field: { in: intents.map(i => i.field) } },
    select: { field: true, intentAt: true },
  })
  const stampOf = new Map(stamps.map(s => [s.field, s.intentAt.getTime()]))
  const accepted: FieldIntent[] = []
  const superseded: string[] = []
  for (const intent of intents) {
    const prior = stampOf.get(intent.field)
    if (prior !== undefined && prior > intent.intentAt.getTime()) superseded.push(intent.field)
    else accepted.push(intent)
  }
  return { accepted, superseded }
}

/**
 * Stamp the accepted fields with their winning intent times INSIDE the
 * caller's tx, right after the value write they vouch for.
 */
export async function stampSettingsFields(
  tx: Prisma.TransactionClient,
  userId: string,
  accepted: FieldIntent[],
  rev: number,
  writer: string,
): Promise<void> {
  for (const { field, intentAt } of accepted) {
    await tx.settingsFieldStamp.upsert({
      where: { userId_field: { userId, field } },
      update: { intentAt, rev, writer },
      create: { userId, field, intentAt, rev, writer },
    })
  }
}

export type SettingsFieldStampView = { rev: number; intentAt: string; writer: string }

/** Full stamp map for the GET response (device-side conflict triage). */
export async function readSettingsFieldStamps(
  db: Prisma.TransactionClient | Prisma.PrismaClient,
  userId: string,
): Promise<Record<string, SettingsFieldStampView>> {
  const rows = await db.settingsFieldStamp.findMany({
    where: { userId },
    select: { field: true, rev: true, intentAt: true, writer: true },
  })
  const out: Record<string, SettingsFieldStampView> = {}
  for (const row of rows) {
    out[row.field] = { rev: row.rev, intentAt: row.intentAt.toISOString(), writer: row.writer }
  }
  return out
}

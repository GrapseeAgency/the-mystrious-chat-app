import type { Prisma } from '../../prisma/generated-client'
import { opHash } from './section10-contracts.ts'

// Section 12 shared contracts - automations, webhooks, and the machine
// authors behind them. Composed from the same primitives as earlier
// sections (durable operation identities, room revisions, keyset pages)
// plus the new admission primitives:
//
//  - IntegrationOperation: one durable create/delete/rotate identity per
//    integration mutation, keyed (kind, clientId). A lost ACK replays the
//    STORED result; the same clientId against different instructions is a 409.
//  - IntegrationRevision: per-conversation monotonic counter bumped by every
//    accepted automation/webhook mutation in its own commit.
//  - Webhook rate window: per-token durable 20/min bucket stored on the row,
//    enforced inside the ingest Serializable tx (distributed-safe, no
//    in-memory Map to lose on restart or disagree on across instances).
//  - BotPromptLease: one in-flight LLM evaluation per conversation across
//    ALL server instances; expired leases are re-claimable.
//  - Canonical bot identity: THE machine author resolves serviceKey first,
//    then the stable handle, then the legacy display name - and the first
//    hit is stamped so every later lookup converges on one row.

export type IntegrationOpKind =
  | 'automation.create'
  | 'automation.delete'
  | 'webhook.create'
  | 'webhook.delete'
  | 'webhook.rotate'

// ── item 1: durable create/delete operation identities ──────────────

export type IntegrationOpArgs = {
  kind: IntegrationOpKind
  clientId: string
  conversationId: string
  userId: string
  identity: unknown[]
}

export type IntegrationOpProbe<T> =
  | { status: 'fresh' }
  | { status: 'replay'; result: T; conversationId: string; userId: string }
  | { status: 'mismatch' }

/**
 * Look up a prior operation identity. 'fresh' means the caller executes its
 * mutation and MUST settle the op afterwards; 'replay' carries the stored
 * result of the original commit plus the stored conversation/user so a
 * delete-replay (target row already gone) can still authorize freshly;
 * 'mismatch' means the identity was reused with different instructions and
 * must be refused.
 *
 * Pass conversationId '' when the caller cannot know it yet (token-keyed
 * deletes); replay matching then compares only userId + instruction hash.
 */
export async function probeIntegrationOp<T>(
  tx: Prisma.TransactionClient,
  args: IntegrationOpArgs,
): Promise<IntegrationOpProbe<T>> {
  const prior = await tx.integrationOperation.findUnique({
    where: { kind_clientId: { kind: args.kind, clientId: args.clientId } },
    select: { conversationId: true, userId: true, requestHash: true, resultJson: true },
  })
  if (!prior) return { status: 'fresh' }
  const sameIdentity =
    (args.conversationId === '' || prior.conversationId === args.conversationId) &&
    (args.userId === '' || prior.userId === args.userId) &&
    prior.requestHash === opHash(args.identity)
  if (!sameIdentity) return { status: 'mismatch' }
  return { status: 'replay', result: JSON.parse(prior.resultJson) as T, conversationId: prior.conversationId, userId: prior.userId }
}

/**
 * Persist the operation identity with the exact response the first attempt
 * returned. Must run in the SAME tx as the mutation it vouches for. A rare
 * concurrent duplicate (unique kind+clientId) re-reads the winner's row so
 * BOTH callers answer with the same receipt.
 */
export async function settleIntegrationOp<T>(
  tx: Prisma.TransactionClient,
  args: IntegrationOpArgs,
  resultId: string | null,
  result: T,
): Promise<{ result: T; replayed: boolean }> {
  try {
    await tx.integrationOperation.create({ data: {
      kind: args.kind,
      clientId: args.clientId,
      conversationId: args.conversationId,
      userId: args.userId,
      requestHash: opHash(args.identity),
      resultId,
      resultJson: JSON.stringify(result),
    } })
    return { result, replayed: false }
  } catch (error) {
    if ((error as { code?: string })?.code !== 'P2002') throw error
    const probe = await probeIntegrationOp<T>(tx, args)
    if (probe.status === 'replay') return { result: probe.result, replayed: true }
    throw error
  }
}

// ── item 2: management revisions ────────────────────────────────────

/** Bump the conversation's integration revision inside the caller's tx. */
export async function bumpIntegrationRevision(
  tx: Prisma.TransactionClient,
  conversationId: string,
): Promise<number> {
  const row = await tx.integrationRevision.upsert({
    where: { conversationId },
    update: { rev: { increment: 1 } },
    create: { conversationId, rev: 1 },
  })
  return row.rev
}

// ── item 5: distributed admission ───────────────────────────────────

export const AUTOMATIONS_PER_ROOM_MAX = 100
export const AUTOMATION_SCAN_MAX = 200
export const WEBHOOK_POSTS_PER_MINUTE = 20
export const WEBHOOK_WINDOW_MS = 60_000
export const BOT_LEASE_MS = 120_000

/**
 * Durable per-token rate window, evaluated inside the ingest Serializable
 * tx. The window state lives on the Webhook row itself, so every server
 * instance and every restart sees the same bucket - the old in-memory Map
 * could silently reset under deploy and let a burst through.
 */
export async function webhookRateWindow(
  tx: Prisma.TransactionClient,
  webhook: { id: string; windowStartedAt: Date | null; windowPosts: number },
  now: Date,
): Promise<{ allowed: boolean; retryAfter: number }> {
  const started = webhook.windowStartedAt
  if (!started || now.getTime() - started.getTime() >= WEBHOOK_WINDOW_MS) {
    await tx.webhook.update({ where: { id: webhook.id }, data: { windowStartedAt: now, windowPosts: 1 } })
    return { allowed: true, retryAfter: 0 }
  }
  if (webhook.windowPosts >= WEBHOOK_POSTS_PER_MINUTE) {
    const retryAfter = Math.max(1, Math.ceil((WEBHOOK_WINDOW_MS - (now.getTime() - started.getTime())) / 1000))
    return { allowed: false, retryAfter }
  }
  await tx.webhook.update({ where: { id: webhook.id }, data: { windowPosts: { increment: 1 } } })
  return { allowed: true, retryAfter: 0 }
}

/**
 * Claim the conversation's bot-prompt admission lease. False = another
 * evaluation (possibly on another server instance) currently holds it.
 * Expired leases are re-claimable, so a crashed evaluation cannot wedge
 * the room forever.
 */
export async function claimBotPromptLease(
  tx: Prisma.TransactionClient,
  conversationId: string,
  now: Date,
  leaseMs = BOT_LEASE_MS,
): Promise<boolean> {
  const existing = await tx.botPromptLease.findUnique({ where: { conversationId } })
  if (existing && existing.expiresAt > now) return false
  const expiresAt = new Date(now.getTime() + leaseMs)
  await tx.botPromptLease.upsert({
    where: { conversationId },
    update: { expiresAt },
    create: { conversationId, expiresAt },
  })
  return true
}

/** Release the lease after the evaluation settles (reply or failure). */
export async function releaseBotPromptLease(
  db: Prisma.TransactionClient | Prisma.PrismaClient,
  conversationId: string,
): Promise<void> {
  await db.botPromptLease.deleteMany({ where: { conversationId } })
}

// ── item 6: canonical bot identity ──────────────────────────────────

export const BOT_SERVICE_KEY = 'pulse.bot'
export const BOT_USERNAME = 'pulseai'
export const BOT_DISPLAY_NAME = 'Pulse AI'

/**
 * Resolve THE machine author. Precedence: stable serviceKey → stable
 * handle → legacy mutable display name (oldest row wins). The first hit is
 * stamped with the serviceKey (and the canonical handle when no row holds
 * it) inside the caller's tx, so mutable-name lookups converge on one
 * canonical row and eventually become unreachable dead branches.
 */
export async function canonicalBotId(
  tx: Prisma.TransactionClient,
): Promise<string | null> {
  const keyed = await tx.user.findUnique({
    where: { serviceKey: BOT_SERVICE_KEY },
    select: { id: true },
  })
  if (keyed) return keyed.id

  const handled = await tx.user.findUnique({
    where: { username: BOT_USERNAME },
    select: { id: true },
  })
  if (handled) {
    await tx.user.update({ where: { id: handled.id }, data: { serviceKey: BOT_SERVICE_KEY } })
    return handled.id
  }

  const legacy = await tx.user.findFirst({
    where: { name: BOT_DISPLAY_NAME },
    orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    select: { id: true },
  })
  if (legacy) {
    await tx.user.update({ where: { id: legacy.id }, data: { serviceKey: BOT_SERVICE_KEY, username: BOT_USERNAME } })
    return legacy.id
  }
  return null
}

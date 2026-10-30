import { randomUUID } from 'node:crypto'
import type { PrismaClient } from '../../prisma/generated-client'
import { deliverMessageNotification } from './message-notification.ts'
import { fanoutPush } from './push/transport.ts'

// Durable retry for LIVE message-push fanouts (Section 6).
//
// The inline first attempt stays fire-and-forget (never blocks a send). Only a
// FAILED report (transient provider/network error) or a planning error enqueues
// a LivePushOutbox row. The drain - called from the same 20s maintenance
// dispatch ticker that drains scheduled notices - re-runs the FULL planner with
// CURRENT policy before every retry, so a mute/block/leave that landed after
// the live attempt is honored at retry time instead of being outrun.

type OutboxDb = PrismaClient

export async function livePushDelivered(db: OutboxDb, messageId: string, bindingHash: string): Promise<boolean> {
  const rows = await db.$queryRaw<Array<{ messageId: string }>>`SELECT messageId FROM LivePushReceipt WHERE messageId=${messageId} AND bindingHash=${bindingHash}`
  return rows.length !== 0
}

export async function recordLivePush(db: OutboxDb, messageId: string, bindingHash: string, now: number) {
  await db.$executeRaw`INSERT OR IGNORE INTO LivePushReceipt(messageId,bindingHash,createdAtMs) VALUES(${messageId},${bindingHash},${BigInt(now)})`
}

/** Enqueue a durable retry for a live push whose provider delivery failed.
 * Idempotent per message: a message already queued is not re-enqueued. */
export async function enqueueLivePushOutbox(db: OutboxDb, messageId: string, now: number) {
  await db.$executeRaw`INSERT INTO LivePushOutbox(id,messageId,createdAtMs,nextAttemptMs)
    SELECT ${randomUUID()},${messageId},${BigInt(now)},${BigInt(now)}
    WHERE NOT EXISTS (SELECT 1 FROM LivePushOutbox WHERE messageId=${messageId} AND completedAtMs IS NULL)`
}

/**
 * LIVE push delivery with durable failure retry. The inline attempt runs
 * immediately; a failed report enqueues the outbox. Never throws.
 */
export async function deliverLivePush(db: OutboxDb, messageId: string, clock: () => number = Date.now): Promise<void> {
  try {
    await deliverMessageNotification(db, async (recipients, payload, options) => {
      const now = clock()
      const report = await fanoutPush(recipients, payload, {
        bodyFor: options.bodyFor,
        alreadyDelivered: binding => livePushDelivered(db, messageId, binding),
        recordDelivered: binding => recordLivePush(db, messageId, binding, now),
      })
      if ((report as { failed?: number } | null)?.failed) {
        await enqueueLivePushOutbox(db, messageId, now)
      }
      return report
    }, messageId, () => new Date(clock()))
  } catch {
    // A planning/transport error is also retry-worthy: the drain re-plans.
    try { await enqueueLivePushOutbox(db, messageId, clock()) } catch { /* enqueue is best-effort */ }
  }
}

/**
 * Drain due live-push retries with bounded leases and exponential backoff.
 * Each attempt re-runs the full planner (current mute/block/membership/deleted
 * state), so policy changes after the live attempt are honored at retry time.
 * A row older than 24h completes without delivery (the message is long since
 * synced; the planner would drop it anyway).
 */
export async function drainLivePushOutbox(
  db: OutboxDb,
  clock: () => number = Date.now,
): Promise<number> {
  const now = clock()
  // Receipt retention: seven days, bounded, mirroring the scheduled drain.
  const receiptCutoff = BigInt(now - 7 * 86_400_000)
  await db.$executeRaw`DELETE FROM LivePushReceipt WHERE rowid IN (
    SELECT rowid FROM LivePushReceipt WHERE createdAtMs<${receiptCutoff} ORDER BY createdAtMs LIMIT 500)`
  const candidates = await db.$queryRaw<Array<{ id: string; attempts: number }>>`
    SELECT id FROM LivePushOutbox WHERE completedAtMs IS NULL AND nextAttemptMs<=${BigInt(now)}
      AND (leaseUntilMs IS NULL OR leaseUntilMs<=${BigInt(now)}) ORDER BY nextAttemptMs,id LIMIT 10`
  let completed = 0
  for (const { id } of candidates) {
    const lease = randomUUID(), at = clock()
    const claimed = await db.$executeRaw`UPDATE LivePushOutbox SET leaseToken=${lease},leaseUntilMs=${BigInt(at + 120_000)},attempts=attempts+1
      WHERE id=${id} AND completedAtMs IS NULL AND (leaseUntilMs IS NULL OR leaseUntilMs<=${BigInt(at)})`
    if (!claimed) continue
    const rows = await db.$queryRaw<Array<{ createdAtMs: bigint; attempts: number; messageId: string }>>`
      SELECT createdAtMs,attempts,messageId FROM LivePushOutbox WHERE id=${id} AND leaseToken=${lease}`
    const row = rows[0]
    if (!row) continue
    let failed = false
    // Late retries are not useful forever; the client has synced long ago.
    if (at - Number(row.createdAtMs) >= 86_400_000) {
      await db.$executeRaw`UPDATE LivePushOutbox SET completedAtMs=${BigInt(at)},leaseToken=NULL,leaseUntilMs=NULL WHERE id=${id} AND leaseToken=${lease}`
      completed++
      continue
    }
    try {
      await deliverMessageNotification(db, async (recipients, payload, options) => {
        const report = await fanoutPush(recipients, payload, {
          bodyFor: options.bodyFor,
          alreadyDelivered: binding => livePushDelivered(db, row.messageId, binding),
          recordDelivered: binding => recordLivePush(db, row.messageId, binding, at),
        })
        if ((report as { failed?: number } | null)?.failed) failed = true
        return report
      }, row.messageId, () => new Date(at))
    } catch {
      failed = true
    }
    if (!failed) {
      const done = await db.$executeRaw`UPDATE LivePushOutbox SET completedAtMs=${BigInt(clock())},leaseToken=NULL,leaseUntilMs=NULL
        WHERE id=${id} AND leaseToken=${lease}`
      if (done) completed++
    } else {
      const wait = Math.min(900_000, 1000 * 2 ** Math.min(10, Number(row.attempts)))
      await db.$executeRaw`UPDATE LivePushOutbox SET leaseToken=NULL,leaseUntilMs=NULL,nextAttemptMs=${BigInt(clock() + wait)} WHERE id=${id} AND leaseToken=${lease}`
    }
  }
  return completed
}

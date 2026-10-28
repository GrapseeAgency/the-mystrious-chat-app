import { randomUUID } from 'node:crypto'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'

/** Must be called inside the same transaction that commits Message + sentAt. */
export async function enqueueScheduledNotice(tx: Prisma.TransactionClient, messageId: string, now: number) {
  await tx.$executeRaw`INSERT INTO ScheduledDeliveryNotice(messageId,createdAtMs,nextAttemptMs) VALUES(${messageId},${BigInt(now)},${BigInt(now)})`
}
export async function scheduledPushDelivered(db: PrismaClient, messageId: string, bindingHash: string) {
  const rows = await db.$queryRaw<Array<{ messageId: string }>>`SELECT messageId FROM ScheduledPushReceipt WHERE messageId=${messageId} AND bindingHash=${bindingHash}`
  return rows.length !== 0
}
export async function recordScheduledPush(db: PrismaClient, messageId: string, bindingHash: string) {
  await db.$executeRaw`INSERT OR IGNORE INTO ScheduledPushReceipt(messageId,bindingHash) VALUES(${messageId},${bindingHash})`
}
/** Bounded leases prevent concurrent dispatchers from fanout duplication. A
 * provider response can still be lost: stable message IDs remain the client
 * deduplication identity, not an impossible exactly-once push guarantee. */
export async function drainScheduledNotices(db: PrismaClient, socket: (id: string) => Promise<void>,
  push: (id: string, cursor: string | null) => Promise<string | null>, clock: () => number = Date.now) {
  const now = clock()
  // Completed notice metadata is not an operation receipt. After seven days it
  // can be retired without permitting a scheduled message to be created again.
  // Bound BOTH child and parent deletion work, including large device fleets.
  const cutoff = BigInt(now - 7 * 86_400_000)
  await db.$transaction(async tx => {
    await tx.$executeRaw`DELETE FROM ScheduledPushReceipt WHERE rowid IN (
      SELECT r.rowid FROM ScheduledPushReceipt r JOIN ScheduledDeliveryNotice n ON n.messageId=r.messageId
      WHERE n.completedAtMs<${cutoff} ORDER BY n.completedAtMs,n.messageId LIMIT 500)`
    await tx.$executeRaw`DELETE FROM ScheduledDeliveryNotice WHERE messageId IN (
      SELECT n.messageId FROM ScheduledDeliveryNotice n WHERE n.completedAtMs<${cutoff}
      AND NOT EXISTS (SELECT 1 FROM ScheduledPushReceipt r WHERE r.messageId=n.messageId)
      ORDER BY n.completedAtMs,n.messageId LIMIT 100)`
  })
  const candidates = await db.$queryRaw<Array<{ messageId: string }>>`
    SELECT messageId FROM ScheduledDeliveryNotice WHERE completedAtMs IS NULL AND nextAttemptMs<=${BigInt(now)}
      AND (leaseUntilMs IS NULL OR leaseUntilMs<=${BigInt(now)}) ORDER BY nextAttemptMs,messageId LIMIT 10`
  let completed = 0
  for (const { messageId } of candidates) {
    const lease = randomUUID(), at = clock()
    const claimed = await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET leaseToken=${lease},leaseUntilMs=${BigInt(at + 300_000)},attempts=attempts+1
      WHERE messageId=${messageId} AND completedAtMs IS NULL AND (leaseUntilMs IS NULL OR leaseUntilMs<=${BigInt(at)})`
    if (!claimed) continue
    const rows = await db.$queryRaw<Array<{ createdAtMs: bigint; attempts: number; socketDone: boolean; pushDone: boolean; pushCursor: string | null }>>`
      SELECT createdAtMs,attempts,socketDone,pushDone,pushCursor FROM ScheduledDeliveryNotice WHERE messageId=${messageId} AND leaseToken=${lease}`
    const row = rows[0]
    if (!row) continue
    // Late hints are not useful forever. They never change the delivered message.
    if (at - Number(row.createdAtMs) >= 86_400_000) {
      await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET completedAtMs=${BigInt(at)},leaseToken=NULL,leaseUntilMs=NULL WHERE messageId=${messageId} AND leaseToken=${lease}`
      completed++; continue
    }
    // A slow channel must never let an expired/stolen lease authorize the next
    // one. Renewal is conditional: it cannot revive ownership after expiration.
    const renew = async () => {
      const time = clock()
      return (await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET leaseUntilMs=${BigInt(time + 300_000)}
        WHERE messageId=${messageId} AND leaseToken=${lease} AND leaseUntilMs>${BigInt(time)} AND completedAtMs IS NULL`) !== 0
    }
    let failed = false
    if (!row.socketDone) {
      try {
        if (!await renew()) continue
        await socket(messageId)
        await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET socketDone=1 WHERE messageId=${messageId} AND leaseToken=${lease}`
      } catch { failed = true }
    }
    if (!row.pushDone) {
      try {
        if (!await renew()) continue
        const cursor = await push(messageId,row.pushCursor)
        await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET pushDone=${cursor === null},pushCursor=${cursor} WHERE messageId=${messageId} AND leaseToken=${lease}`
      } catch { failed = true }
    }
    const done = await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET completedAtMs=${BigInt(clock())},leaseToken=NULL,leaseUntilMs=NULL
      WHERE messageId=${messageId} AND leaseToken=${lease} AND socketDone=1 AND pushDone=1`
    if (done) completed++
    else {
      const wait = failed ? Math.min(900_000,1000 * 2 ** Math.min(10,Number(row.attempts))) : 250
      await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET leaseToken=NULL,leaseUntilMs=NULL,nextAttemptMs=${BigInt(clock()+wait)} WHERE messageId=${messageId} AND leaseToken=${lease}`
    }
  }
  return completed
}

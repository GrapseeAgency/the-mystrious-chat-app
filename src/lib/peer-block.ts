import type { PrismaClient } from '../../prisma/generated-client'

export class PeerBlockError extends Error {
  readonly status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}

export type PeerBlockReceipt = {
  ok: true
  blockReceiptVersion: 1
  actorId: string
  targetId: string
  blocked: boolean
  /** Section 8 item 6: true when a NEWER durable intent already won - this
   * write is recorded but the pair state is left untouched (no regression). */
  stale?: boolean
  /** The intent watermark this submission was judged against (ISO string). */
  intentAt?: string
}

const CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,128}$/

export type PeerBlockOptions = {
  /** Retry identity of the offline intent (durable journals replay with the
   * SAME clientId, so a lost ACK cannot double-apply or flip-flop). */
  clientId?: unknown
  /** Original client clock time of the intent (offline writes keep their
   * birth order; the server orders opposite writes by this watermark). */
  intentAt?: unknown
}

function parseOptions(options: PeerBlockOptions | undefined): { clientId: string | null; intentAt: Date | null } {
  if (!options) return { clientId: null, intentAt: null }
  let clientId: string | null = null
  if (options.clientId !== undefined && options.clientId !== null) {
    if (typeof options.clientId !== 'string' || !CLIENT_ID_RE.test(options.clientId))
      throw new PeerBlockError(400, 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.')
    clientId = options.clientId
  }
  let intentAt: Date | null = null
  if (options.intentAt !== undefined && options.intentAt !== null) {
    if (typeof options.intentAt !== 'string' || Number.isNaN(Date.parse(options.intentAt)))
      throw new PeerBlockError(400, 'intentAt must be an ISO-8601 timestamp.')
    intentAt = new Date(options.intentAt)
  }
  if (clientId && !intentAt) throw new PeerBlockError(400, 'clientId requires intentAt.')
  if (intentAt && !clientId) throw new PeerBlockError(400, 'intentAt requires clientId.')
  return { clientId, intentAt }
}

/** Read and desired-state writes share one pair snapshot. Authentication belongs
 * to the route; neither a missing row nor a failed write is an unblock receipt.
 * Section 8 item 6: with a durable intent (clientId + intentAt) the write joins
 * a total order - the newest intentAt wins, older opposite intents are recorded
 * but stale, and the same intent replays to the SAME receipt. Legacy callers
 * (no intent) keep the old immediate-apply behavior and are stamped server-now. */
export async function peerBlock(
  db: PrismaClient, actorId: string, targetId: string, desired: boolean | null,
  options?: PeerBlockOptions,
): Promise<PeerBlockReceipt> {
  if (desired !== null && actorId === targetId) throw new PeerBlockError(400, 'You cannot block yourself.')
  const { clientId, intentAt } = parseOptions(options)
  try {
    return await db.$transaction(async tx => {
      const users = await tx.user.findMany({ where: { id: { in: [actorId, targetId] } }, select: { id: true } })
      if (!users.some(user => user.id === actorId) || !users.some(user => user.id === targetId))
        throw new PeerBlockError(404, 'Account not found.')
      const pair = { blockerId: actorId, blockedId: targetId }
      let stale = false
      let replayed = false
      if (desired !== null) {
        const stamp = intentAt ?? new Date()
        const prior = clientId
          ? await tx.peerBlockIntent.findUnique({
              where: { blockerId_blockedId_clientId: { blockerId: actorId, blockedId: targetId, clientId } },
              select: { desired: true, intentAt: true },
            })
          : null
        if (prior) {
          // Section 8 item 6 replay: the SAME intent re-lands after a lost ACK.
          // It must not re-apply or collide - it re-derives its receipt from
          // the intents that actually won.
          if (prior.desired !== desired || prior.intentAt.getTime() !== stamp.getTime())
            throw new PeerBlockError(409, 'This block intent ID was already used for a different intent.')
          replayed = true
        } else {
          // Every applied write is journaled so later offline replays can be
          // ordered against it - including writes from legacy callers.
          await tx.peerBlockIntent.create({
            data: { blockerId: actorId, blockedId: targetId, clientId: clientId ?? `legacy-${stamp.getTime().toString(36)}`, desired, intentAt: stamp },
          })
        }
        // Late opposite write: a DIFFERENT intent with a strictly newer
        // watermark already won. Record this one, change nothing.
        const newer = await tx.peerBlockIntent.findFirst({
          where: { blockerId: actorId, blockedId: targetId, intentAt: { gt: prior?.intentAt ?? stamp }, ...(clientId ? { NOT: { clientId } } : {}) },
          select: { clientId: true },
        })
        if (newer) stale = true
        if (!stale && !replayed) {
          if (desired === true) {
            await tx.userBlock.upsert({
              where: { blockerId_blockedId: pair },
              create: { ...pair, lastIntentAt: stamp, lastClientId: clientId },
              update: { lastIntentAt: stamp, lastClientId: clientId },
            })
          } else {
            await tx.userBlock.deleteMany({ where: pair })
          }
        }
      }
      const row = await tx.userBlock.findUnique({ where: { blockerId_blockedId: pair }, select: { id: true } })
      // Legacy receipts keep their exact historical shape; the intent fields
      // appear only when the caller journaled a durable intent.
      return {
        ok: true, blockReceiptVersion: 1 as const, actorId, targetId, blocked: row !== null,
        ...(clientId && desired !== null ? { stale, replayed, intentAt: (intentAt ?? new Date()).toISOString() } : {}),
      }
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2002', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      throw new PeerBlockError(409, 'The block state changed or is busy. Refresh before retrying.')
    throw error
  }
}

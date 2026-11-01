import { preserveJoiningHistory } from './membership-history.ts'
import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { opHash, decodeKeysetCursor, pageLimit } from './section10-contracts.ts'
import { MATRIX } from './hub-catalog.ts'

export class HubAppError extends Error {
  constructor(public readonly status: number, message: string, public readonly code?: string, public readonly clientId?: string) { super(message) }
}
export function hubAppErrorResponse(error: unknown): Response {
  if (error instanceof HubAppError) return Response.json({ error: error.message, code: error.code, clientId: error.clientId }, { status: error.status })
  if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
    return Response.json({ error: 'App state changed or is busy. Refresh and retry the same action.' }, { status: 409 })
  }
  throw error
}
const profile = { id: true, name: true, username: true, color: true } as const
function transaction<T>(db: PrismaClient, action: (tx: Prisma.TransactionClient) => Promise<T>) {
  return db.$transaction(action, { isolationLevel: 'Serializable' })
}

/** Section 11 item 8 — the user's current app-connection revision (0 when never bumped). */
export async function readInstallRev(tx: Pick<Prisma.TransactionClient, '$queryRaw'>, userId: string): Promise<number> {
  const rows = await tx.$queryRaw<Array<{ rev: number }>>`
    SELECT rev FROM AppInstallRevision WHERE userId = ${userId}`
  return rows[0]?.rev ?? 0
}

async function bumpInstallRev(tx: Prisma.TransactionClient, userId: string): Promise<number> {
  await tx.$executeRaw`INSERT INTO AppInstallRevision (userId, rev) VALUES (${userId}, 1)
    ON CONFLICT (userId) DO UPDATE SET rev = rev + 1`
  return readInstallRev(tx, userId)
}

async function installSnapshot(tx: Prisma.TransactionClient, appId: string, userId: string | null) {
  const where = { appId, status: 'connected' }
  const installs = await tx.appInstall.count({ where })
  // Do not fetch every connector just to count them or locate the viewer.
  const recent = await tx.appInstall.findMany({ where, take: 6,
    orderBy: [{ installedAt: 'desc' }, { id: 'desc' }], select: { user: { select: profile } } })
  const mine = userId ? await tx.appInstall.findUnique({ where: { userId_appId: { userId, appId } },
    select: { status: true, installedAt: true } }) : null
  const installed = mine?.status === 'connected'
  const installsRev = userId ? await readInstallRev(tx, userId) : null
  return { appVersion: 1, appId, userId, installed, status: installed ? 'connected' : null,
    installedAt: installed ? mine.installedAt.toISOString() : null, installs, installers: recent.map(row => row.user),
    installsRev }
}

export function appInstallSnapshot(db: PrismaClient, appId: string, userId: string | null) {
  return transaction(db, tx => installSnapshot(tx, appId, userId))
}

/**
 * Section 11 item 6 — one batch snapshot instead of one request per
 * installed app. Every catalog id comes back with its connected flag, so an
 * installation scan costs ONE request regardless of how many apps exist.
 */
export function appInstallsBatchSnapshot(db: PrismaClient, userId: string) {
  return transaction(db, async tx => {
    const rows = await tx.appInstall.findMany({
      where: { userId, status: 'connected' },
      select: { appId: true, installedAt: true },
    })
    const byApp = new Map(rows.map(row => [row.appId, row.installedAt.toISOString()]))
    return {
      appVersion: 2, userId,
      installsRev: await readInstallRev(tx, userId),
      apps: Object.entries(CATALOG_IDS).map(([appId, order]) => ({
        appId, order,
        installed: byApp.has(appId),
        installedAt: byApp.get(appId) ?? null,
      })),
    }
  })
}

/** Catalog ids the server resolves (kept in one place for the batch scan). */
const CATALOG_IDS: Record<string, number> = Object.fromEntries(
  MATRIX.map((app, index) => [String(app.n), index]),
)

/**
 * Section 11 item 8 — connect/disconnect with a durable replay receipt.
 * A replay (operation row already committed) is an ACKNOWLEDGEMENT, not a
 * new action: the current snapshot is returned untouched so a late retry can
 * never override another device's newer toggle. Fresh actions bump the
 * user's install revision inside the same commit as the state change.
 */
export function setAppInstalled(db: PrismaClient, appId: string, userId: string, installed: boolean,
  clientId?: string | null) {
  return transaction(db, async tx => {
    if (clientId) {
      const requestHash = opHash([userId, appId, installed])
      const existing = await tx.appConnectionOperation.findUnique({
        where: { userId_clientId: { userId, clientId } }, select: { requestHash: true } })
      if (existing) {
        if (existing.requestHash !== requestHash) {
          throw new HubAppError(409, 'This connection identity was already used with different instructions.', 'identity_clash', clientId)
        }
        return { ...await installSnapshot(tx, appId, userId), replayed: true, clientId }
      }
    }
    if (installed) await tx.appInstall.upsert({ where: { userId_appId: { userId, appId } },
      create: { userId, appId, status: 'connected' }, update: { status: 'connected' }, select: { id: true } })
    else await tx.appInstall.deleteMany({ where: { userId, appId } })
    if (clientId) {
      await tx.appConnectionOperation.create({ data: {
        userId, clientId, requestHash: opHash([userId, appId, installed]), appId, installed } })
    }
    const installsRev = await bumpInstallRev(tx, userId)
    return { ...await installSnapshot(tx, appId, userId), installsRev, replayed: false, clientId: clientId ?? null }
  })
}

/** Historical alias keys ("04", "1e0", "4.0") reconcile onto the one
 * canonical app-number conversation instead of splitting into ghost rooms:
 * exact/canonical spelling first, then a bounded legacy scan adopts a
 * numeric-equal group room by RE-KEYING it to the canonical id (Section 11
 * item 9). The re-key happens inside the caller's Serializable tx, so a
 * concurrent adopt is a 409, never a duplicate room. */
function canonicalAppKey(appId: string): string[] {
  const numeric = Number(appId)
  const canonical = Number.isSafeInteger(numeric) && numeric > 0 ? String(numeric) : appId
  return canonical === appId ? [appId] : [appId, canonical]
}

async function findCommunityRoom(tx: Prisma.TransactionClient, appId: string) {
  const exact = await tx.conversation.findFirst({ where: { appKey: { in: canonicalAppKey(appId) } }, select: {
    id: true, isGroup: true, appKey: true, name: true, photo: true, createdAt: true, updatedAt: true,
    _count: { select: { participants: true } },
  } })
  if (exact) return exact
  const numeric = Number(appId)
  if (!Number.isSafeInteger(numeric) || numeric <= 0) return null
  const candidates = await tx.conversation.findMany({
    where: { isGroup: true, appKey: { not: null } },
    select: { id: true, appKey: true, isGroup: true, name: true, photo: true, createdAt: true, updatedAt: true,
      _count: { select: { participants: true } } },
    take: 500,
  })
  const legacy = candidates.find(row => {
    if (!row.appKey || canonicalAppKey(appId).includes(row.appKey)) return false
    const value = Number(row.appKey)
    return Number.isFinite(value) && value === numeric
  })
  if (!legacy) return null
  return tx.conversation.update({ where: { id: legacy.id }, data: { appKey: appId }, select: {
    id: true, isGroup: true, appKey: true, name: true, photo: true, createdAt: true, updatedAt: true,
    _count: { select: { participants: true } },
  } })
}

async function communitySnapshot(tx: Prisma.TransactionClient, appId: string, userId: string | null,
  paging?: { limit: number; cursor: { atMs: number; id: string } | null }) {
  const room = await findCommunityRoom(tx, appId)
  const base = { appVersion: 1, appId, userId }
  if (!room) return { ...base, conversation: null, memberCount: 0, joined: false }
  if (!room.isGroup) throw new HubAppError(409, 'The app community is not a group. Refresh before joining.')
  const mine = userId ? await tx.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: room.id } }, select: { id: true },
  }) : null
  // The directory is not a back door into room details: no invite codes,
  // drafts, read markers, streaks or messages, even for an existing member.
  // Nonmembers get public identity/count only, never the private roster.
  let members: Array<{ role: string; joinedAt: Date; user: { id: string; name: string; username: string | null; color: string } }> = []
  let hasMore = false
  let nextCursor: string | null = null
  if (mine) {
    const where: Prisma.ConversationParticipantWhereInput = { conversationId: room.id }
    if (paging?.cursor) {
      const at = new Date(paging.cursor.atMs)
      where.OR = [{ joinedAt: { gt: at } }, { joinedAt: at, id: { gt: paging.cursor.id } }]
    }
    const rows = await tx.conversationParticipant.findMany({ where,
      orderBy: [{ joinedAt: 'asc' }, { id: 'asc' }],
      ...(paging ? { take: paging.limit + 1 } : {}),
      select: { role: true, joinedAt: true, id: true, user: { select: profile } },
    })
    if (paging) {
      hasMore = rows.length > paging.limit
      const page = hasMore ? rows.slice(0, paging.limit) : rows
      const last = page.at(-1)
      nextCursor = hasMore && last ? `${last.joinedAt.getTime()}.${last.id}` : null
      members = page
    } else {
      members = rows
    }
  }
  return { ...base, conversation: { id: room.id, isGroup: true, name: room.name, photo: room.photo,
    createdAt: room.createdAt.toISOString(), updatedAt: room.updatedAt.toISOString(),
    members: members.map(row => ({ ...row.user, role: row.role, joinedAt: row.joinedAt.toISOString() })) },
    memberCount: room._count.participants, joined: Boolean(mine),
    ...(paging ? { rosterHasMore: hasMore, rosterNextCursor: nextCursor } : {}) }
}

export function appCommunitySnapshot(db: PrismaClient, appId: string, userId: string | null,
  rawLimit?: string | null, rawCursor?: string | null) {
  return transaction(db, async tx => {
    if ((rawLimit === undefined || rawLimit === null) && (rawCursor === undefined || rawCursor === null)) {
      return communitySnapshot(tx, appId, userId)
    }
    const limitParsed = pageLimit(rawLimit ?? null, 100)
    if (!limitParsed.ok) throw new HubAppError(400, 'limit must be an integer between 1 and 100.')
    const cursorParsed = decodeKeysetCursor(rawCursor ?? null)
    if (!cursorParsed.ok) throw new HubAppError(400, 'cursor is invalid.')
    const limit = limitParsed.ok ? limitParsed.limit ?? 50 : 50
    const cursor = cursorParsed.ok && 'atMs' in cursorParsed && cursorParsed.atMs !== undefined
      ? { atMs: cursorParsed.atMs, id: cursorParsed.id } : null
    return communitySnapshot(tx, appId, userId, { limit, cursor })
  })
}

export function joinAppCommunity(db: PrismaClient, appId: string, name: string, userId: string) {
  return transaction(db, async tx => {
    const now = new Date()
    const room = await findCommunityRoom(tx, appId) ?? await tx.conversation.create({ data: { appKey: appId, name, isGroup: true,
      participants: { create: { userId, role: 'admin', joinedAt: now, lastReadAt: now } } }, select: {
        id: true, isGroup: true, appKey: true, name: true, photo: true, createdAt: true, updatedAt: true,
        _count: { select: { participants: true } } } })
    if (!room.isGroup) throw new HubAppError(409, 'The app community is not a group.')
    const existing = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: room.id } }, select: { id: true },
    })
    if (!existing) {
      await tx.conversationParticipant.create({ data: {
        userId, conversationId: room.id, role: 'member', joinedAt: now, lastReadAt: now,
      }, select: { id: true } })
      await preserveJoiningHistory(tx, room.id, [userId], now)
    }
    return communitySnapshot(tx, appId, userId)
  })
}

import type { Prisma, PrismaClient } from '../../prisma/generated-client'

export class HubAppError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export function hubAppErrorResponse(error: unknown): Response {
  if (error instanceof HubAppError) return Response.json({ error: error.message }, { status: error.status })
  if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
    return Response.json({ error: 'App state changed or is busy. Refresh and retry the same action.' }, { status: 409 })
  }
  throw error
}
const profile = { id: true, name: true, username: true, color: true } as const
function transaction<T>(db: PrismaClient, action: (tx: Prisma.TransactionClient) => Promise<T>) {
  return db.$transaction(action, { isolationLevel: 'Serializable' })
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
  return { appVersion: 1, appId, userId, installed, status: installed ? 'connected' : null,
    installedAt: installed ? mine.installedAt.toISOString() : null, installs, installers: recent.map(row => row.user) }
}
export function appInstallSnapshot(db: PrismaClient, appId: string, userId: string | null) {
  return transaction(db, tx => installSnapshot(tx, appId, userId))
}
export function setAppInstalled(db: PrismaClient, appId: string, userId: string, installed: boolean) {
  return transaction(db, async tx => {
    if (installed) await tx.appInstall.upsert({ where: { userId_appId: { userId, appId } },
      create: { userId, appId, status: 'connected' }, update: { status: 'connected' }, select: { id: true } })
    else await tx.appInstall.deleteMany({ where: { userId, appId } })
    return installSnapshot(tx, appId, userId)
  })
}
async function communitySnapshot(tx: Prisma.TransactionClient, appId: string, userId: string | null) {
  const room = await tx.conversation.findUnique({ where: { appKey: appId }, select: {
    id: true, isGroup: true, name: true, photo: true, createdAt: true, updatedAt: true,
    _count: { select: { participants: true } },
  } })
  const base = { appVersion: 1, appId, userId }
  if (!room) return { ...base, conversation: null, memberCount: 0, joined: false }
  if (!room.isGroup) throw new HubAppError(409, 'The app community is not a group. Refresh before joining.')
  const mine = userId ? await tx.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: room.id } }, select: { id: true },
  }) : null
  // The directory is not a back door into room details: no invite codes,
  // drafts, read markers, streaks or messages, even for an existing member.
  // Nonmembers get public identity/count only, never the private roster.
  const members = mine ? await tx.conversationParticipant.findMany({ where: { conversationId: room.id },
    orderBy: [{ joinedAt: 'asc' }, { id: 'asc' }],
    select: { role: true, joinedAt: true, user: { select: profile } },
  }) : []
  return { ...base, conversation: { id: room.id, isGroup: true, name: room.name, photo: room.photo,
    createdAt: room.createdAt.toISOString(), updatedAt: room.updatedAt.toISOString(),
    members: members.map(row => ({ ...row.user, role: row.role, joinedAt: row.joinedAt.toISOString() })) },
    memberCount: room._count.participants, joined: Boolean(mine) }
}
export function appCommunitySnapshot(db: PrismaClient, appId: string, userId: string | null) {
  return transaction(db, tx => communitySnapshot(tx, appId, userId))
}
export function joinAppCommunity(db: PrismaClient, appId: string, name: string, userId: string) {
  return transaction(db, async tx => {
    let room = await tx.conversation.findUnique({ where: { appKey: appId }, select: { id: true, isGroup: true } })
    if (!room) room = await tx.conversation.create({ data: { appKey: appId, name, isGroup: true,
      participants: { create: { userId, role: 'admin' } } }, select: { id: true, isGroup: true } })
    if (!room.isGroup) throw new HubAppError(409, 'The app community is not a group.')
    await tx.conversationParticipant.upsert({ where: { userId_conversationId: { userId, conversationId: room.id } },
      create: { userId, conversationId: room.id, role: 'member' }, update: {}, select: { id: true } })
    return communitySnapshot(tx, appId, userId)
  })
}

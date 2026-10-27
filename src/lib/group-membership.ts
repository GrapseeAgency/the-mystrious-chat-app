import type { Prisma, PrismaClient } from '../../prisma/generated-client'
import { preserveJoiningHistory } from './membership-history.ts'

export class GroupMembershipError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly missing?: string[],
  ) {
    super(message)
  }
}

async function transaction<T>(
  db: PrismaClient,
  action: (tx: Prisma.TransactionClient) => Promise<T>,
): Promise<T> {
  try {
    return await db.$transaction(action, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034', 'P2002'].includes((error as { code?: string })?.code ?? '')) {
      throw new GroupMembershipError(
        409,
        'Membership changed or is busy. Refresh and retry the same action.',
      )
    }
    throw error
  }
}

async function group(tx: Prisma.TransactionClient, room: string, channelOnly = false) {
  const row = await tx.conversation.findUnique({
    where: { id: room },
    select: { id: true, isGroup: true, broadcastMode: true },
  })
  if (!row)
    throw new GroupMembershipError(
      404,
      channelOnly ? 'Channel not found.' : 'Conversation not found.',
    )
  if (!row.isGroup || (channelOnly && !row.broadcastMode)) {
    throw new GroupMembershipError(
      400,
      channelOnly
        ? 'This conversation is not a broadcast channel.'
        : 'Direct conversations have no group membership actions.',
    )
  }
  return row
}

function participant(tx: Prisma.TransactionClient, room: string, userId: string) {
  return tx.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: room } },
    select: { id: true, role: true },
  })
}
async function admin(tx: Prisma.TransactionClient, room: string, actor: string) {
  const row = await participant(tx, room, actor)
  if (!row || row.role !== 'admin')
    throw new GroupMembershipError(403, 'Only current group admins can manage members.')
  return row
}
async function recipients(tx: Prisma.TransactionClient, room: string) {
  return (
    await tx.conversationParticipant.findMany({
      where: { conversationId: room },
      select: { userId: true },
    })
  ).map((row) => row.userId)
}

/** Permission, target role and last-admin checks share the same write snapshot. */
export function setGroupRole(
  db: PrismaClient,
  room: string,
  actor: string,
  targetId: string,
  role: 'admin' | 'member',
) {
  return transaction(db, async (tx) => {
    await group(tx, room)
    const requester = await participant(tx, room, actor)
    if (!requester)
      throw new GroupMembershipError(403, 'You are not a participant of this conversation.')
    const target = await participant(tx, room, targetId)
    // A lost self-demotion acknowledgement can be confirmed without restoring admin power.
    const ownCompletedDemotion =
      actor === targetId && role === 'member' && requester.role === 'member'
    if (requester.role !== 'admin' && !ownCompletedDemotion)
      throw new GroupMembershipError(403, 'Only current group admins can change roles.')
    if (!target) throw new GroupMembershipError(404, 'That user is not a member of this group.')
    const changed = target.role !== role
    if (changed) {
      if (role === 'member') {
        const others = await tx.conversationParticipant.count({
          where: { conversationId: room, role: 'admin', NOT: { id: target.id } },
        })
        if (others === 0)
          throw new GroupMembershipError(
            400,
            'Cannot demote the last admin - promote someone else first.',
          )
      }
      await tx.conversationParticipant.update({
        where: { id: target.id },
        data: { role },
        select: { id: true },
      })
      await tx.conversation.update({
        where: { id: room },
        data: { updatedAt: new Date() },
        select: { id: true },
      })
    }
    return { changed, recipients: await recipients(tx, room) }
  })
}

export function removeGroupMember(db: PrismaClient, room: string, actor: string, targetId: string) {
  return transaction(db, async (tx) => {
    await group(tx, room)
    await admin(tx, room, actor)
    if (actor === targetId)
      throw new GroupMembershipError(400, 'Use "Leave group" to remove yourself from this chat.')
    const target = await participant(tx, room, targetId)
    if (target?.role === 'admin')
      throw new GroupMembershipError(403, 'Admins cannot be removed from the group.')
    if (target)
      await tx.conversationParticipant.delete({ where: { id: target.id }, select: { id: true } })
    // Repeat-safe only for a still-authorized admin, not for arbitrary former members.
    return { changed: target !== null, recipients: await recipients(tx, room) }
  })
}

export function addGroupMembers(
  db: PrismaClient,
  room: string,
  actor: string,
  requested: string[],
) {
  return transaction(db, async (tx) => {
    await group(tx, room)
    await admin(tx, room, actor)
    const ids = [...new Set(requested.map((id) => id.trim()).filter(Boolean))]
    if (!ids.length)
      throw new GroupMembershipError(400, 'userIds must contain at least one user id.')
    const known = new Set(
      (await tx.user.findMany({ where: { id: { in: ids } }, select: { id: true } })).map(
        (user) => user.id,
      ),
    )
    const missing = ids.filter((id) => !known.has(id))
    if (missing.length) throw new GroupMembershipError(400, 'Unknown user id in userIds.', missing)
    const existing = new Set(await recipients(tx, room))
    const added = ids.filter((id) => !existing.has(id))
    if (added.length) {
      const now = new Date()
      await tx.conversationParticipant.createMany({
        data: added.map((userId) => ({
          userId,
          conversationId: room,
          role: 'member',
          joinedAt: now,
          lastReadAt: now,
        })),
      })
      await preserveJoiningHistory(tx, room, added, now)
      await tx.conversation.update({
        where: { id: room },
        data: { updatedAt: now },
        select: { id: true },
      })
    }
    return { added, recipients: await recipients(tx, room) }
  })
}

export function joinChannel(db: PrismaClient, room: string, actor: string) {
  return transaction(db, async (tx) => {
    await group(tx, room, true)
    if (!(await tx.user.findUnique({ where: { id: actor }, select: { id: true } })))
      throw new GroupMembershipError(404, 'Unknown user.')
    const existing = await participant(tx, room, actor)
    if (!existing) {
      const now = new Date()
      await tx.conversationParticipant.create({
        data: { userId: actor, conversationId: room, role: 'member', joinedAt: now, lastReadAt: now },
        select: { id: true },
      })
      await preserveJoiningHistory(tx, room, [actor], now)
      await tx.conversation.update({
        where: { id: room },
        data: { updatedAt: now },
        select: { id: true },
      })
    }
    return { already: existing !== null, recipients: await recipients(tx, room) }
  })
}

export function leaveGroup(db: PrismaClient, room: string, actor: string, channelOnly = false) {
  return transaction(db, async (tx) => {
    const conversation = await group(tx, room, channelOnly)
    const leaver = await participant(tx, room, actor)
    if (!leaver)
      throw new GroupMembershipError(
        channelOnly ? 404 : 403,
        'You are not a participant of this conversation.',
      )
    let promotedUserId: string | null = null
    if (leaver.role === 'admin') {
      const others = await tx.conversationParticipant.count({
        where: { conversationId: room, role: 'admin', NOT: { id: leaver.id } },
      })
      if (others === 0) {
        // The generic group endpoint must not bypass the channel succession rule.
        if (conversation.broadcastMode)
          throw new GroupMembershipError(
            403,
            'You are the last admin of this channel - promote another admin ("Make admin" on the channel info) before leaving.',
          )
        const successor = await tx.conversationParticipant.findFirst({
          where: { conversationId: room, NOT: { id: leaver.id } },
          orderBy: [{ joinedAt: 'asc' }, { id: 'asc' }],
          select: { id: true, userId: true },
        })
        if (successor) {
          await tx.conversationParticipant.update({
            where: { id: successor.id },
            data: { role: 'admin' },
            select: { id: true },
          })
          promotedUserId = successor.userId
        }
      }
    }
    await tx.conversationParticipant.delete({ where: { id: leaver.id }, select: { id: true } })
    return { promotedUserId, recipients: await recipients(tx, room) }
  })
}

/** Lazy creation must converge; a preflight read must not mint several dead links. */
export function groupInvite(
  db: PrismaClient,
  room: string,
  actor: string,
  regenerate: boolean,
  mint: () => string,
) {
  return transaction(db, async (tx) => {
    await group(tx, room)
    await admin(tx, room, actor)
    const current = await tx.conversation.findUniqueOrThrow({
      where: { id: room },
      select: { inviteCode: true },
    })
    let code = current.inviteCode
    if (regenerate || !code) {
      for (let attempt = 0; attempt < 5; attempt++) {
        const candidate = mint()
        if (/^[A-Z0-9]{4,16}$/.test(candidate) && candidate !== current.inviteCode) {
          code = candidate
          break
        }
      }
      if (!code || code === current.inviteCode)
        throw new GroupMembershipError(503, 'Could not generate a new invite link. Try again.')
      await tx.conversation.update({
        where: { id: room },
        data: { inviteCode: code },
        select: { id: true },
      })
    }
    return {
      inviteCode: code,
      changed: code !== current.inviteCode,
      recipients: await recipients(tx, room),
    }
  })
}

/** Redemption and rotation both participate in SQLite's serializable write ordering. */
export function joinGroupInvite(db: PrismaClient, rawCode: string, actor: string) {
  const code = rawCode.trim().toUpperCase()
  if (!/^[A-Z0-9]{4,16}$/.test(code))
    return Promise.reject(new GroupMembershipError(400, 'Invalid invite code.'))
  return transaction(db, async (tx) => {
    const conversation = await tx.conversation.findUnique({
      where: { inviteCode: code },
      select: { id: true, isGroup: true },
    })
    if (!conversation?.isGroup)
      throw new GroupMembershipError(404, 'This invite link is no longer valid.')
    if (!(await tx.user.findUnique({ where: { id: actor }, select: { id: true } })))
      throw new GroupMembershipError(404, 'Unknown user.')
    const existing = await participant(tx, conversation.id, actor)
    if (!existing) {
      const now = new Date()
      await tx.conversationParticipant.create({
        data: { userId: actor, conversationId: conversation.id, role: 'member', joinedAt: now, lastReadAt: now },
        select: { id: true },
      })
      await preserveJoiningHistory(tx, conversation.id, [actor], now)
      await tx.conversation.update({
        where: { id: conversation.id },
        data: { updatedAt: now },
        select: { id: true },
      })
    }
    return {
      conversationId: conversation.id,
      alreadyMember: existing !== null,
      recipients: await recipients(tx, conversation.id),
    }
  })
}

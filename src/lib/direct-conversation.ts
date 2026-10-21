import type { PrismaClient } from '../../prisma/generated-client'

export class DirectConversationError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

/** Check membership and create in ONE serializable transaction, not two requests. */
export async function openDirectConversation(db: PrismaClient, ids: string[]) {
  const members = [...new Set(ids)].sort()
  if (members.length !== 2) throw new DirectConversationError(400, 'A direct conversation needs two distinct members.')
  for (let attempt = 0; ; attempt++) {
    try {
      return await db.$transaction(async tx => {
        const candidates = await tx.conversation.findMany({
          where: { isGroup: false, isSelf: false, participants: { some: { userId: members[0] } } },
          orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
          select: { id: true, participants: { select: { userId: true } } },
        })
        const existing = candidates.find(c => c.participants.length === 2 &&
          members.every(id => c.participants.some(p => p.userId === id)))
        // Blocked history stays accessible; the message-send gate still applies.
        if (existing) return { id: existing.id, created: false }
        const blocked = await tx.userBlock.findFirst({ where: { OR: [
          { blockerId: members[0], blockedId: members[1] },
          { blockerId: members[1], blockedId: members[0] },
        ] }, select: { id: true } })
        if (blocked) throw new DirectConversationError(403, "You can't message this account.")
        const row = await tx.conversation.create({
          data: { isGroup: false, participants: { create: members.map(userId => ({ userId })) } }, select: { id: true },
        })
        return { id: row.id, created: true }
      }, { isolationLevel: 'Serializable' })
    } catch (error) {
      const code = (error as { code?: string })?.code
      if (!['P2034', 'P1008', 'P2028'].includes(code ?? '')) throw error
      if (attempt >= 2) throw new DirectConversationError(409, 'Chat creation is busy. Retry to open the same conversation.')
      await new Promise(resolve => setTimeout(resolve, 25 * (attempt + 1)))
    }
  }
}


/** Note to Self cannot use the two-distinct-member DM contract. Creation and
 * ownership lookup share the transaction so concurrent taps/devices converge. */
export async function openSelfConversation(db: PrismaClient, owner: string) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await db.$transaction(async tx => {
        const user = await tx.user.findUnique({ where: { id: owner }, select: { id: true } })
        if (!user) throw new DirectConversationError(404, 'User not found.')
        const candidates = await tx.conversation.findMany({
          where: { isSelf: true, isGroup: false, participants: { some: { userId: owner } } },
          orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
          select: { id: true, participants: { select: { userId: true } } },
        })
        const existing = candidates.find(row => row.participants.length === 1 && row.participants[0].userId === owner)
        if (existing) return { id: existing.id, created: false }
        const created = await tx.conversation.create({
          data: { isGroup: false, isSelf: true, name: 'Note to self', participants: { create: { userId: owner } } },
          select: { id: true },
        })
        return { id: created.id, created: true }
      }, { isolationLevel: 'Serializable' })
    } catch (error) {
      if (!['P2034', 'P1008', 'P2028'].includes((error as { code?: string })?.code ?? '')) throw error
      if (attempt >= 2) throw new DirectConversationError(409, 'Note to Self is busy. Retry to open the same notebook.')
      await new Promise(resolve => setTimeout(resolve, 25 * (attempt + 1)))
    }
  }
}

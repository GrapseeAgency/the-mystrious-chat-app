import type { PrismaClient, Prisma } from '../../prisma/generated-client'

export class ReminderContractError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
const PAGE_SIZE = 100
export async function reminderPage(db: PrismaClient, userId: string, dueOnly: boolean, cursor: string | null, now = new Date()) {
  let after: Prisma.ReminderWhereInput = {}
  if (cursor !== null) {
    try {
      if (cursor.length > 1024) throw Error('size')
      const data = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'))
      if (!Array.isArray(data) || data.length !== 4 || data[0] !== userId || data[1] !== dueOnly ||
          typeof data[2] !== 'string' || typeof data[3] !== 'string' || !data[3] || data[3].length > 128) throw Error('shape')
      const at = new Date(data[2])
      if (!Number.isFinite(at.getTime())) throw Error('time')
      after = { OR: [{ remindAt: { gt: at } }, { remindAt: at, id: { gt: data[3] } }] }
    } catch { throw new ReminderContractError(400, 'Invalid reminder cursor.') }
  }
  const rows = await db.reminder.findMany({
    where: { userId, AND: [after, ...(dueOnly ? [{ firedAt: null, remindAt: { lte: now } }] : [])] },
    orderBy: [{ remindAt: 'asc' }, { id: 'asc' }], take: PAGE_SIZE + 1,
  })
  const page = rows.slice(0, PAGE_SIZE)
  const last = page.at(-1)
  return { rows: page, nextCursor: rows.length > PAGE_SIZE && last
    ? Buffer.from(JSON.stringify([userId, dueOnly, last.remindAt.toISOString(), last.id])).toString('base64url') : null }
}

export async function resolveReminder(db: PrismaClient, id: string, userId: string) {
  return db.$transaction(async tx => {
    const row = await tx.reminder.findUnique({ where: { id } })
    if (!row) throw new ReminderContractError(404, 'Reminder not found.')
    if (row.userId !== userId) throw new ReminderContractError(403, 'Only the owner can resolve this reminder.')
    // One atomic transition; repeated/concurrent ACKs preserve the original time.
    if (!row.firedAt) await tx.reminder.updateMany({ where: { id, userId, firedAt: null }, data: { firedAt: new Date() } })
    const confirmed = await tx.reminder.findUniqueOrThrow({ where: { id } })
    return { ok: true, firedAt: confirmed.firedAt!.toISOString() }
  })
}

export async function deleteReminder(db: PrismaClient, id: string, userId: string) {
  return db.$transaction(async tx => {
    const row = await tx.reminder.findUnique({ where: { id } })
    if (row && row.userId !== userId) throw new ReminderContractError(403, 'Only the owner can cancel this reminder.')
    await tx.reminder.deleteMany({ where: { id, userId } })
    return { ok: true } // A lost DELETE response is safe to retry.
  })
}

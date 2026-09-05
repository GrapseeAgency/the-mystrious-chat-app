import { PrismaClient } from '@prisma/client'
const db = new PrismaClient()
const TID = 'cmtnbsb6e0001q456kyhku4kb'
async function main() {
  const t = await db.tournament.findUnique({ where: { id: TID }, include: { entries: true } })
  console.log(JSON.stringify({ status: t?.status, name: t?.name, entries: t?.entries.map(e => ({ userId: e.userId, points: e.points, wins: e.wins, losses: e.losses, draws: e.draws, joinedAt: e.joinedAt.toISOString() })) }, null, 2))
  const msg = await db.message.findFirst({ where: { kind: 'tournament', conversationId: 'cmtn1844z0000nhhcxv5i1f8p' }, orderBy: { createdAt: 'desc' } })
  console.log('carrier message:', msg?.id, msg?.kind, msg?.payload)
}
main().finally(() => db.$disconnect())

import { PrismaClient } from '@prisma/client'
const db = new PrismaClient()
const CONV = 'cmtn1844z0000nhhcxv5i1f8p'
const alice = 'cmtawq3h4001ktcwn674m1frp'
const bob = 'cmtawqfwh001ltcwnsalt6659'
const cara = 'cmtawydlt001utcwna93mi3w1'
async function main() {
  const conv = await db.conversation.findUnique({ where: { id: CONV }, select: { id: true, name: true, isGroup: true } })
  const parts = await db.conversationParticipant.findMany({ where: { conversationId: CONV }, select: { userId: true, role: true, user: { select: { name: true } } } })
  const users = await db.user.findMany({ where: { id: { in: [alice, bob, cara] } }, select: { id: true, name: true, xp: true } })
  const tourneys = await db.tournament.findMany({ where: { conversationId: CONV } })
  const msgs = await db.message.count({ where: { conversationId: CONV } })
  console.log(JSON.stringify({ conv, parts, users, existingTournaments: tourneys.length, totalMessages: msgs }, null, 2))
}
main().finally(() => db.$disconnect())

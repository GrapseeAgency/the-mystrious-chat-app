import { PrismaClient } from '@prisma/client'
const db = new PrismaClient()
const users = await db.user.findMany({ select: { id: true, name: true } })
const umap = Object.fromEntries(users.map(u => [u.id, u.name]))
console.log('USERS:', users.map(u => `${u.name}=${u.id.slice(-5)}`).join(', '))
const convos = await db.conversation.findMany({
  include: { participants: { include: { user: { select: { name: true } } } }, messages: { orderBy: { createdAt: 'asc' }, take: 100 }, }
})
for (const c of convos) {
  const members = c.participants.map(p => p.user.name).join(' + ')
  console.log(`\nCONV ${c.id.slice(-5)} group=${c.isGroup} name=${c.name} [${members}]`)
  for (const m of c.messages.slice(-6)) {
    const r = m.reactions ? m.reactions.map(x => `${x.emoji}(${umap[x.userId]})`).join(',') : ''
    console.log(`   ${umap[m.senderId]}: "${m.content.slice(0,40)}"${m.deletedAt?' [DELETED]':''} ${r?`{${r}}`:''}`)
  }
}
await db.$disconnect()

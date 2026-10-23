import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createRequire } from 'node:module'
import { deliverMessageNotification } from '../../src/lib/message-notification.ts'
const { Prisma, PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-23T00:00:00Z')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-notification-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir,'test.sqlite')}` })
  try {
    for (const name of ['Message','User','Conversation','ConversationParticipant','UserBlock']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int','Boolean','BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        const def = typeof f.default === 'string' ? ` DEFAULT '${f.default.replaceAll("'","''")}'` :
          typeof f.default === 'number' || typeof f.default === 'boolean' ? ` DEFAULT ${Number(f.default)}` : f.default?.name === 'now' ? ' DEFAULT CURRENT_TIMESTAMP' : ''
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
      })
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    await db.conversation.create({ data: { id: 'room', name: 'Group', isGroup: true } })
    for (const id of ['author','peer','quiet','hidden','departed','blocked']) {
      await db.user.create({ data: { id, username: id, name: id === 'author' ? 'Private Real Name' : id,
        preferences: id === 'hidden' ? '{"notifPreviews":false}' : '{}' } })
      await db.conversationParticipant.create({ data: { userId: id, conversationId: 'room',
        mutedUntil: id === 'quiet' ? new Date(now.getTime()+10000) : null } })
    }
    await db.message.create({ data: { id:'message', conversationId:'room', senderId:'author', content:'Secret caption',
      anon:true, anonAlias:'Quiet Raven', createdAt:now } })
    const calls = []
    const send = async (ids,payload,options) => { calls.push({ ids,payload,bodies:Object.fromEntries(ids.map(id => [id,options.bodyFor(id)])) }); return { users:ids.length,devices:0,sent:0,pruned:0,skipped:0 } }
    await run(db, () => deliverMessageNotification(db,send,'message',() => now), calls, send)
  } finally { await db.$disconnect(); await rm(dir,{recursive:true,force:true}) }
}
test('push uses current membership, mutual blocks, mute, anonymous name and per-recipient previews', () => fixture(async(db,notify,calls) => {
  await db.conversationParticipant.deleteMany({ where:{userId:'departed'} })
  await db.userBlock.create({data:{blockerId:'blocked',blockedId:'author'}})
  assert.equal(await notify(),true)
  const call=calls[0]
  assert.deepEqual(call.ids.sort(),['hidden','peer'])
  assert.equal(call.payload.title,'Quiet Raven · Group')
  assert.equal(call.bodies.peer,'Quiet Raven: Secret caption')
  assert.equal(call.bodies.hidden,'New message')
  assert.equal(JSON.stringify(call).includes('Private Real Name'),false)
}))
test('view-once captions and corrupt preview preferences never enter a push body', () => fixture(async(db,notify,calls) => {
  await db.message.update({where:{id:'message'},data:{viewOnce:true,anonAlias:null}})
  await db.user.update({where:{id:'peer'},data:{preferences:'broken-json'}})
  await notify()
  assert.equal(calls[0].payload.title,'Anonymous · Group')
  assert.equal(calls[0].bodies.peer,'New message')
  assert.equal(calls[0].payload.body,'Anonymous: View-once message')
  assert.equal(JSON.stringify(calls).includes('Secret caption'),false)
}))
test('deleted, expired and unavailable-thread messages cannot schedule notifications', () => fixture(async(db,notify,calls) => {
  for(const patch of [{deletedAt:now},{expiresAt:now},{parentId:'missing'}]) {
    await db.message.update({where:{id:'message'},data:patch});assert.equal(await notify(),false)
    await db.message.update({where:{id:'message'},data:{deletedAt:null,expiresAt:null,parentId:null}})
  }
  await db.message.create({data:{id:'root',conversationId:'foreign',senderId:'author',content:'root'}})
  await db.message.update({where:{id:'message'},data:{parentId:'root'}})
  assert.equal(await notify(),false);assert.equal(calls.length,0)
}))
test('server machine marker includes represented member; payload-only markers do not', () => fixture(async(db,notify,calls) => {
  await db.message.update({where:{id:'message'},data:{anon:false,payload:'{"webhook":true,"webhookName":"Builds"}'}})
  await notify();assert.equal(calls[0].ids.includes('author'),false)
  assert.equal(calls[0].payload.title,'Unverified integration · Group')
  await db.message.update({where:{id:'message'},data:{viaAutomation:true}})
  await notify();assert.equal(calls[1].ids.includes('author'),true)
  assert.equal(calls[1].payload.title,'Webhook · Builds · Group')
}))
test('deadline is checked again after planning and a push outage does not erase the committed message', () => fixture(async(db,notify,calls,send) => {
  const deadline=new Date(now.getTime()+1000)
  await db.message.update({where:{id:'message'},data:{expiresAt:deadline}})
  let ticks=0
  assert.equal(await deliverMessageNotification(db,send,'message',()=>ticks++===0?now:deadline),false)
  assert.equal(calls.length,0)
  await assert.rejects(deliverMessageNotification(db,async()=>{throw Error('provider offline')},'message',()=>now))
  assert.equal(await db.message.count(),1)
}))

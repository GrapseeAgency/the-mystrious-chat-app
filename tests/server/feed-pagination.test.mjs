import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { scanFeed, feedScope, readFeedBatch } from '../../src/lib/feed-pagination.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(),'feed-pages-'))
  const sql=[]
  const db = new PrismaClient({datasourceUrl:`file:${join(directory,'test.sqlite')}`,log:[{emit:'event',level:'query'}]})
  db.$on('query',event=>{if(event.query.startsWith('SELECT'))sql.push(event)})
  try {
    await db.$executeRawUnsafe('CREATE TABLE Message(id TEXT PRIMARY KEY,createdAt DATETIME,content TEXT)')
    await db.$executeRawUnsafe('CREATE INDEX message_history_order ON Message(createdAt,id)')
    await db.$executeRawUnsafe(`INSERT INTO Message VALUES ${Array.from({length:6000},(_,i)=>`('m-${String(i).padStart(6,'0')}',1000,'hello')`).join(',')}`)
    let scanned=0,largest=0
    const page=(cursor=null,project=row=>row.id,scope=feedScope('search','owner','hello'),limit=30)=>db.$transaction(tx=>scanFeed({cursor,scope,limit,
      load:async(before,upper,take)=>{largest=Math.max(largest,take); const rows=await readFeedBatch(before,upper,take,(range,take)=>tx.message.findMany({where:{createdAt:range.time,id:range.id},orderBy:[{createdAt:'desc'},{id:'desc'}],take,select:{id:true,createdAt:true,content:true}}));scanned+=rows.length;return rows},
      position:row=>({id:row.id,at:row.createdAt.toISOString()}),project}))
    await run({db,page,scanned:()=>scanned,largest:()=>largest,sql})
  } finally {await db.$disconnect();await rm(directory,{recursive:true,force:true})}
}
test('all 6000 same-time matches remain reachable beyond both historical scan and result caps',()=>fixture(async f=>{
  const ids=new Set();let cursor=null
  do {const page=await f.page(cursor);assert.ok(page.items.length<=30);assert.equal(page.hasMore,page.nextCursor!==null)
    page.items.forEach(id=>{assert.ok(!ids.has(id));ids.add(id)});cursor=page.nextCursor
  }while(cursor)
  assert.equal(ids.size,6000);assert.ok(f.largest()<=201)
}))
test('empty scan windows advance to older hits without claiming no results',()=>fixture(async f=>{
  const filter=row=>row.id==='m-000001'?row.id:null
  let page=await f.page(null,filter);assert.deepEqual(page.items,[]);assert.equal(page.hasMore,true)
  assert.ok(f.scanned()<=1005)
  while(page.hasMore && !page.items.length) page=await f.page(page.nextCursor,filter)
  assert.deepEqual(page.items,['m-000001'])
}))
test('fixed upper boundary, deletion of the cursor, and feed binding survive paging',()=>fixture(async f=>{
  const first=await f.page();const boundary=first.items.at(-1)
  await f.db.$executeRawUnsafe("INSERT INTO Message VALUES ('new',2000,'hello')")
  await f.db.message.deleteMany({where:{id:boundary}})
  const second=await f.page(first.nextCursor)
  assert.ok(second.items.every(id=>id<boundary));assert.ok(!second.items.includes('new'))
  for(const scope of [feedScope('search','other','hello'),feedScope('search','owner','different'),feedScope('mentions','owner','hello')])
    await assert.rejects(f.page(first.nextCursor,undefined,scope),{status:400})
  await assert.rejects(f.page('bad-token'),{status:400})
}))
test('full expiry filtering cannot erase scan continuation; source failures never become successful emptiness',()=>fixture(async f=>{
  const page=await f.page(null,()=>null);assert.deepEqual(page.items,[]);assert.ok(page.nextCursor)
  await assert.rejects(scanFeed({cursor:null,scope:'scope',limit:30,load:async()=>{throw Error('offline')},position:()=>null,project:()=>null}),/offline/)
}))


test('actual Prisma feed continuation seeks the complete timestamp and identity index',()=>fixture(async f=>{
  const first=await f.page()
  await f.page(first.nextCursor)
  const tied=f.sql.filter(q=>/createdAt[`"] = /.test(q.query) && /id[`"] </.test(q.query))
  assert.ok(tied.length)
  for(const query of tied) {
    const plan=await f.db.$queryRawUnsafe('EXPLAIN QUERY PLAN '+query.query,...JSON.parse(query.params))
    assert.match(plan.map(row=>row.detail).join('\n'),/message_history_order \(createdAt=\? AND id</)
  }
}))

import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { kanbanOperation, KanbanError } from '../../src/lib/kanban-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-22T00:00:00Z')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-board-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE User(id TEXT PRIMARY KEY,name TEXT)',
      'CREATE TABLE Conversation(id TEXT PRIMARY KEY,isGroup BOOLEAN)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,role TEXT,UNIQUE(userId,conversationId))',
      'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,content TEXT,deletedAt DATETIME,viewOnce BOOLEAN NOT NULL DEFAULT false,expiresAt DATETIME,parentId TEXT)',
      'CREATE TABLE KanbanCard(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,column TEXT,position INTEGER,assigneeId TEXT,createdById TEXT,sourceMessageId TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
      'CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
      "INSERT INTO User VALUES ('me','Me'),('peer','Peer'),('admin','Admin')",
      "INSERT INTO Conversation VALUES ('room',true),('elsewhere',true)",
      "INSERT INTO ConversationParticipant VALUES ('a','me','room','member'),('b','peer','room','member'),('c','admin','room','admin')",
      "INSERT INTO Message(id,conversationId,content) VALUES ('root','room','Root'),('foreign','elsewhere','Foreign')",
      "INSERT INTO Message(id,conversationId,content,parentId) VALUES ('reply','room','  private   task\ntext  ','root')",
    ]) await db.$executeRawUnsafe(sql)
    await run(db,(action,user='me',target=db) => kanbanOperation(target,user,action,() => now))
  } finally { await db.$disconnect(); await rm(dir,{recursive:true,force:true}) }
}
const create = input => ({ kind:'create', conversationId:'room', input })
const list = { kind:'list', conversationId:'room' }
const update = (cardId,input) => ({kind:'update',cardId,input})
test('message conversion preserves provenance but never copies plaintext into the activity journal', () => fixture(async (db,act) => {
  const result = await act(create({messageId:'reply',assigneeId:'peer'}))
  assert.equal(result.boardReceiptVersion,1); assert.equal(result.userId,'me'); assert.equal(result.conversationId,'room')
  assert.equal(result.card.title,'private task text'); assert.equal(result.card.sourceMessageId,'reply'); assert.equal(result.card.assigneeName,'Peer')
  const logs = await db.logEvent.findMany()
  assert.equal(logs.length,1); assert.ok(!JSON.stringify(logs).includes('private task text'))
  assert.equal((await act(list)).cards[0].sourceMessageId,'reply')
  assert.equal((await act(create({messageId:'reply',title:'Edited task title'}))).card.title,'Edited task title')
}))
test('deleted expired view-once disappearing foreign orphan and unavailable roots cannot become permanent tasks', () => fixture(async(db,act) => {
  for (const patch of ["deletedAt=0", "deletedAt=NULL,expiresAt=0", "expiresAt=NULL,viewOnce=true", "viewOnce=false,expiresAt=9999999999999",
    "expiresAt=NULL,parentId='foreign'", "parentId='missing'", "parentId='reply'"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id='reply'`)
    await assert.rejects(act(create({messageId:'reply'})), error => [400,410].includes(error.status))
  }
  await db.$executeRaw`UPDATE Message SET parentId='root' WHERE id='reply'`
  for (const patch of ['expiresAt=0','expiresAt=9999999999999','expiresAt=NULL,viewOnce=true','viewOnce=false,deletedAt=0']) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id='root'`)
    await assert.rejects(act(create({messageId:'reply'})), error => [400,410].includes(error.status))
  }
  await assert.rejects(act(create({messageId:'foreign'})),{status:400})
  assert.equal(await db.kanbanCard.count(),0); assert.equal(await db.logEvent.count(),0)
}))
test('append and move retries retain position, preserve omitted fields, and clear assignment explicitly', () => fixture(async(db,act) => {
  const first = (await act(create({title:'First',column:'doing',assigneeId:'peer'}))).card
  const next = (await act(create({title:'Next'}))).card
  const moved = (await act(update(next.id,{column:'doing'}))).card
  assert.equal(first.position,0); assert.equal(moved.position,1)
  assert.equal((await act(update(next.id,{column:'doing'}))).card.position,1)
  const renamed = (await act(update(first.id,{title:'Renamed'}))).card
  assert.equal(renamed.assigneeId,'peer'); assert.equal(renamed.column,'doing')
  assert.equal((await act(update(first.id,{assigneeId:null}))).card.assigneeId,null)
  for (const position of [null,false,'1',-1,0.5,2147483648]) await assert.rejects(act(update(first.id,{position})),{status:400})
  await assert.rejects(act(update(first.id,{assigneeId:'outsider'})),{status:400})
}))
test('departure and creator/admin checks apply to each operation, not cached room access', () => fixture(async(db,act) => {
  const card = (await act(create({title:'Task'}))).card
  await assert.rejects(act({kind:'delete',cardId:card.id},'peer'),{status:403})
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  for (const action of [list,create({title:'More'}),update(card.id,{column:'done'}),{kind:'delete',cardId:card.id}]) await assert.rejects(act(action),{status:403})
  assert.equal((await act({kind:'delete',cardId:card.id},'admin')).ok,true)
  assert.equal(await db.kanbanCard.count(),0)
}))
test('journal or final receipt failure rolls back creation instead of returning a false failure after committing', () => fixture(async(db,act) => {
  await db.$executeRawUnsafe("CREATE TRIGGER fail_log BEFORE INSERT ON LogEvent BEGIN SELECT RAISE(ABORT, 'log fault'); END")
  await assert.rejects(act(create({title:'Task'})))
  assert.equal(await db.kanbanCard.count(),0)
  await db.$executeRawUnsafe('DROP TRIGGER fail_log')
  const broken = {$transaction:(fn,options)=>db.$transaction(tx=>fn(new Proxy(tx,{get:(target,key)=>key==='user'?{findMany:async()=>{throw Error('snapshot fault')}}:target[key]})),options)}
  await assert.rejects(act(create({title:'Task'}),'me',broken),/snapshot fault/)
  assert.equal(await db.kanbanCard.count(),0);assert.equal(await db.logEvent.count(),0)
}))
test('concurrent appends do not pick the same implicit rank', () => fixture(async(db,act) => {
  const results = await Promise.allSettled([act(create({title:'First'})),act(create({title:'Second'}))])
  for (const result of results) if (result.status==='rejected') assert.equal(result.reason.status,409)
  const rows = await db.kanbanCard.findMany()
  assert.ok(rows.length>0);assert.equal(new Set(rows.map(x=>x.position)).size,rows.length)
}))
test('oversize boards refuse partial snapshots and position overflow refuses before writing', () => fixture(async(db,act) => {
  const card = (await act(create({title:'Last'}))).card
  await db.kanbanCard.update({where:{id:card.id},data:{position:2147483647}})
  await assert.rejects(act(create({title:'Overflow'})),{status:409})
  await db.$executeRawUnsafe("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<500) INSERT INTO KanbanCard(id,conversationId,title,column,position,createdAt,updatedAt) SELECT 'bulk'||x,'room','Bulk','todo',x,0,0 FROM n")
  await assert.rejects(act(list),{status:413});await assert.rejects(act(create({title:'Over cap'})),{status:409})
}))
test('all mounted board routes require the existing owner credential before touching a room', async () => {
  for (const [path,methods] of [['conversations/[id]/kanban',['GET','POST']],['kanban/[cardId]',['PATCH','DELETE']]]) {
    const source=stripTypeScriptTypes(await readFile(new URL('../../src/app/api/'+path+'/route.ts',import.meta.url),'utf8')).replace(/^import .*$/gm,'').replace(/export /g,'')
    let calls=0
    const deps={NextResponse:{json:Response.json},db:{},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',requireActionOwner:async()=>Response.json({},{status:401}),kanbanOperation:async()=>{calls++},KanbanError}
    const handlers=new Function(...Object.keys(deps),source+';return {'+methods.join(',')+'}')(...Object.values(deps))
    for(const method of methods){
      const request=new Request('https://board.test?userId=me',{method,...(['POST','PATCH'].includes(method)?{body:JSON.stringify({userId:'me'})}:{})})
      assert.equal((await handlers[method](request,{params:Promise.resolve({id:'room',cardId:'card'})})).status,401)
    }
    assert.equal(calls,0)
  }
})

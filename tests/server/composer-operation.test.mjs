import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { readComposer, writeComposer, ComposerError, composerFailure } from '../../src/lib/composer-operation.ts'
import { mutateMessage } from '../../src/lib/message-mutation.ts'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const {PrismaClient}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(file,bindings,expression){
 const text=(await readFile(new URL('../../'+file,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run){
 const dir=await mkdtemp(join(tmpdir(),'composer-')),db=new PrismaClient({datasourceUrl:`file:${join(dir,'db.sqlite')}`})
 try{
  for(const sql of [
   'CREATE TABLE User(id TEXT PRIMARY KEY,sessionTokenHash TEXT,legacyTokenHashes TEXT)',
   'CREATE TABLE Conversation(id TEXT PRIMARY KEY)',
   'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,draft TEXT,draftRevision TEXT NOT NULL DEFAULT "0",UNIQUE(userId,conversationId))',
   'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,content TEXT,editedAt DATETIME,parentId TEXT,deletedAt DATETIME,expiresAt DATETIME,viewOnce BOOLEAN DEFAULT false,viewedAt DATETIME,pinnedAt DATETIME)',
   'CREATE TABLE ComposerOperation(userId TEXT,clientId TEXT,kind TEXT,targetId TEXT,rootId TEXT,requestHash TEXT,revision TEXT,outcome TEXT,createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(userId,clientId))',
   'CREATE TABLE ThreadDraftState(userId TEXT,conversationId TEXT,rootId TEXT,draft TEXT,revision TEXT,PRIMARY KEY(userId,conversationId,rootId))',
   "INSERT INTO Conversation VALUES ('room')",
   "INSERT INTO ConversationParticipant(id,userId,conversationId) VALUES ('membership','owner','room'),('peer-membership','peer','room')",
   "INSERT INTO Message(id,conversationId,senderId,content) VALUES ('m','room','owner','original'),('root','room','owner','root')",
  ])await db.$executeRawUnsafe(sql)
  for(const id of ['owner','peer'])await db.$executeRaw`INSERT INTO User VALUES (${id},${hashSessionToken('token-'+id)},NULL)`
  const read=(kind='draft',target='room',root='',owner='owner')=>readComposer(db,owner,kind,target,root)
  const write=(text,intent,kind='draft',target='room',root='',owner='owner')=>writeComposer(db,owner,kind,target,root,text,intent,()=>new Date('2026-10-28T00:00:00Z'))
  await run({db,read,write})
 }finally{await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
test('draft CAS, lost acknowledgement and terminal conflicts cannot undo another device',()=>fixture(async f=>{
 const baseline=await f.read()
 const a=await f.write('mine',{clientId:'a',expectedRevision:baseline.revision})
 const b=await f.write('other device',{clientId:'b',expectedRevision:a.revision})
 assert.equal((await f.write('mine',{clientId:'a',expectedRevision:baseline.revision})).outcome,'superseded')
 const stale=await f.write('',{clientId:'clear-old',expectedRevision:a.revision})
 assert.equal(stale.outcome,'conflict');assert.equal(stale.text,'other device')
 assert.equal((await f.write('',{clientId:'clear-old',expectedRevision:a.revision})).outcome,'conflict')
 assert.equal((await f.write('',{clientId:'clear-new',expectedRevision:b.revision})).text,'')
 await assert.rejects(async()=>f.write('mutated',{clientId:'a',expectedRevision:baseline.revision}),{status:409})
}))
test('causal successors preserve identity and do not rebase a conflicted predecessor',()=>fixture(async f=>{
 const first=await f.read()
 await assert.rejects(async()=>f.write('next',{clientId:'next',predecessorClientId:'missing'}),{status:409})
 await f.write('a',{clientId:'a',expectedRevision:first.revision})
 assert.equal((await f.write('b',{clientId:'b',predecessorClientId:'a'})).outcome,'applied')
 await f.write('stale',{clientId:'stale',expectedRevision:first.revision})
 assert.equal((await f.write('not rebased',{clientId:'child',predecessorClientId:'stale'})).outcome,'conflict')
}))
test('thread and main drafts are independent and reauthorize root and membership on every replay',()=>fixture(async f=>{
 const main=await f.read(),thread=await f.read('draft','room','root')
 await f.write('main',{clientId:'main',expectedRevision:main.revision})
 await f.write('reply',{clientId:'thread',expectedRevision:thread.revision},'draft','room','root')
 assert.equal((await f.read()).text,'main');assert.equal((await f.read('draft','room','root')).text,'reply')
 await assert.rejects(async()=>f.write('bad',{clientId:'bad',predecessorClientId:'main'},'draft','room','root'),{status:400})
 await f.db.message.update({where:{id:'root'},data:{deletedAt:new Date(0)},select:{id:true}})
 await assert.rejects(f.read('draft','room','root'),{status:410})
 await f.db.conversationParticipant.deleteMany({where:{userId:'owner'}})
 await assert.rejects(async()=>f.write('main',{clientId:'main',expectedRevision:main.revision}),{status:403})
}))
test('membership replacement invalidates frozen draft revisions',()=>fixture(async f=>{
 const old=await f.read()
 await f.db.conversationParticipant.deleteMany({where:{userId:'owner'}})
 await f.db.$executeRawUnsafe("INSERT INTO ConversationParticipant(id,userId,conversationId) VALUES ('new-member','owner','room')")
 assert.equal((await f.write('old offline draft',{clientId:'old',expectedRevision:old.revision})).outcome,'conflict')
}))
test('edit CAS remains ordered through same-clock ABA edits and legacy clients',()=>fixture(async f=>{
 const old=await f.read('edit','m')
 const a=await f.write('A',{clientId:'a',expectedRevision:old.revision},'edit','m')
 const b=await f.write('original',{clientId:'b',expectedRevision:a.revision},'edit','m')
 assert.notEqual(a.editedAt,b.editedAt);assert.notEqual(old.revision,b.revision)
 assert.equal((await f.write('stale',{clientId:'stale',expectedRevision:old.revision},'edit','m')).outcome,'conflict')
 await mutateMessage(f.db,'m','owner',{kind:'edit',content:'legacy'},(tx,id)=>tx.message.findUnique({where:{id},select:{id:true}}),()=>new Date(0))
 const legacy=await f.read('edit','m')
 assert.ok(Date.parse(legacy.editedAt)>Date.parse(b.editedAt))
 assert.equal((await f.write('not legacy',{clientId:'after-legacy',expectedRevision:b.revision},'edit','m')).outcome,'conflict')
 assert.equal((await f.write('A',{clientId:'a',expectedRevision:old.revision},'edit','m')).outcome,'superseded')
}))
test('edit authorization, deletion and parent expiry reject writes without creating replay receipts',()=>fixture(async f=>{
 const base=await f.read('edit','m')
 await assert.rejects(f.read('edit','m','','peer'),{status:403})
 await f.db.message.update({where:{id:'m'},data:{parentId:'root'},select:{id:true}})
 await f.db.message.update({where:{id:'root'},data:{expiresAt:new Date(0)},select:{id:true}})
 await assert.rejects(async()=>f.write('no',{clientId:'bad',expectedRevision:base.revision},'edit','m'),{status:410})
 assert.equal(await f.db.composerOperation.count(),0)
}))
test('actual draft and edit routes require the exact actor credential before mutations',()=>fixture(async f=>{
 const auth=await load('src/lib/wallet-authorization.ts',{db:f.db,bearerToken,tokenAccepted,hashSessionToken},'requireActionOwner')
 const bindings={db:f.db,requireActionOwner:auth,readComposer,writeComposer,composerFailure,
  safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',notifySocket:async()=>{},memberIdsOf:async()=>[],mapMessage:r=>r,MESSAGE_FULL_INCLUDE:{}}
 const route=await load('src/app/api/composer-state/route.ts',bindings,'({GET,PATCH})')
 const base=await f.read()
 for(const credential of [null,'token-peer']){
  const headers=credential?{authorization:'Bearer '+credential}:{}
  assert.equal((await route.PATCH(new Request('https://test/api/composer-state',{method:'PATCH',headers,body:JSON.stringify({userId:'owner',kind:'draft',targetId:'room',text:'intrusion',clientId:'x',expectedRevision:base.revision})}))).status,credential?403:401)
 }
 assert.equal((await f.read()).text,'')
 const legacy=await load('src/app/api/messages/[id]/route.ts',{...bindings,mutateMessage,MessageMutationError:class extends Error{},NextResponse:Response,MESSAGE_MAX:2000},'({PATCH,DELETE})')
 for(const method of ['PATCH','DELETE'])assert.equal((await legacy[method](new Request('https://test/api/messages/m',{method,headers:{authorization:'Bearer token-peer'},body:JSON.stringify({userId:'owner',requesterId:'owner',content:'spoof'})}),{params:Promise.resolve({id:'m'})})).status,403)
}))

test('composer scopes and identities reject malformed types, normalization and trailing newlines',()=>fixture(async f=>{
 const before=await f.read()
 for(const [kind,target,root] of [['draft',' room',''],['draft','room',42],['edit','m','root'],['draft',null,''],['draft','room','root\n']])
  await assert.rejects(async()=>writeComposer(f.db,'owner',kind,target,root,'text',{clientId:'scope',expectedRevision:before.revision}),e=>e instanceof ComposerError&&e.status===400)
 for(const intent of [{clientId:'operation\n',expectedRevision:before.revision},{clientId:'operation',expectedRevision:before.revision+'\n'},{clientId:'operation',predecessorClientId:'prior\n'}])
  await assert.rejects(async()=>f.write('text',intent),e=>e instanceof ComposerError&&e.status===400)
 assert.equal(await f.db.composerOperation.count(),0)
 assert.equal((await f.read()).revision,before.revision)
}))

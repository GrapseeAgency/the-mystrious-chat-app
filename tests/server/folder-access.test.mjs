import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { folderIconId } from '../../src/lib/icon-ids.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'folders-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const sql of [
   'CREATE TABLE User(id TEXT PRIMARY KEY, sessionTokenHash TEXT, legacyTokenHashes TEXT)',
   'CREATE TABLE Conversation(id TEXT PRIMARY KEY)',
   'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))',
   'CREATE TABLE Folder(id TEXT PRIMARY KEY, userId TEXT NOT NULL, name TEXT NOT NULL, emoji TEXT DEFAULT "folder", position INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
   'CREATE TABLE FolderConversation(id TEXT PRIMARY KEY, folderId TEXT REFERENCES Folder(id) ON DELETE CASCADE, conversationId TEXT REFERENCES Conversation(id) ON DELETE CASCADE, position INTEGER DEFAULT 0, UNIQUE(folderId,conversationId))',
   'CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
   "INSERT INTO Conversation VALUES ('room'),('archived'),('foreign')",
   "INSERT INTO ConversationParticipant VALUES ('p1','me','room'),('p2','me','archived'),('p3','other','foreign')",
  ]) await db.$executeRawUnsafe(sql)
  for(const id of ['me','other']) await db.$executeRawUnsafe('INSERT INTO User VALUES (?,?,?)',id,hashSessionToken(`synthetic-${id}`),'[]')
  for(const [id,userId,position]of [['f1','me',0],['f2','me',1],['f3','me',2],['alien','other',0]]) await db.folder.create({data:{id,userId,name:id,position}})
  await db.folderConversation.create({data:{folderId:'f1',conversationId:'room'}})
  const helper=await load('src/lib/folder-operation.ts',{bearerToken,tokenAccepted},'({withFolderOwner,FolderError,folderErrorResponse,folderHeaders,folderInclude,folderWire,folderScope,folderName,folderSelection})')
  const bindings={db,...helper,folderIconId,NextResponse:{json:Response.json},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():''}
  const base=await load('src/app/api/folders/route.ts',bindings,'({GET,POST})')
  const single=await load('src/app/api/folders/[id]/route.ts',bindings,'({PATCH,DELETE})')
  const member=await load('src/app/api/folders/[id]/conversations/route.ts',bindings,'PUT')
  await run(db,{...base,...single,PUT:member})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=(id='f1')=>({params:Promise.resolve({id})})
const req=(method,body={},token='synthetic-me',owner='me')=>new Request(`https://folder.test/?userId=${owner}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify(body)})})
test('every folder route rejects missing and other-owner credentials without changing rows',()=>fixture(async(db,h)=>{
 for(const method of ['GET','POST','PATCH','DELETE','PUT']) {
  const body={userId:'me',name:'Changed',conversationIds:['archived']}
  assert.equal((await h[method](req(method,body,null),ctx())).status,401)
  assert.equal((await h[method](req(method,body,'synthetic-other'),ctx())).status,403)
 }
 assert.equal(await db.folder.count(),4);assert.equal((await db.folder.findUnique({where:{id:'f1'}})).name,'f1')
 assert.deepEqual((await db.folderConversation.findMany()).map(r=>r.conversationId),['room'])
}))
test('membership replacement rejects malformed, unknown, foreign and removed chats without partial clear',()=>fixture(async(db,h)=>{
 for(const conversationIds of [[null],[4],[''],['room','foreign'],['unknown']]) {
  const response=await h.PUT(req('PUT',{conversationIds}),ctx());assert.ok([400,403].includes(response.status))
  assert.deepEqual((await db.folderConversation.findMany({where:{folderId:'f1'}})).map(r=>r.conversationId),['room'])
 }
 await db.conversationParticipant.deleteMany({where:{userId:'me',conversationId:'archived'}})
 assert.equal((await h.PUT(req('PUT',{conversationIds:['archived']}),ctx())).status,403)
 assert.equal(await db.folderConversation.count(),1)
}))
test('owned snapshots and ordered save receipts are scoped; revoked chat IDs disappear; explicit empty clears',()=>fixture(async(db,h)=>{
 const response=await h.PUT(req('PUT',{conversationIds:['archived','room','archived']}),ctx())
 assert.equal(response.status,200);assert.match(response.headers.get('cache-control'),/private.*no-store/)
 assert.equal(response.headers.get('vary'),'Authorization')
 const body=await response.json();assert.equal(body.folderVersion,1);assert.equal(body.userId,'me');assert.equal(body.folder.id,'f1')
 assert.deepEqual(body.folder.conversationIds,['archived','room'])
 await db.conversationParticipant.deleteMany({where:{userId:'me',conversationId:'archived'}})
 const page=await(await h.GET(req('GET'))).json();assert.deepEqual(page.folders.find(f=>f.id==='f1').conversationIds,['room'])
 assert.equal(page.folders.length,3)
 assert.equal((await h.PUT(req('PUT',{conversationIds:[]}),ctx())).status,200);assert.equal(await db.folderConversation.count(),0)
}))
test('folder moves atomically normalize the entire owned order; invalid positions do not change it',()=>fixture(async(db,h)=>{
 const response=await h.PATCH(req('PATCH',{position:0,name:'Moved'}),ctx('f3'))
 assert.equal(response.status,200);assert.equal((await response.json()).folder.position,0)
 assert.deepEqual((await db.folder.findMany({where:{userId:'me'},orderBy:{position:'asc'}})).map(f=>[f.id,f.position]),[['f3',0],['f1',1],['f2',2]])
 for(const position of [null,true,'0',-1,3,1.2]) assert.equal((await h.PATCH(req('PATCH',{position}),ctx())).status,400)
 assert.equal((await db.folder.findUnique({where:{id:'alien'}})).position,0)
}))
test('create and log commit together; confirmed delete leaves chats intact',()=>fixture(async(db,h)=>{
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_folder_log BEFORE INSERT ON LogEvent BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
 await assert.rejects(h.POST(req('POST',{userId:'me',name:'Work'})));assert.equal(await db.folder.count(),4)
 await db.$executeRawUnsafe('DROP TRIGGER refuse_folder_log')
 const created=await h.POST(req('POST',{userId:'me',name:' Work '}));assert.equal(created.status,201)
 const body=await created.json();assert.equal(body.folder.name,'Work');assert.equal(body.folder.position,3);assert.equal(await db.logEvent.count(),1)
 const deleted=await(await h.DELETE(req('DELETE'),ctx())).json()
 assert.deepEqual(deleted,{folderVersion:1,userId:'me',folderId:'f1',deleted:true,ok:true})
 assert.equal(await db.folderConversation.count(),0);assert.equal(await db.conversation.count(),3)
}))
test('failed replacement or reorder rolls back every earlier write in its transaction',()=>fixture(async(db,h)=>{
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_members BEFORE INSERT ON FolderConversation BEGIN SELECT RAISE(ABORT, 'synthetic membership failure'); END")
 await assert.rejects(h.PUT(req('PUT',{conversationIds:['archived']}),ctx()))
 assert.deepEqual((await db.folderConversation.findMany({where:{folderId:'f1'}})).map(r=>r.conversationId),['room'])
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_reorder BEFORE UPDATE ON Folder WHEN NEW.id = 'f2' BEGIN SELECT RAISE(ABORT, 'synthetic reorder failure'); END")
 await assert.rejects(h.PATCH(req('PATCH',{position:0}),ctx('f3')))
 assert.deepEqual((await db.folder.findMany({where:{userId:'me'},orderBy:{position:'asc'}})).map(f=>[f.id,f.position]),[['f1',0],['f2',1],['f3',2]])
}))

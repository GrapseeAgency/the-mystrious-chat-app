import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as access from '../../src/lib/game-read.ts'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const {PrismaClient}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, name) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text)+`;return ${name}`)(...Object.values(bindings))
}
const routes=['games/route.ts','games/[id]/route.ts','tournaments/route.ts','tournaments/[id]/route.ts']
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'game-read-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const sql of [
   'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT, color TEXT, sessionTokenHash TEXT, legacyTokenHashes TEXT)',
   'CREATE TABLE Conversation(id TEXT PRIMARY KEY)',
   'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))',
   'CREATE TABLE GameMatch(id TEXT PRIMARY KEY, conversationId TEXT, game TEXT DEFAULT "tictactoe", playerXId TEXT, playerOId TEXT, board TEXT DEFAULT "         ", turn TEXT DEFAULT "X", status TEXT DEFAULT "active", winnerId TEXT, winLine TEXT, moveCount INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
   'CREATE TABLE Tournament(id TEXT PRIMARY KEY, conversationId TEXT, name TEXT, game TEXT DEFAULT "tictactoe", status TEXT DEFAULT "running", createdById TEXT, endsAt DATETIME, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
   'CREATE TABLE TournamentPlayer(id TEXT PRIMARY KEY, tournamentId TEXT, userId TEXT, points INTEGER DEFAULT 0, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0, draws INTEGER DEFAULT 0, joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(tournamentId,userId))',
   "INSERT INTO Conversation VALUES ('room'),('other')",
   "INSERT INTO ConversationParticipant VALUES ('member','me','room'),('outsider','out','other')",
  ]) await db.$executeRawUnsafe(sql)
  for(const id of ['me','out']) await db.$executeRawUnsafe('INSERT INTO User VALUES (?,?,?,?,?)',id,id,'#F2A65A',hashSessionToken(`synthetic-${id}`),'[]')
  await db.gameMatch.create({data:{id:'match',conversationId:'room',playerXId:'me'}})
  await db.tournament.create({data:{id:'season',conversationId:'room',createdById:'me',name:'Private Cup'}})
  await db.tournamentPlayer.create({data:{tournamentId:'season',userId:'me'}})
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const handlers=[]
  for(const path of routes) handlers.push(await load(`src/app/api/${path}`,{db,...access,requireActionOwner,NextResponse:{json:Response.json},strField:v=>typeof v==='string'?v.trim():''},'GET'))
  await run(db,handlers)
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const request=(owner='me',token='synthetic-me')=>new Request(`https://game.test/?conversationId=room&userId=${owner}`,{headers:token?{Authorization:`Bearer ${token}`}:{}})
const ctx=i=>({params:Promise.resolve({id:i===1?'match':'season'})})
test('all game list/detail routes reject missing or other-owner credentials and valid outsiders',()=>fixture(async(db,handlers)=>{
 for(const [i,handler]of handlers.entries()) {
  assert.equal((await handler(request('me',null),ctx(i))).status,401)
  assert.equal((await handler(request('me','synthetic-out'),ctx(i))).status,403)
  assert.equal((await handler(request('out','synthetic-out'),ctx(i))).status,403)
 }
}))
test('member snapshots are versioned, owner/room labelled and private with exact joined state',()=>fixture(async(db,handlers)=>{
 for(const [i,handler]of handlers.entries()) {
  const response=await handler(request(),ctx(i));assert.equal(response.status,200)
  assert.equal(response.headers.get('vary'),'Authorization');assert.match(response.headers.get('cache-control'),/private.*no-store/)
  const page=await response.json();assert.equal(page.gameReadVersion,1);assert.equal(page.userId,'me');assert.equal(page.conversationId,'room')
  if(i===0)assert.equal(page.matches[0].conversationId,'room')
  if(i===1)assert.equal(page.match.id,'match')
  if(i===2){assert.equal(page.tournaments[0].joined,true);assert.equal(page.tournaments[0].conversationId,'room')}
  if(i===3)assert.equal(page.tournament.entries[0].userId,'me')
 }
}))
test('removed participants lose all four read paths even when they created or joined the resource',()=>fixture(async(db,handlers)=>{
 await db.conversationParticipant.deleteMany({where:{userId:'me'}})
 for(const [i,handler]of handlers.entries()) assert.equal((await handler(request(),ctx(i))).status,403)
}))
test('membership denial never calls the private snapshot reader',()=>fixture(async(db)=>{
 let called=false
 for(const target of [{conversationId:'room'},{matchId:'match'},{tournamentId:'season'}]) {
  await assert.rejects(access.readRoomGame(db,'out',target,async()=>{called=true}),{status:403})
 }
 assert.equal(called,false)
}))

import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { unreadSnapshot } from '../../src/lib/message-visibility.ts'
import * as actions from '../../src/lib/hub-app-operation.ts'
import { MATRIX } from '../../src/lib/hub-catalog.ts'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, names) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8')).replace(/^import .*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text) + `;return {${names}}`)(...Object.values(bindings))
}
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'hub-app-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT, username TEXT, color TEXT, sessionTokenHash TEXT, legacyTokenHashes TEXT)',
      'CREATE TABLE AppInstall(id TEXT PRIMARY KEY, userId TEXT, appId TEXT, status TEXT DEFAULT "connected", installedAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(userId,appId))',
      'CREATE TABLE Conversation(id TEXT PRIMARY KEY, appKey TEXT UNIQUE, isGroup BOOLEAN DEFAULT false, name TEXT, photo TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME, description TEXT DEFAULT "", inviteCode TEXT, ttlSeconds INTEGER DEFAULT 0, broadcastMode BOOLEAN DEFAULT false, screenPrivacy BOOLEAN DEFAULT false, slowModeSeconds INTEGER DEFAULT 0, isSelf BOOLEAN DEFAULT false)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT DEFAULT "member", joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, lastReadAt DATETIME DEFAULT CURRENT_TIMESTAMP, pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME, handRaisedAt DATETIME, screenPrivacy BOOLEAN DEFAULT false, manualUnread BOOLEAN DEFAULT false, draft TEXT, UNIQUE(userId,conversationId))',
      'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,createdAt DATETIME,parentId TEXT,topicId TEXT,deletedAt DATETIME,expiresAt DATETIME,viaAutomation BOOLEAN DEFAULT false)',
      "CREATE TABLE MessageReadObservation(participantId TEXT,messageId TEXT,observedAt DATETIME,source TEXT DEFAULT 'visible',PRIMARY KEY(participantId,messageId),FOREIGN KEY(participantId) REFERENCES ConversationParticipant(id) ON DELETE CASCADE)",
    ]) await db.$executeRawUnsafe(sql)
    for (const id of ['a','b','c']) await db.$executeRawUnsafe('INSERT INTO User VALUES (?,?,?,?,?,?)',id,id,id,'#F2A65A',hashSessionToken(`synthetic-${id}`),'[]')
    const auth = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireActionOwner')
    const routes = {}, hints = []
    for (const kind of ['install','community']) routes[kind] = await load(`src/app/api/hub/apps/[appId]/${kind}/route.ts`, {
      db, MATRIX, ...auth, ...actions, invalidateNativeReadState:(...args)=>hints.push(args), NextResponse:{json:Response.json}, safeJson:req=>req.json(),
    }, kind==='install'?'GET,POST,DELETE':'GET,POST')
    await run(db,routes,hints)
  } finally { await db.$disconnect(); await rm(dir,{recursive:true,force:true}) }
}
const ctx = (id='1') => ({params:Promise.resolve({appId:id})})
function req(method='GET', owner='a', token='synthetic-a') {
  return new Request(`https://app.test/?${owner ? `userId=${owner}` : ''}`,{method,
    headers:token?{Authorization:`Bearer ${token}`}:{}, ...(method==='GET'?{}:{body:JSON.stringify({userId:owner})})})
}
test('app reads and writes bind the supplied actor before private reads or mutations',()=>fixture(async(db,routes)=>{
  for(const kind of ['install','community']) for(const method of Object.keys(routes[kind])) {
    for(const token of [null,'forged','synthetic-b']) assert.equal((await routes[kind][method](req(method,'a',token),ctx())).status,token?403:401)
  }
  assert.equal(await db.appInstall.count(),0); assert.equal(await db.conversation.count(),0)
}))
test('catalog aliases cannot split app counts or provision shadow communities',()=>fixture(async(db,routes)=>{
  for(const id of ['01','1.0','1e0',' 1','0','101','NaN']) for(const kind of ['install','community']) {
    assert.equal((await routes[kind].POST(req('POST'),ctx(id))).status,404)
  }
  assert.equal(await db.appInstall.count(),0); assert.equal(await db.conversation.count(),0)
}))
test('connect and disconnect replay exact state, count and original installation time',()=>fixture(async(db,routes)=>{
  const first=await(await routes.install.POST(req('POST'),ctx())).json()
  const again=await(await routes.install.POST(req('POST'),ctx())).json()
  assert.equal(first.appVersion,1);assert.equal(first.appId,'1');assert.equal(first.userId,'a')
  assert.equal(first.installed,true);assert.equal(first.installs,1);assert.equal(again.installedAt,first.installedAt)
  await routes.install.POST(req('POST','b','synthetic-b'),ctx())
  for(let i=0;i<2;i++) {
    const removed=await(await routes.install.DELETE(req('DELETE'),ctx())).json()
    assert.equal(removed.installed,false);assert.equal(removed.status,null);assert.equal(removed.installs,1)
  }
  assert.equal((await actions.appInstallSnapshot(db,'1','b')).installed,true)
}))
test('bounded public connector preview does not impersonate a viewer or lose older own membership',()=>fixture(async(db,routes)=>{
  for(let i=0;i<9;i++) {
    const id=`u${i}`
    await db.$executeRawUnsafe('INSERT INTO User(id,name,color) VALUES (?,?,?)',id,id,'#F2A65A')
    await actions.setAppInstalled(db,'1',id,true)
  }
  await db.appInstall.update({where:{userId_appId:{userId:'u0',appId:'1'}},data:{installedAt:new Date('2000-01-01T00:00:00Z')}})
  const snapshot=await actions.appInstallSnapshot(db,'1','u0')
  assert.equal(snapshot.installed,true);assert.equal(snapshot.installs,9);assert.equal(snapshot.installers.length,6);assert.equal(snapshot.installers.some(row=>row.id==='u0'),false)
  const response=await routes.install.GET(req('GET',null,null),ctx())
  assert.match(response.headers.get('cache-control'),/private.*no-store/)
  const page=await response.json();assert.equal(page.userId,null);assert.equal(page.installed,false);assert.equal(page.installs,9)
}))
test('community join is stable on retry, preserves founder role, and nonmembers see no roster or private room fields',()=>fixture(async(db,routes)=>{
  const first=await(await routes.community.POST(req('POST'),ctx())).json()
  const second=await(await routes.community.POST(req('POST'),ctx())).json()
  const id=first.conversation.id
  assert.equal(second.conversation.id,id);assert.equal(second.memberCount,1);assert.equal(second.joined,true)
  assert.equal(second.conversation.members[0].role,'admin')
  await db.$executeRawUnsafe('UPDATE Conversation SET inviteCode=? WHERE id=?','private-invite',id)
  await db.$executeRawUnsafe('UPDATE ConversationParticipant SET draft=? WHERE userId=?','private draft','a')
  for(const [owner,token] of [[null,null],['b','synthetic-b'],['a','synthetic-a']]) {
    const page=await(await routes.community.GET(req('GET',owner,token),ctx())).json()
    assert.equal(page.memberCount,1);assert.equal(page.joined,owner==='a')
    assert.equal(page.conversation.members.length,owner==='a'?1:0)
    assert.equal(JSON.stringify(page).includes('private'),false)
    assert.equal(page.conversation.myDraft,undefined);assert.equal(page.conversation.inviteCode,undefined)
  }
  const joined=await(await routes.community.POST(req('POST','b','synthetic-b'),ctx())).json()
  assert.equal(joined.memberCount,2);assert.equal(joined.joined,true)
  assert.equal(joined.conversation.members.find(m=>m.id==='b').role,'member')
}))
test('failed founder membership rolls back the room, and install snapshot failure rolls back the change',()=>fixture(async(db)=>{
  await db.$executeRawUnsafe('CREATE TRIGGER reject_member BEFORE INSERT ON ConversationParticipant BEGIN SELECT RAISE(ABORT,"member unavailable"); END')
  await assert.rejects(actions.joinAppCommunity(db,'1','Community','a'))
  assert.equal(await db.conversation.count(),0)
  // Snapshot requires User; removing it faults after the upsert but inside the same transaction.
  await db.$executeRawUnsafe('DROP TABLE User')
  await assert.rejects(actions.setAppInstalled(db,'1','a',true))
  assert.equal(await db.appInstall.count(),0)
}))
test('simultaneous first joins converge on one app room and keep exactly one founder',()=>fixture(async(db)=>{
  const sends=['a','b'].map(owner=>actions.joinAppCommunity(db,'1','Community',owner))
  await Promise.allSettled(sends)
  for(const owner of ['a','b']) await actions.joinAppCommunity(db,'1','Community',owner)
  assert.equal(await db.conversation.count(),1)
  assert.equal(await db.conversationParticipant.count(),2)
  assert.equal(await db.conversationParticipant.count({where:{role:'admin'}}),1)
}))


test('community joins share exact historical exclusions and wake only the joining account', async t => {
  t.mock.timers.enable({apis:['Date'],now:new Date('2030-01-02T03:04:05.678Z')})
  try { await fixture(async(db,routes,hints)=>{
    const founder = await (await routes.community.POST(req('POST'),ctx())).json()
    const room = founder.conversation.id, at = new Date()
    assert.deepEqual(hints.pop(),['a',room])
    await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('history',${room},'a',${at})`
    assert.equal((await routes.community.POST(req('POST','b','synthetic-b'),ctx())).status,200)
    assert.deepEqual(hints.pop(),['b',room])
    assert.equal((await unreadSnapshot(db,room,'b',at,at)).unreadCount,0)
    const row = await db.messageReadObservation.findFirst({where:{messageId:'history'},select:{source:true}})
    assert.equal(row.source,'legacy','History is excluded, not Seen')
    await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('arrival',${room},'a',${at})`
    assert.equal((await routes.community.POST(req('POST','b','synthetic-b'),ctx())).status,200)
    assert.equal((await unreadSnapshot(db,room,'b',at,at)).unreadCount,1,'A retry cannot exempt an equal-time arrival')
  }) } finally { t.mock.timers.reset() }
})

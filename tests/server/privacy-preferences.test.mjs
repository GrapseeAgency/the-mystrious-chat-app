import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { stripTypeScriptTypes } from 'node:module'
import { privacyPreferenceEnabled, privacyPreferenceSnapshot } from '../../src/lib/privacy-preferences.ts'
import { receiptRecipients } from '../../src/lib/read-receipt.ts'

test('privacy distinguishes known unset defaults from unknown/malformed stored policy',()=>{
 for(const key of ['readReceipts','lastSeenVisible','typingVisible','notifPreviews']) {
  for(const raw of [null,'{}',JSON.stringify({[key]:true})]) assert.equal(privacyPreferenceEnabled(raw,key),true)
  for(const raw of [undefined,'','{broken','null','[]','false','123','"text"',
    JSON.stringify({[key]:false}),JSON.stringify({[key]:'true'}),JSON.stringify({[key]:1}),JSON.stringify({[key]:null})]) {
   assert.equal(privacyPreferenceEnabled(raw,key),false,`${key}: ${raw}`)
  }
 }
})

test('invalid or unloaded receipt preferences still synchronize only the reader own devices',()=>{
 for(const raw of [undefined,'{broken','null','[]','{"readReceipts":"false"}']) {
  assert.deepEqual(receiptRecipients('owner',['peer','owner','peer'],raw),['owner'])
 }
 assert.deepEqual(receiptRecipients('owner',['peer','owner'],null),['owner','peer'])
})

test('actual serializers hide timestamps without viewer context and preserve the owner unread boundary',async()=>{
 const source=(await readFile(new URL('../../src/lib/serializers.ts',import.meta.url),'utf8'))
  .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'')
  .replace(/^export \{[^}]+\} from [^\n]+$/gm,'').replace(/^export /gm,'')
 const {mapUser,membersForViewer}=new Function('privacyPreferenceEnabled','path',stripTypeScriptTypes(source,{mode:'transform'})+';return {mapUser,membersForViewer}')(privacyPreferenceEnabled,path)
 const stamp=new Date('2026-10-24T00:00:00Z')
 const user={id:'owner',name:'Owner',about:'',color:'amber',avatar:null,statusEmoji:null,statusText:null,createdAt:stamp,lastSeenAt:stamp}
 for(const preferences of [undefined,'{broken','null','[]','{"lastSeenVisible":"true","readReceipts":"true"}', '{"lastSeenVisible":false,"readReceipts":false}']) {
  const row={...user,preferences}
  const members=[{user:row,lastReadAt:stamp,role:'member'}]
  assert.equal(mapUser(row).lastSeenAt,null)
  assert.equal(membersForViewer(members,'peer')[0].lastReadAt,'1970-01-01T00:00:00.000Z')
  assert.equal(membersForViewer(members)[0].lastReadAt,'1970-01-01T00:00:00.000Z')
  assert.equal(membersForViewer(members,'owner')[0].lastReadAt,stamp.toISOString())
 }
 for(const preferences of [null,'{}','{"lastSeenVisible":true,"readReceipts":true}']) {
  const row={...user,preferences}
  assert.equal(mapUser(row).lastSeenAt,stamp.toISOString())
  assert.equal(membersForViewer([{user:row,lastReadAt:stamp,role:'admin'}],'peer')[0].lastReadAt,stamp.toISOString())
 }
})

async function relayFixture(fetch) {
 const file=await readFile(new URL('../../mini-services/pulse-socket/index.ts',import.meta.url),'utf8')
 const section=file.slice(file.indexOf('type PrivacyFlags ='),file.indexOf('/** Presence snapshot'))
 let now=100000
 const onlineUsers=new Map([['owner',new Set(['device'])]]),emissions=[]
 const policy=new Function('fetch','Date','NEXT_API_BASE','PRIVACY_KEY','PRIVACY_TTL_MS','onlineUsers','io',
  stripTypeScriptTypes(section,{mode:'transform'})+';return {fetchPrivacyFlags,privacyOf,refreshPresencePrivacy}')(fetch,{now:()=>now},'https://privacy.test','test',30000,onlineUsers,{emit:(...args)=>emissions.push(args)})
 return {...policy,emissions,advance:ms=>now+=ms}
}
test('actual relay policy fails closed for unknown expired missing malformed and failed snapshots',async()=>{
 let mode='allow', calls=0
 const f=await relayFixture(async()=>{
  calls++
  if(mode==='outage')throw new Error('offline')
  return Response.json({flags:mode==='allow'?{owner:{typingVisible:true,presenceVisible:true},unsolicited:{typingVisible:true,presenceVisible:true}}:
   mode==='malformed'?{owner:{typingVisible:'true',presenceVisible:true}}:{}})
 })
 const hidden={typingVisible:false,presenceVisible:false}
 assert.deepEqual(f.privacyOf('owner'),hidden)
 await f.fetchPrivacyFlags(['owner']);assert.equal(f.privacyOf('owner').presenceVisible,true)
 assert.deepEqual(f.privacyOf('unsolicited'),hidden)
 f.advance(30000);assert.deepEqual(f.privacyOf('owner'),hidden)
 for(const next of ['outage','malformed','missing']) {
  mode=next;await f.fetchPrivacyFlags(['owner']);assert.deepEqual(f.privacyOf('owner'),hidden)
  const before=calls;await f.fetchPrivacyFlags(['owner']);assert.equal(calls,before)
  f.advance(5000)
 }
 mode='allow';await f.fetchPrivacyFlags(['owner']);assert.equal(f.privacyOf('owner').typingVisible,true)
})

test('relay refresh covers more than 500 users and serializes concurrent disjoint requests',async()=>{
 const batches=[];let active=0,maxActive=0
 const f=await relayFixture(async url=>{
  active++;maxActive=Math.max(maxActive,active)
  const ids=new URL(url).searchParams.get('ids').split(',');batches.push(ids)
  await new Promise(resolve=>setImmediate(resolve))
  active--
  return Response.json({flags:Object.fromEntries(ids.map(id=>[id,{typingVisible:true,presenceVisible:true}]))})
 })
 await Promise.all([f.fetchPrivacyFlags(Array.from({length:1100},(_,i)=>`u${i}`)), f.fetchPrivacyFlags(['other']), f.fetchPrivacyFlags(['another'])])
 assert.equal(maxActive,1);assert.ok(batches.every(batch=>batch.length<=500))
 for(const id of ['u0','u500','u1099','other','another'])assert.equal(f.privacyOf(id).presenceVisible,true)
})

test('internal privacy endpoint uses strict stored policy for presence and typing',async()=>{
 const source=(await readFile(new URL('../../src/app/api/internal/privacy/route.ts',import.meta.url),'utf8')).replace(/^import .*$/gm,'').replace(/^export /gm,'')
 const bindings={NextResponse:{json:Response.json},process:{env:{CRON_SECRET:'test'}},privacyPreferenceEnabled,
  db:{user:{findMany:async()=>[{id:'hidden',preferences:'{broken'},{id:'default',preferences:null},{id:'off',preferences:'{"typingVisible":false,"lastSeenVisible":false}'}]}}}
 const get=new Function(...Object.keys(bindings),stripTypeScriptTypes(source,{mode:'transform'})+';return GET')(...Object.values(bindings))
 const response=await get(new Request('https://privacy.test/?ids=hidden,default,off',{headers:{'x-pulse-key':'test'}}))
 assert.deepEqual(await response.json(),{flags:{hidden:{typingVisible:false,presenceVisible:false},default:{typingVisible:true,presenceVisible:true},off:{typingVisible:false,presenceVisible:false}}})
})

test('idle connected clients receive privacy revocation without needing a reconnect or typing event',async()=>{
 let visible=true
 const f=await relayFixture(async()=>Response.json({flags:{owner:{typingVisible:visible,presenceVisible:visible}}}))
 await f.refreshPresencePrivacy()
 assert.deepEqual(f.emissions.at(-1),['presence:snapshot',{onlineUserIds:['owner']}])
 visible=false;f.advance(30000)
 await Promise.all([f.refreshPresencePrivacy(),f.refreshPresencePrivacy()])
 assert.deepEqual(f.emissions.at(-1),['presence:snapshot',{onlineUserIds:[]}])
 assert.equal(f.emissions.length,2)
})

test('settings repair preserves privacy refusals until an explicit boolean opt-in',async()=>{
 const strip=raw=>stripTypeScriptTypes(raw.replace(/^import .*$/gm,'').replace(/^export /gm,''),{mode:'transform'})
 const prefSource=await readFile(new URL('../../src/lib/prefs-defaults.ts',import.meta.url),'utf8')
 const mergePrefs=new Function('sanitizeConvThemeMap',strip(prefSource)+';return mergePrefs')(()=>{throw new Error('Unexpected theme-map branch')})
 const source=await readFile(new URL('../../src/app/api/settings/route.ts',import.meta.url),'utf8')
 let preferences='{broken',writes=0
 const bindings={NextResponse:{json:Response.json},mergePrefs,privacyPreferenceSnapshot,
  db:{user:{findUnique:async()=>({preferences}),update:async({data})=>{preferences=data.preferences;writes++}}}}
 const {GET,PATCH}=new Function(...Object.keys(bindings),strip(source)+';return {GET,PATCH}')(...Object.values(bindings))
 const get=await GET(new Request('https://settings.test/?userId=owner'))
 assert.equal((await get.json()).preferences.readReceipts,false)
 const patch=data=>PATCH(new Request('https://settings.test/',{method:'PATCH',body:JSON.stringify(data)}))
 for(const body of [null,[],{userId:'owner',preferences:{readReceipts:'true'}}]) assert.equal((await patch(body)).status,400)
 assert.equal(writes,0)
 assert.equal((await patch({userId:'owner',preferences:{density:'compact'}})).status,200)
 let stored=JSON.parse(preferences)
 assert.equal(stored.density,'compact');assert.equal(stored.readReceipts,false)
 assert.equal(stored.typingVisible,false);assert.equal(stored.lastSeenVisible,false);assert.equal(stored.notifPreviews,false)
 await patch({userId:'owner',preferences:{readReceipts:true}})
 stored=JSON.parse(preferences);assert.equal(stored.readReceipts,true);assert.equal(stored.lastSeenVisible,false)
})

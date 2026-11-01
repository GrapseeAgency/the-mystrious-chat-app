import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const {PrismaClient,Prisma}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path,bindings,expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'story-privacy-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const name of ['User','Conversation','ConversationParticipant','UserBlock','StatusStory','StoryView']) {
   const model=Prisma.dmmf.datamodel.models.find(m=>m.name===name)
   const columns=model.fields.filter(f=>f.kind!=='object'&&!f.isList).map(f=>{
    const type=['Int','Boolean','BigInt'].includes(f.type)?'INTEGER':f.type==='Float'?'REAL':f.type==='DateTime'?'DATETIME':'TEXT'
    let def=''
    if(typeof f.default==='string')def=` DEFAULT '${f.default.replaceAll("'","''")}'`
    else if(typeof f.default==='number'||typeof f.default==='boolean')def=` DEFAULT ${Number(f.default)}`
    else if(f.default?.name==='now')def=' DEFAULT CURRENT_TIMESTAMP'
    return `"${f.name}" ${type}${f.isId?' PRIMARY KEY':''}${def}`
   })
   await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
  }
  await db.$executeRawUnsafe('CREATE UNIQUE INDEX story_view_owner ON StoryView(storyId,userId)')
  for(const id of ['me','peer'])await db.$executeRawUnsafe('INSERT INTO User(id,name,sessionTokenHash,legacyTokenHashes,preferences) VALUES(?,?,?,?,?)',id,'Name '+id,hashSessionToken(`synthetic-${id}`),'[]','{}')
  await db.conversation.create({data:{id:'room',isGroup:true}})
  for(const id of ['me','peer'])await db.conversationParticipant.create({data:{id:'p-'+id,userId:id,conversationId:'room'}})
  const access=await load('src/lib/story-access.ts',{Prisma},'({storyAudience,storyUnblocked,storyJson,privateStoryResponse,encodeStoryCursor,parseStoryCursor,parseStoryPageLimit,STORY_FEED_PAGE_MAX,STORY_VIEWERS_PAGE_MAX,storyReceiptDisclosed,storyViewerInAuthorAudience})')
  const prefs=await load('src/lib/privacy-preferences.ts',{},'({privacyPreferenceSnapshot})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,Prisma,randomUUID,...access,...prefs,requireActionOwner,AVATAR_COLORS:['emerald','rose','amber','violet','teal','orange','pink','cyan'],safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',
   // The disclosure test asserts column truth, not the prefs sanitizer (the
   // sanitizer's own behavior is covered by the settings suite).
   mergePrefs:raw=>({notifPreviews:true,readReceipts:true,lastSeenVisible:true,typingVisible:true,...raw})}
  const feed=await load('src/app/api/stories/route.ts',bindings,'({GET,POST})')
  const views=await load('src/app/api/stories/[id]/view/route.ts',bindings,'({GET,POST})')
  const settings=await load('src/app/api/settings/route.ts',{...bindings,NextResponse:{json:(body,init)=>new Response(JSON.stringify(body),init)}},'({GET,PATCH})')
  const create=async(id,userId,at=Date.now())=>db.$executeRaw`INSERT INTO StatusStory(id,userId,caption,background,createdAt,expiresAt) VALUES(${id},${userId},${id},'emerald',${at},${at+86400000})`
  await run(db,{feed,views,settings,create})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=id=>({params:Promise.resolve({id})})
const req=(method='GET',user='me',body={},token=`synthetic-${user}`,path='')=>new Request(`https://story.test/${path}?requesterId=${user}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'||method==='DELETE'?{}:{body:JSON.stringify({requesterId:user,...body})})})
const settingsReq=(method,user,body={})=>new Request(`https://story.test/?userId=${user}`,{method,headers:{Authorization:`Bearer synthetic-${user}`},...(method==='PATCH'?{body:JSON.stringify({userId:user,...body})}:{})})

test('story view receipts obey the viewer-owned storyViewReceipts privacy, live',()=>fixture(async(db,h)=>{
 await h.create('story','me')
 const view=await(await h.views.POST(req('POST','peer',{}),ctx('story'))).json()
 assert.equal(view.receiptVisible,true);assert.equal(view.viewCount,1)
 // Off: the receipt stops disclosing everywhere - list AND both counts.
 const patched=await(await h.settings.PATCH(settingsReq('PATCH','peer',{preferences:{storyViewReceipts:false}}))).json()
 assert.equal(patched.preferences.storyViewReceipts,false)
 const col=await db.$queryRaw`SELECT storyViewReceipts AS flag FROM User WHERE id='peer'`
 assert.equal(Number(col[0].flag),0)
 const viewers=await(await h.views.GET(req('GET','me',{},'synthetic-me'),ctx('story'))).json()
 assert.deepEqual(viewers.viewers,[])
 const feed=await(await h.feed.GET(req('GET','me'))).json()
 assert.equal(feed.groups[0].stories[0].viewCount,0)
 const hidden=await(await h.views.POST(req('POST','peer',{}),ctx('story'))).json()
 assert.equal(hidden.receiptVisible,false);assert.equal(hidden.viewCount,0)
 // The viewer keeps their OWN seen state (their feed ring clears).
 const peerFeed=await(await h.feed.GET(req('GET','peer'))).json()
 assert.equal(peerFeed.groups[0].stories[0].viewedByMe,true)
 // Back on (live semantics): the past receipt discloses again - the row was
 // always there, only disclosure was gated.
 await h.settings.PATCH(settingsReq('PATCH','peer',{preferences:{storyViewReceipts:true}}))
 const again=await(await h.views.GET(req('GET','me'),ctx('story'))).json()
 assert.deepEqual(again.viewers.map(v=>v.userId),['peer'])
 assert.equal((await(await h.feed.GET(req('GET','me'))).json()).groups[0].stories[0].viewCount,1)
}))

test('settings PATCH rejects non-boolean receipt privacy and GET mirrors the column',()=>fixture(async(db,h)=>{
 assert.equal((await h.settings.PATCH(settingsReq('PATCH','me',{preferences:{storyViewReceipts:'yes'}}))).status,400)
 const get=await(await h.settings.GET(settingsReq('GET','me'))).json()
 assert.equal(get.preferences.storyViewReceipts,true)
 // An unrelated patch must not disturb the receipt column.
 await h.settings.PATCH(settingsReq('PATCH','me',{preferences:{readReceipts:false}}))
 const after=await(await h.settings.GET(settingsReq('GET','me'))).json()
 assert.equal(after.preferences.storyViewReceipts,true)
 assert.equal(after.preferences.readReceipts,false)
}))

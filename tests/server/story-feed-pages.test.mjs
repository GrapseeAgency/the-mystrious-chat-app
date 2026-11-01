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
 const dir=await mkdtemp(join(tmpdir(),'story-pages-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
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
  for(const id of ['me','peer','third'])await db.$executeRawUnsafe('INSERT INTO User(id,name,sessionTokenHash,legacyTokenHashes) VALUES(?,?,?,?)',id,'Name '+id,hashSessionToken(`synthetic-${id}`),'[]')
  await db.conversation.create({data:{id:'room',isGroup:true}})
  for(const id of ['me','peer','third'])await db.conversationParticipant.create({data:{id:'p-'+id,userId:id,conversationId:'room'}})
  const access=await load('src/lib/story-access.ts',{Prisma},'({storyAudience,storyUnblocked,storyJson,privateStoryResponse,encodeStoryCursor,parseStoryCursor,parseStoryPageLimit,STORY_FEED_PAGE_MAX,STORY_VIEWERS_PAGE_MAX,storyReceiptDisclosed,storyViewerInAuthorAudience})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,Prisma,randomUUID,...access,requireActionOwner,AVATAR_COLORS:['emerald','rose','amber','violet','teal','orange','pink','cyan'],safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():''}
  const feed=await load('src/app/api/stories/route.ts',bindings,'({GET,POST})')
  const views=await load('src/app/api/stories/[id]/view/route.ts',bindings,'({GET,POST})')
  const create=async(id,userId,at)=>db.$executeRaw`INSERT INTO StatusStory(id,userId,caption,background,createdAt,expiresAt) VALUES(${id},${userId},${id},'emerald',${at},${at+86400000})`
  await run(db,{feed,views,create})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const page=(user='me',params='')=>new Request(`https://story.test/?requesterId=${user}${params}`,{headers:{Authorization:`Bearer synthetic-${user}`}})
const feedIds=body=>body.groups.flatMap(g=>g.stories.map(s=>s.id))

test('bounded feed pages walk every story exactly once, newest-first',()=>fixture(async(db,h)=>{
 const base=Date.now()-3_600_000
 // 7 rows across 3 authors; ids chosen so tied timestamps exercise the id tiebreak.
 for(const [id,owner,at]of [['s1','me',base],['s2','me',base+1000],['p1','peer',base+2000],['p2','peer',base+2000],['t1','third',base+3000],['s3','me',base+4000],['p3','peer',base+5000]])await h.create(id,owner,at)
 const seen=[]
 let cursor=null
 for(let pageIndex=0;pageIndex<10;pageIndex++) {
  const q=cursor?`&limit=3&cursor=${encodeURIComponent(cursor)}`:'&limit=3'
  const response=await h.feed.GET(page('me',q));assert.equal(response.status,200)
  const body=await response.json()
  assert.ok(body.groups.length<=3)
  seen.push(...feedIds(body))
  if(!body.hasMore){assert.equal(body.nextCursor,null);break}
  assert.ok(body.nextCursor);cursor=body.nextCursor
 }
 // The walk covers every story exactly once. Page composition follows the
 // wire contract: mine group first, then authors by freshest story; stories
 // inside a group stay oldest-first (so peer's tied p1/p2 order by id ASC).
 assert.deepEqual([...seen].sort(),['p1','p2','p3','s1','s2','s3','t1'].sort())
 assert.deepEqual(seen,['s3','p3','t1','s2','p1','p2','s1'])
 // Inside each group stories stay oldest-first (viewer playback order).
 const feed=await(await h.feed.GET(page('me'))).json()
 const peer=feed.groups.find(g=>g.user.id==='peer')
 assert.deepEqual(peer.stories.map(s=>s.id),['p1','p2','p3'])
 assert.equal(feed.groups[0].mine,true)
}))

test('pagination input is validated and legacy callers keep the full shape',()=>fixture(async(db,h)=>{
 const base=Date.now()-3_600_000
 for(const [id,at]of [['s1',base],['s2',base+1000],['s3',base+2000]])await h.create(id,'me',at)
 assert.equal((await h.feed.GET(page('me','&limit=0'))).status,400)
 assert.equal((await h.feed.GET(page('me','&limit=61'))).status,400)
 assert.equal((await h.feed.GET(page('me','&limit=abc'))).status,400)
 assert.equal((await h.feed.GET(page('me','&cursor=not-a-cursor'))).status,400)
 assert.equal((await h.feed.GET(page('me','&cursor=999999999999999999999.s1'))).status,400)
 const legacy=await(await h.feed.GET(page('me'))).json()
 assert.deepEqual(legacy.nextCursor,null);assert.equal(legacy.hasMore,false);assert.equal(legacy.groups.length,1)
 // An exact-size page reports hasMore=false honestly (the probe row is absent).
 const exact=await(await h.feed.GET(page('me','&limit=3'))).json()
 assert.deepEqual(feedIds(exact).length,3);assert.equal(exact.hasMore,false)
 // A malformed cursor (no dot separator) is rejected, never loosely parsed.
 const tail=await h.feed.GET(page('me','&limit=3&cursor=garbage'))
 assert.equal(tail.status,400)
}))

test('expired rows never appear on any page and views paginate per page window',()=>fixture(async(db,h)=>{
 const base=Date.now()-3_600_000
 for(const [id,owner,at]of [['live1','me',base],['dead','peer',base+500],['live2','peer',base+1000]])await h.create(id,owner,at)
 await db.$executeRaw`UPDATE StatusStory SET expiresAt = ${base - 1} WHERE id = 'dead'`
 const first=await(await h.feed.GET(page('me','&limit=1'))).json()
 assert.deepEqual(feedIds(first),['live2']);assert.equal(first.hasMore,true)
 const second=await(await h.feed.GET(page('me',`&limit=1&cursor=${encodeURIComponent(first.nextCursor)}`))).json()
 assert.deepEqual(feedIds(second),['live1']);assert.equal(second.hasMore,false)
 // Viewer pages: the owner walks their receipt list with the same honesty.
 await db.$executeRaw`INSERT INTO StoryView(id,storyId,userId,createdAt) VALUES('v1','live1','peer',${base+100})`
 await db.$executeRaw`INSERT INTO StoryView(id,storyId,userId,createdAt) VALUES('v2','live1','third',${base+200})`
 const ctx={params:Promise.resolve({id:'live1'})}
 const v1=await(await h.views.GET(page('me','&limit=1'),ctx)).json()
 assert.deepEqual(v1.viewers.map(v=>v.userId),['peer']);assert.equal(v1.hasMore,true)
 const v2=await(await h.views.GET(page('me',`&limit=1&cursor=${encodeURIComponent(v1.nextCursor)}`),ctx)).json()
 assert.deepEqual(v2.viewers.map(v=>v.userId),['third']);assert.equal(v2.hasMore,false)
 assert.equal((await h.views.GET(page('me','&limit=0'),ctx)).status,400)
 assert.equal((await h.views.GET(page('me','&limit=101'),ctx)).status,400)
 assert.equal((await h.views.GET(page('peer','&limit=1'),ctx)).status,403)
}))

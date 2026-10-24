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
 const dir=await mkdtemp(join(tmpdir(),'stories-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
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
  for(const id of ['me','peer','blocked','stranger'])await db.$executeRawUnsafe('INSERT INTO User(id,name,sessionTokenHash,legacyTokenHashes) VALUES(?,?,?,?)',id,'Name '+id,hashSessionToken(`synthetic-${id}`),'[]')
  await db.conversation.create({data:{id:'room',isGroup:true}})
  for(const id of ['me','peer','blocked'])await db.conversationParticipant.create({data:{id:'p-'+id,userId:id,conversationId:'room'}})
  const access=await load('src/lib/story-access.ts',{Prisma},'({storyAudience,storyUnblocked,storyJson,privateStoryResponse})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,Prisma,randomUUID,...access,requireActionOwner,AVATAR_COLORS:['emerald','rose','amber','violet','teal','orange','pink','cyan'],safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():''}
  const feed=await load('src/app/api/stories/route.ts',bindings,'({GET,POST})')
  const views=await load('src/app/api/stories/[id]/view/route.ts',bindings,'({GET,POST})')
  const remove=await load('src/app/api/stories/[id]/route.ts',bindings,'DELETE')
  const create=async(id,userId,at=Date.now())=>db.$executeRaw`INSERT INTO StatusStory(id,userId,caption,background,createdAt,expiresAt) VALUES(${id},${userId},${id},'emerald',${at},${at+86400000})`
  const mark=async(id,userId)=>db.$executeRaw`INSERT INTO StoryView(id,storyId,userId,createdAt) VALUES(${randomUUID()},${id},${userId},${Date.now()})`
  await run(db,{feed,views,remove,create,mark})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=id=>({params:Promise.resolve({id})})
const req=(method='GET',user='me',body={},token=`synthetic-${user}`)=>new Request(`https://story.test/?requesterId=${user}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'||method==='DELETE'?{}:{body:JSON.stringify({requesterId:user,...body})})})
const feedIds=body=>body.groups.flatMap(g=>g.stories.map(s=>s.id))

test('story feed, publish, views and deletion bind the requested owner credential',()=>fixture(async(db,h)=>{
 await h.create('own','me')
 for(const [handler,method]of [[h.feed.GET,'GET'],[h.feed.POST,'POST'],[h.views.GET,'GET'],[h.views.POST,'POST'],[h.remove,'DELETE']]) {
  for(const [token,status]of [[null,401],['synthetic-peer',403]]) {
   const response=await handler(req(method,'me',{caption:'test'},token),ctx('own'))
   assert.equal(response.status,status);assert.match(response.headers.get('cache-control'),/private.*no-store/);assert.equal(response.headers.get('vary'),'Authorization')
  }
 }
 assert.equal(await db.statusStory.count(),1);assert.equal(await db.storyView.count(),0)
}))
test('feed includes only own and current unblocked peer stories with stable tied ordering',()=>fixture(async(db,h)=>{
 const now=Date.now()
 for(const [id,owner]of [['own','me'],['b','peer'],['a','peer'],['hidden','blocked'],['outside','stranger']])await h.create(id,owner,now)
 await h.create('expired','peer',now-2*86400000)
 await db.userBlock.create({data:{blockerId:'blocked',blockedId:'me'}})
 let response=await h.feed.GET(req());assert.equal(response.status,200)
 const body=await response.json();assert.equal(body.groups[0].mine,true);assert.deepEqual(feedIds(body),['own','a','b'])
 await db.userBlock.deleteMany();await db.userBlock.create({data:{blockerId:'me',blockedId:'peer'}})
 assert.deepEqual(feedIds(await(await h.feed.GET(req())).json()),['own','hidden'])
 await db.conversationParticipant.deleteMany({where:{userId:'me'}})
 assert.deepEqual(feedIds(await(await h.feed.GET(req())).json()),['own'])
}))
test('hidden and expired story IDs cannot create views or leak view counts',()=>fixture(async(db,h)=>{
 await h.create('peer-story','peer');await h.create('expired','peer',Date.now()-2*86400000)
 for(const [user,id]of [['stranger','peer-story'],['me','expired'],['me','missing']])assert.equal((await h.views.POST(req('POST',user),ctx(id))).status,404)
 await db.userBlock.create({data:{blockerId:'peer',blockedId:'me'}})
 assert.equal((await h.views.POST(req('POST'),ctx('peer-story'))).status,404)
 await db.userBlock.deleteMany();await db.userBlock.create({data:{blockerId:'me',blockedId:'peer'}})
 assert.equal((await h.views.POST(req('POST'),ctx('peer-story'))).status,404)
 await db.userBlock.deleteMany();await db.conversationParticipant.deleteMany({where:{userId:'me'}})
 assert.equal((await h.views.POST(req('POST'),ctx('peer-story'))).status,404)
 assert.equal(await db.storyView.count(),0)
}))
test('authorized view marks are idempotent and owner self-views are not recorded',()=>fixture(async(db,h)=>{
 await h.create('story','peer')
 for(let i=0;i<2;i++)assert.deepEqual(await(await h.views.POST(req('POST'),ctx('story'))).json(),{viewCount:1})
 assert.deepEqual(await(await h.views.POST(req('POST','peer'),ctx('story'))).json(),{viewCount:1,owner:true})
 assert.equal(await db.storyView.count(),1)
 const group=(await(await h.feed.GET(req())).json()).groups[0];assert.equal(group.allSeen,true);assert.equal(group.stories[0].viewedByMe,true);assert.equal(group.stories[0].viewCount,1)
}))
test('owner viewers and counts hide currently blocked identities and ignore orphaned legacy views',()=>fixture(async(db,h)=>{
 await h.create('story','me');await h.mark('story','peer');await h.mark('story','blocked');await h.mark('story','removed-user')
 await db.userBlock.create({data:{blockerId:'blocked',blockedId:'me'}})
 assert.equal((await h.views.GET(req('GET','peer'),ctx('story'))).status,403)
 const viewers=await(await h.views.GET(req(),ctx('story'))).json();assert.deepEqual(viewers.viewers.map(v=>v.userId),['peer'])
 assert.equal((await(await h.feed.GET(req())).json()).groups[0].stories[0].viewCount,1)
 assert.deepEqual(await(await h.views.POST(req('POST'),ctx('story'))).json(),{viewCount:1,owner:true})
 await db.$executeRaw`UPDATE StatusStory SET expiresAt = 0 WHERE id = 'story'`
 assert.equal((await h.views.GET(req(),ctx('story'))).status,404)
}))
test('story and viewer deletion commit together and roll back a partial database failure',()=>fixture(async(db,h)=>{
 await h.create('story','me');await h.mark('story','peer')
 assert.equal((await h.remove(req('DELETE','peer'),ctx('story'))).status,403)
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_story_delete BEFORE DELETE ON StatusStory BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
 await assert.rejects(h.remove(req('DELETE'),ctx('story')))
 assert.equal(await db.statusStory.count(),1);assert.equal(await db.storyView.count(),1)
 await db.$executeRawUnsafe('DROP TRIGGER refuse_story_delete')
 assert.deepEqual(await(await h.remove(req('DELETE'),ctx('story'))).json(),{ok:true})
 assert.equal(await db.statusStory.count(),0);assert.equal(await db.storyView.count(),0)
}))
test('valid publication keeps the native wire shape and UTF16 caption admission',()=>fixture(async(db,h)=>{
 assert.equal((await h.feed.POST(req('POST','me',{caption:'😀'.repeat(141)}))).status,400)
 const response=await h.feed.POST(req('POST','me',{caption:'😀'.repeat(140),background:'amber'}));assert.equal(response.status,201)
 const {story}=await response.json();assert.equal(story.caption.length,280);assert.equal(story.kind,'text');assert.equal(story.background,'amber');assert.equal(Date.parse(story.expiresAt)-Date.parse(story.createdAt),86400000)
 assert.equal(await db.statusStory.count(),1)
}))

test('large shared-group audience does not expand into SQLite bind parameters',()=>fixture(async(db,h)=>{
 await db.$executeRawUnsafe("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<33000) INSERT INTO User(id,name) SELECT 'large-user-'||x,'Synthetic peer' FROM n")
 await db.$executeRawUnsafe("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<33000) INSERT INTO ConversationParticipant(id,userId,conversationId) SELECT 'large-p-'||x,'large-user-'||x,'room' FROM n")
 await h.create('own','me');await h.create('peer-story','peer')
 const response=await h.feed.GET(req())
 assert.equal(response.status,200);assert.deepEqual(feedIds(await response.json()),['own','peer-story'])
}))

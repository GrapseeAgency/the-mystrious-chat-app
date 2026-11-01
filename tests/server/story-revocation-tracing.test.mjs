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
 const dir=await mkdtemp(join(tmpdir(),'story-revoke-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
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
  for(const id of ['me','peer'])await db.$executeRawUnsafe('INSERT INTO User(id,name,sessionTokenHash,legacyTokenHashes) VALUES(?,?,?,?)',id,'Name '+id,hashSessionToken(`synthetic-${id}`),'[]')
  await db.conversation.create({data:{id:'room',isGroup:true}})
  for(const id of ['me','peer'])await db.conversationParticipant.create({data:{id:'p-'+id,userId:id,conversationId:'room'}})
  const access=await load('src/lib/story-access.ts',{Prisma},'({storyAudience,storyUnblocked,storyJson,privateStoryResponse,encodeStoryCursor,parseStoryCursor,parseStoryPageLimit,STORY_FEED_PAGE_MAX,STORY_VIEWERS_PAGE_MAX,storyReceiptDisclosed,storyViewerInAuthorAudience})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,Prisma,randomUUID,...access,requireActionOwner,AVATAR_COLORS:['emerald','rose','amber','violet','teal','orange','pink','cyan'],safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():''}
  const feed=await load('src/app/api/stories/route.ts',bindings,'({GET,POST})')
  const views=await load('src/app/api/stories/[id]/view/route.ts',bindings,'({GET,POST})')
  const remove=await load('src/app/api/stories/[id]/route.ts',bindings,'DELETE')
  const create=async(id,userId,at=Date.now())=>db.$executeRaw`INSERT INTO StatusStory(id,userId,caption,background,createdAt,expiresAt) VALUES(${id},${userId},${id},'emerald',${at},${at+86400000})`
  await run(db,{feed,views,remove,create})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=id=>({params:Promise.resolve({id})})
const req=(method='GET',user='me',body={},token=`synthetic-${user}`)=>new Request(`https://story.test/?requesterId=${user}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'||method==='DELETE'?{}:{body:JSON.stringify({requesterId:user,...body})})})

test('revocation traces every retained viewer: block, removal and owner deletion',()=>fixture(async(db,h)=>{
 // Section 9 item 8: a viewer modal opened BEFORE each revocation must heal
 // through the SAME server predicates - no cached viewer survives a revocation.
 await h.create('story','me')
 assert.equal((await h.views.POST(req('POST','peer'),ctx('story'))).status,200)
 const before=await(await h.views.GET(req('GET','me'),ctx('story'))).json()
 assert.deepEqual(before.viewers.map(v=>v.userId),['peer'])
 // 1. Block mid-story: the open modal's next read drops the viewer AND the count.
 await db.userBlock.create({data:{blockerId:'me',blockedId:'peer'}})
 const blocked=await(await h.views.GET(req('GET','me'),ctx('story'))).json()
 assert.deepEqual(blocked.viewers,[])
 assert.equal((await(await h.feed.GET(req('GET','me'))).json()).groups[0].stories[0].viewCount,0)
 // The blocked viewer's own view-mark replay is refused (they lost audience).
 assert.equal((await h.views.POST(req('POST','peer'),ctx('story'))).status,404)
 assert.deepEqual((await(await h.feed.GET(req('GET','peer'))).json()).groups,[])
 // Unblock restores the audience and the disclosed receipt (live predicate).
 await db.userBlock.deleteMany()
 const healed=await(await h.views.GET(req('GET','me'),ctx('story'))).json()
 assert.deepEqual(healed.viewers.map(v=>v.userId),['peer'])
 // 2. Audience removal (left the shared conversation): same healing path.
 await db.conversationParticipant.delete({where:{id:'p-peer'}})
 const removed=await(await h.views.GET(req('GET','me'),ctx('story'))).json()
 assert.deepEqual(removed.viewers,[])
 assert.equal((await h.views.POST(req('POST','peer'),ctx('story'))).status,404)
 // 3. Owner deletion while the modal is open: every read becomes 404.
 await h.remove(req('DELETE','me'),ctx('story'))
 assert.equal((await h.views.GET(req('GET','me'),ctx('story'))).status,404)
 assert.equal((await h.views.POST(req('POST','peer'),ctx('story'))).status,404)
 assert.equal(await db.storyView.count(),0)
 // 4. Expiry revokes identically for a retained modal.
 await h.create('story2','me')
 await h.views.POST(req('POST','peer'),ctx('story2'))
 await db.$executeRaw`UPDATE StatusStory SET expiresAt = 0 WHERE id = 'story2'`
 assert.equal((await h.views.GET(req('GET','me'),ctx('story2'))).status,404)
 assert.equal((await h.views.POST(req('POST','peer'),ctx('story2'))).status,404)
}))

test('view-mark retry lifetime: replays stay idempotent across revocation and restart',()=>fixture(async(db,h)=>{
 // Section 9 item 9: a durable client journal replays the same view mark after
 // process loss; the server answer must be identical whether the receipt was
 // recorded before or after a revocation window, and never double-counts.
 await h.create('story','me')
 for(let i=0;i<3;i++) {
  const ack=await(await h.views.POST(req('POST','peer'),ctx('story'))).json()
  assert.equal(ack.viewCount,1)
 }
 assert.equal(await db.storyView.count(),1)
 await db.userBlock.create({data:{blockerId:'me',blockedId:'peer'}})
 // The journal replays into a revoked audience: 404, no row resurrection.
 assert.equal((await h.views.POST(req('POST','peer'),ctx('story'))).status,404)
 assert.equal(await db.storyView.count(),1)
 await db.userBlock.deleteMany()
 // The same journal replay after audience restoration converges again.
 assert.equal((await(await h.views.POST(req('POST','peer'),ctx('story'))).json()).viewCount,1)
 assert.equal(await db.storyView.count(),1)
}))

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
 const dir=await mkdtemp(join(tmpdir(),'story-replay-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
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
  await db.$executeRawUnsafe('CREATE UNIQUE INDEX status_story_identity ON StatusStory(userId,clientId)')
  for(const id of ['me','peer'])await db.$executeRawUnsafe('INSERT INTO User(id,name,sessionTokenHash,legacyTokenHashes) VALUES(?,?,?,?)',id,'Name '+id,hashSessionToken(`synthetic-${id}`),'[]')
  await db.$executeRawUnsafe(`INSERT INTO Conversation(id,isGroup) VALUES('room',1)`)
  for(const id of ['me','peer'])await db.$executeRawUnsafe(`INSERT INTO ConversationParticipant(id,userId,conversationId) VALUES('p-${id}','${id}','room')`)
  const access=await load('src/lib/story-access.ts',{Prisma},'({storyAudience,storyUnblocked,storyJson,privateStoryResponse,encodeStoryCursor,parseStoryCursor,parseStoryPageLimit,STORY_FEED_PAGE_MAX,STORY_VIEWERS_PAGE_MAX,storyReceiptDisclosed,storyViewerInAuthorAudience})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,Prisma,randomUUID,...access,requireActionOwner,AVATAR_COLORS:['emerald','rose','amber','violet','teal','orange','pink','cyan'],safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():''}
  const feed=await load('src/app/api/stories/route.ts',bindings,'({GET,POST})')
  await run(db,{feed})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const req=(method='POST',user='me',body={},token=`synthetic-${user}`)=>new Request(`https://story.test/?requesterId=${user}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify({requesterId:user,...body})})})

test('a journaled publication replays the SAME story row for the SAME identity',()=>fixture(async(db,h)=>{
 const first=await h.feed.POST(req('POST','me',{caption:'lost-ack status',background:'amber',clientId:'replay-identity-000000001'}))
 assert.equal(first.status,201)
 const created=(await first.json());assert.equal(created.replayed,false)
 // The ACK is lost and the process dies; the journal replays the SAME clientId
 // with the SAME content - the server returns the SAME row, never duplicates.
 const replay=await h.feed.POST(req('POST','me',{caption:'lost-ack status',background:'amber',clientId:'replay-identity-000000001'}))
 assert.equal(replay.status,200)
 const body=await replay.json();assert.equal(body.replayed,true);assert.equal(body.story.id,created.story.id)
 assert.equal(await db.statusStory.count(),1)
}))

test('reusing a publication identity with different content is a 409',()=>fixture(async(db,h)=>{
 await h.feed.POST(req('POST','me',{caption:'original text',clientId:'replay-identity-000000002'}))
 const mismatch=await h.feed.POST(req('POST','me',{caption:'different text',clientId:'replay-identity-000000002'}))
 assert.equal(mismatch.status,409)
 const image=await h.feed.POST(req('POST','me',{caption:'original text',background:'amber',clientId:'replay-identity-000000002'}))
 assert.equal(image.status,409)
 assert.equal(await db.statusStory.count(),1)
}))

test('a racing replay resolves through the (userId, clientId) unique constraint',()=>fixture(async(db,h)=>{
 // Simulate the racing winner: the row already exists when the replayed INSERT fires.
 const now=Date.now()
 await db.$executeRaw`INSERT INTO StatusStory(id,userId,caption,background,createdAt,expiresAt,clientId) VALUES('raced','me','same text','emerald',${now},${now+86400000},'replay-identity-000000003')`
 const replay=await h.feed.POST(req('POST','me',{caption:'same text',clientId:'replay-identity-000000003'}))
 assert.equal(replay.status,200)
 const body=await replay.json();assert.equal(body.replayed,true);assert.equal(body.story.id,'raced')
 const conflicting=await h.feed.POST(req('POST','me',{caption:'other text',clientId:'replay-identity-000000003'}))
 assert.equal(conflicting.status,409)
 assert.equal(await db.statusStory.count(),1)
}))

test('publication identities are per-user and strictly validated',()=>fixture(async(db,h)=>{
 await h.feed.POST(req('POST','me',{caption:'mine',clientId:'replay-identity-000000004'}))
 // The SAME clientId under a different author is a DIFFERENT identity.
 const other=await h.feed.POST(req('POST','peer',{caption:'theirs',clientId:'replay-identity-000000004'}))
 assert.equal(other.status,201)
 assert.equal(await db.statusStory.count(),2)
 for(const bad of ['short','has spaces inside of it','<script>injection-tag-here']) {
  assert.equal((await h.feed.POST(req('POST','me',{caption:'x',clientId:bad}))).status,400)
 }
 assert.equal((await h.feed.POST(req('POST','me',{caption:'legacy path stays open'}))).status,201)
 assert.equal(await db.statusStory.count(),3)
 // The feed still carries every story exactly once (crash-gap closure: the
 // replayed publication surfaces in the cache refresh exactly one time).
 const rows=await db.$queryRaw`SELECT id FROM StatusStory ORDER BY createdAt DESC`
 assert.equal(rows.length,3)
}))

test('native adoption path: publish + replay + feed keeps one identity row across both',()=>fixture(async(db,h)=>{
 // Section 9 item 4 (publication-to-cache crash gap): acceptance happens, the
 // process dies BEFORE the local cache refresh; restore replays the identity
 // and the feed converges without a duplicate.
 const a=await h.feed.POST(req('POST','me',{caption:'crash between accept and cache',clientId:'replay-identity-000000005'}))
 const b=await h.feed.POST(req('POST','me',{caption:'crash between accept and cache',clientId:'replay-identity-000000005'}))
 assert.equal(a.status,201);assert.equal(b.status,200)
 assert.equal((await a.json()).story.id,(await b.json()).story.id)
 const feed=await(await h.feed.GET(req('GET','me',{},'synthetic-me'))).json()
 const mine=feed.groups.find(g=>g.mine)
 assert.equal(mine.stories.filter(s=>s.caption==='crash between accept and cache').length,1)
}))

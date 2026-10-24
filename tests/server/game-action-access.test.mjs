import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { GameOperationError } from '../../src/lib/game-contract.ts'
async function load(path, bindings, name) {
  const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
  return new Function(...Object.keys(bindings),stripTypeScriptTypes(text)+`;return ${name}`)(...Object.values(bindings))
}
const routes=[['games/route.ts','POST'],['games/[id]/join/route.ts','POST'],['games/[id]/move/route.ts','POST'],
  ['tournaments/route.ts','POST'],['tournaments/[id]/join/route.ts','POST'],['tournaments/[id]/route.ts','PATCH']]
function req(token) {
  return new Request('https://game.test',{method:'POST',headers:token?{Authorization:`Bearer ${token}`}:{},
    body:JSON.stringify({userId:'me',conversationId:'room',cell:2,name:'Cup',status:'finished'})})
}
for (const [path, method] of routes) test(`${path} ${method} binds the credential before dispatch, including direct legacy URLs`,async()=>{
  const dispatched=[]
  const marker=new Error('authorized-business-dispatch')
  const dispatch=(...args)=>{dispatched.push(args);throw marker}
  const db={user:{findUnique:async({where})=>({sessionTokenHash:hashSessionToken(`synthetic-${where.id}`),legacyTokenHashes:'[]'})}}
  const owner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const handler=await load(`src/app/api/${path}`,{
    db,requireActionOwner:owner,NextResponse:{json:Response.json},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',
    playGame:dispatch,joinGame:dispatch,joinTournament:dispatch,finishTournament:dispatch,createRoomObject:dispatch,
    GameOperationError,MessageOperationError:class extends Error {},
  },method)
  for(const token of [undefined,'forged','synthetic-other']) {
    const response=await handler(req(token),{params:Promise.resolve({id:'resource'})})
    assert.equal(response.status,token?403:401)
  }
  assert.equal(dispatched.length,0)
  await assert.rejects(handler(req('synthetic-me'),{params:Promise.resolve({id:'resource'})}),error=>error===marker)
  assert.equal(dispatched.length,1)
  const args=dispatched[0]
  if(path==='games/route.ts'||path==='tournaments/route.ts') assert.equal(args[1].userId,'me')
  else {assert.equal(args[1],'resource');assert.equal(args[2],'me')}
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http2'
import { once } from 'node:events'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import crypto from 'node:crypto'
import { pushDeliveryWindow, shouldRetirePushToken, apnsInvalidatedAt } from '../../src/lib/push/delivery-policy.ts'
import { postApns, apnsOrigin } from '../../src/lib/push/apns-http2.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const unregistered = {error:{details:[{'@type':'type.googleapis.com/google.firebase.fcm.v1.FcmError',errorCode:'UNREGISTERED'}]}}
const payload = {kind:'message',title:'Sender',body:'Preview',conversationId:'room',messageId:'message'}
const now = 2_000_000_000_000

test('only explicit provider unregistration retires a native token', () => {
  assert.equal(shouldRetirePushToken('android',404,unregistered),true)
  assert.equal(shouldRetirePushToken('ios',410,{reason:'Unregistered',timestamp:now}),true)
  for(const status of [400,401,403,404,410,429,500,503]) {
    for(const body of [null,{},'bad-response',{error:{status:'NOT_FOUND'}},
      {error:{details:[{'@type':'type.googleapis.com/google.firebase.fcm.v1.FcmError',errorCode:'INVALID_ARGUMENT'}]}}]) {
      assert.equal(shouldRetirePushToken('android',status,body),false)
    }
    for(const reason of ['InvalidProviderToken','ExpiredProviderToken','BadDeviceToken','DeviceTokenNotForTopic','TooManyRequests'])
      assert.equal(shouldRetirePushToken('ios',status,{reason}),false)
  }
  assert.equal(shouldRetirePushToken('ios',410,{reason:'Unregistered'}),false)
  assert.equal(shouldRetirePushToken('ios',410,{reason:'Unregistered',timestamp:'yesterday'}),false)
  assert.equal(apnsInvalidatedAt({timestamp:now}),now)
  assert.equal(apnsInvalidatedAt({timestamp:'yesterday'}),undefined)
})
test('provider TTL is bounded, never rounds past expiry, and invalid deadlines fail closed', () => {
  assert.equal(pushDeliveryWindow(payload,now).ttlSeconds,3600)
  assert.equal(pushDeliveryWindow({...payload,kind:'gcall'},now).ttlSeconds,60)
  for(const offset of [-1,0,999]) assert.equal(pushDeliveryWindow({...payload,expiresAt:new Date(now+offset).toISOString()},now),null)
  const window=pushDeliveryWindow({...payload,expiresAt:new Date(now+2999).toISOString()},now)
  assert.equal(window.ttlSeconds,2);assert.ok(window.expiresAtSeconds*1000<=now+2999)
  assert.equal(pushDeliveryWindow({...payload,expiresAt:'bad'},now),null)
  assert.equal(apnsOrigin(undefined),'https://api.push.apple.com')
  assert.equal(apnsOrigin('sandbox'),'https://api.sandbox.push.apple.com')
  assert.equal(apnsOrigin('sandbbox'),null)
})
async function http2Fixture(run,handler) {
  const server=createServer();const sessions=new Set()
  server.on('session',session=>{sessions.add(session);session.on('close',()=>sessions.delete(session));session.on('error',()=>{})})
  server.on('stream',(stream,headers)=>{stream.on('error',()=>{});handler(stream,headers)})
  server.listen(0,'127.0.0.1');await once(server,'listening')
  try{await run(`http://127.0.0.1:${server.address().port}`)}finally{for(const session of sessions)session.destroy();await new Promise(resolve=>server.close(resolve))}
}
test('APNs sender uses real HTTP/2 headers/body and returns provider rejection metadata',()=>{
  let request
  return http2Fixture(async origin=>{
    const result=await postApns(origin,'token/escaped',{'apns-topic':'app.pulse.chat','apns-expiration':'123'}, {aps:{alert:'hello'}})
    assert.equal(result.status,410);assert.deepEqual(result.body,{reason:'Unregistered',timestamp:now})
    assert.equal(request.headers[':method'],'POST');assert.equal(request.headers[':path'],'/3/device/token%2Fescaped')
    assert.equal(request.headers['apns-topic'],'app.pulse.chat');assert.equal(request.body.aps.alert,'hello')
  },(stream,headers)=>{
    let data='';stream.on('data',chunk=>data+=chunk);stream.on('end',()=>{
      request={headers,body:JSON.parse(data)};stream.respond({':status':410});stream.end(JSON.stringify({reason:'Unregistered',timestamp:now}))
    })
  })
})
test('HTTP/2 empty success and abruptly closed sessions settle without hanging',async()=>{
  await http2Fixture(async origin=>assert.deepEqual(await postApns(origin,'token',{}, {}),{status:200,body:null}),stream=>{stream.respond({':status':200});stream.end()})
  await http2Fixture(async origin=>assert.equal((await postApns(origin,'token',{}, {})).status,0),stream=>stream.session.destroy())
})
test('HTTP/2 timeout and oversized replies close the request without retiring tokens',async()=>{
  await http2Fixture(async origin=>assert.equal((await postApns(origin,'token',{}, {},100)).status,0),()=>{})
  await http2Fixture(async origin=>assert.equal((await postApns(origin,'token',{}, {})).status,0),stream=>{stream.respond({':status':410});stream.end('x'.repeat(17000))})
})
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'push-transport-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  await db.$executeRawUnsafe('CREATE TABLE PushToken(id TEXT PRIMARY KEY,userId TEXT,platform TEXT,token TEXT UNIQUE,endpoint TEXT,createdAt DATETIME,updatedAt DATETIME)')
  const seed=async(id,platform='android')=>db.pushToken.create({data:{id,userId:'owner',platform,token:`token-${id}`,createdAt:new Date(now-10000),updatedAt:new Date(now-1000)}})
  const source=(await readFile(new URL('../../src/lib/push/transport.ts',import.meta.url),'utf8'))
   .replace(/^import .*$/gm,'').replace(/^export /gm,'')
  const requests=[];let androidResponse={status:200,body:{}};let iosResponse={status:200,body:null};let duringSend=async()=>{}
  const fetch=async(url,options)=>{
   if(url.endsWith('/online'))return new Response(JSON.stringify({onlineUserIds:[]}))
   requests.push({platform:'android',payload:JSON.parse(options.body)})
   await duringSend();return new Response(JSON.stringify(androidResponse.body),{status:androidResponse.status})
  }
  const apns=async(origin,token,headers,body)=>{requests.push({platform:'ios',origin,token,headers,payload:body});await duringSend();return iosResponse}
  const web=async(subscription,body,options)=>{requests.push({platform:'web',subscription,body,options})}
  const bindings={web,db,crypto,pushDeliveryWindow:p=>pushDeliveryWindow(p,now),shouldRetirePushToken,apnsInvalidatedAt,apnsOrigin,postApns:apns,fetch,process:{env:{}},console:{log(){},warn(){}}}
  const methods=new Function(...Object.keys(bindings),stripTypeScriptTypes(source,{mode:'transform'})+`;
    fcmBearer=async()=>"test-token";fcmCredentials=()=>({projectId:"test-project"});apnsBearer=async()=>"test-token";
    webPushSender=async()=>({sendNotification:web});
    return {fanoutPush,sendAndroid,sendIos,sendWeb};`)(...Object.values(bindings))
  await run({db,seed,requests,...methods,setAndroid:r=>androidResponse=r,setIos:r=>iosResponse=r,onSend:f=>duringSend=f})
 }finally{await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
test('actual senders retain registrations on payload/credential failure and serialize bounded expiry',()=>fixture(async h=>{
 const expiry=new Date(now+30000).toISOString()
 h.setAndroid({status:400,body:{error:{status:'INVALID_ARGUMENT'}}});h.setIos({status:403,body:{reason:'ExpiredProviderToken'}})
 assert.equal((await h.sendAndroid('token',{...payload,expiresAt:expiry})).pruneToken,false)
 assert.equal((await h.sendIos('token',{...payload,expiresAt:expiry})).pruneToken,false)
 assert.equal(h.requests[0].payload.message.android.ttl,'30s')
 assert.equal(h.requests[0].payload.message.data.expiresAt,expiry)
 assert.equal(h.requests[1].headers['apns-topic'],'app.pulse.chat')
 assert.equal(h.requests[1].headers['apns-expiration'],String(now/1000+30))
 assert.equal(h.requests[1].payload.expiresAt,expiry)
 assert.equal((await h.sendIos('token',{...payload,expiresAt:'bad'})).skipped,true)
 assert.equal(h.requests.length,2)
}))
test('stale unregistered response cannot delete refreshed/rebound registry rows',()=>fixture(async h=>{
 await h.seed('a');h.setAndroid({status:404,body:unregistered})
 h.onSend(async()=>{await h.db.pushToken.update({where:{id:'a'},data:{userId:'another-owner',updatedAt:new Date(now+1)}})})
 const report=await h.fanoutPush(['owner'],payload)
 assert.equal(report.pruned,0);assert.equal((await h.db.pushToken.findUnique({where:{id:'a'}})).userId,'another-owner')
}))
test('a registration changed while earlier devices send is skipped before dispatch',()=>fixture(async h=>{
 await h.seed('a');await h.seed('b')
 h.onSend(async()=>{await h.db.pushToken.update({where:{id:'b'},data:{userId:'another-owner',updatedAt:new Date(now+1)}})})
 const report=await h.fanoutPush(['owner'],payload)
 assert.equal(report.sent,1);assert.equal(report.skipped,1);assert.equal(h.requests.length,1)
}))
test('current unregistered tokens retire but old APNs invalidation does not erase re-registration',()=>fixture(async h=>{
 await h.seed('a');h.setAndroid({status:404,body:unregistered})
 assert.equal((await h.fanoutPush(['owner'],payload)).pruned,1)
 await h.seed('ios','ios');h.setIos({status:410,body:{reason:'Unregistered',timestamp:now-2000}})
 assert.equal((await h.fanoutPush(['owner'],payload)).pruned,0)
 h.setIos({status:410,body:{reason:'Unregistered',timestamp:now}})
 assert.equal((await h.fanoutPush(['owner'],payload)).pruned,1)
 assert.equal(await h.db.pushToken.count(),0)
}))

test('legacy web pings remain payload-free while keyed subscriptions receive bounded expiry',()=>fixture(async h=>{
 const expiry=new Date(now+30000).toISOString()
 assert.equal((await h.sendWeb('https://example.test/push',{...payload,expiresAt:expiry})).ok,true)
 assert.equal(h.requests[0].body,undefined);assert.equal(h.requests[0].options.TTL,30)
 assert.equal((await h.sendWeb(JSON.stringify({endpoint:'https://example.test/push',keys:{p256dh:'key',auth:'auth'}}),{...payload,expiresAt:expiry})).ok,true)
 assert.equal(JSON.parse(h.requests[1].body).expiresAt,expiry)
}))

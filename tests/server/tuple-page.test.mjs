import test from 'node:test'
import assert from 'node:assert/strict'
import { readTupleSide, readTupleInterval } from '../../src/lib/tuple-page.ts'
const position=(time,id)=>({at:new Date(time).toISOString(),id})
const compare=(a,b)=>Date.parse(a.at)-Date.parse(b.at) || (a.id<b.id?-1:a.id>b.id?1:0)
const rows=[0,1000,2000,3000].flatMap(time=>['a','c','e'].map(id=>position(time,id)))
function reader(order) {
  let calls=0,returned=0
  const load=async(range,take)=>{
    calls++
    const match=(value,filter)=>Object.entries(filter).every(([op,bound])=>({lt:value<bound,lte:value<=bound,gt:value>bound,gte:value>=bound})[op])
    const page=rows.filter(row=>{
      const time=Date.parse(row.at)
      const matchesTime=!range.time || (range.time instanceof Date ? time===range.time.getTime()
        :match(time,Object.fromEntries(Object.entries(range.time).map(([op,date])=>[op,date.getTime()]))))
      return matchesTime && (!range.id || match(row.id,range.id))
    }).sort((a,b)=>compare(a,b)*(order==='before'?-1:1)).slice(0,take)
    returned+=page.length
    return page
  }
  return {load,calls:()=>calls,returned:()=>returned}
}
test('two-range seeks preserve order, inclusivity and gaps across timestamps',async()=>{
  for(const time of [-1,0,1000,1500,3000,4000]) for(const id of ['a','b','e','z'])
    for(const direction of ['before','after']) for(const inclusive of [false,true]) for(const take of [0,1,4,20]) {
      const at=position(time,id), r=reader(direction)
      const result=await readTupleSide(at,direction,take,r.load,inclusive)
      const expected=rows.filter(row=>direction==='before' ? (inclusive?compare(row,at)<=0:compare(row,at)<0)
        :(inclusive?compare(row,at)>=0:compare(row,at)>0)).sort((a,b)=>compare(a,b)*(direction==='before'?-1:1)).slice(0,take)
      assert.deepEqual(result,expected)
      assert.ok(r.calls()<=2);assert.ok(r.returned()<=take)
    }
})
test('three-range bounded export never crosses either cursor or loses timestamp transitions',async()=>{
  for(const upper of rows) for(const after of [undefined,...rows.filter(row=>compare(row,upper)<=0)]) for(const take of [0,1,4,20]) {
    const r=reader('after'),floor=new Date(0).toISOString()
    const result=await readTupleInterval(after,upper,take,r.load,floor)
    assert.deepEqual(result,rows.filter(row=>(!after || compare(row,after)>0) && compare(row,upper)<=0).slice(0,take))
    assert.ok(r.calls()<=3);assert.ok(r.returned()<=take)
  }
  const r=reader('after')
  assert.deepEqual(await readTupleInterval(undefined,position(3000,'c'),20,r.load,new Date(1500).toISOString()),
    rows.filter(row=>Date.parse(row.at)>=1500 && compare(row,position(3000,'c'))<=0))
})
test('tuple page loaders cannot overfill a bounded page or accept negative sizes',async()=>{
  await assert.rejects(readTupleSide(position(1000,'c'),'before',1,async()=>rows),/exceeded/)
  await assert.rejects(readTupleSide(undefined,'before',-1,async()=>[]),/size/)
})


test('invalid interval inputs fail before any read, including a reversed timestamp fence',async()=>{
  const load=async()=>{throw Error('must not read')}
  for(const [after,upper,take,floor] of [
    [position(2000,'a'),position(1000,'e'),5],
    [position(1000,'z'),position(1000,'e'),5],
    [undefined,position(1000,'e'),5,new Date(2000).toISOString()],
    [position(0,'a'),position(1000,'e'),5,new Date(500).toISOString()],
    [undefined,{at:'bad',id:'a'},5],
    [undefined,position(1000,'e'),-1],
  ]) await assert.rejects(readTupleInterval(after,upper,take,load,floor),/Invalid tuple/)
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { clipUtf16 } from '../../src/lib/text-limits.ts'

test('UTF-16 limits keep emoji intact without exceeding native/server budgets',()=>{
 for(const value of ['Build 😀 finished','দাম কত?','👩‍💻 café','a😀b','𠮷野家']) {
  for(let limit=0;limit<=value.length+1;limit++) {
   const result=clipUtf16(value,limit)
   assert.ok(result.length<=limit)
   assert.ok(result.isWellFormed(),`${JSON.stringify(value)} / ${limit}`)
   assert.ok(value.startsWith(result))
   assert.equal(JSON.parse(JSON.stringify(result)),result)
  }
 }
 assert.equal(clipUtf16('a😀b',2),'a')
 assert.equal(clipUtf16('a😀b',3),'a😀')
 assert.equal(clipUtf16('abc',30),'abc')
})
test('invalid truncation budgets fail explicitly',()=>{
 for(const limit of [-1,0.5,Infinity,NaN])assert.throws(()=>clipUtf16('hello',limit),RangeError)
})

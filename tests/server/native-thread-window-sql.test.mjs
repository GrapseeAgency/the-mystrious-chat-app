import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

test('production Android thread projections bound large caches and expire counts without decoding replies', () => {
  const source = readFileSync('apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt', 'utf8')
  const queries = {}
  for (const match of source.matchAll(/@Query\(([\s\S]*?)\)\s*(?:suspend\s+)?fun\s+(\w+)/g)) {
    if (['observeRiver', 'liveThreadCounts', 'cachedThreadSlice', 'observeThreadWindow'].includes(match[2])) {
      queries[match[2]] = [...match[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(m => JSON.parse(m[0])).join('')
    }
  }
  assert.equal(Object.keys(queries).length, 4)
  const script = String.raw`
import json,sqlite3,sys
q=json.load(sys.stdin);d=sqlite3.connect(':memory:')
d.executescript('CREATE TABLE messages(id TEXT PRIMARY KEY,conversationId TEXT,threadRootId TEXT,createdAt TEXT,deletedAt TEXT,expiresAtEpochMs INTEGER);CREATE TABLE wave7_cache(key TEXT,json TEXT);CREATE TABLE outbox(clientId TEXT,failureReason TEXT);')
d.execute("INSERT INTO messages VALUES ('root','room',NULL,'2000',NULL,200)")
d.executemany('INSERT INTO messages VALUES (?,\'room\',\'root\',\'2000\',NULL,NULL)',[(f'm-{i:06}',) for i in range(12000)])
def rows(name,**kw):return d.execute(q[name],kw).fetchall()
assert len(rows('observeRiver',room='room',selected=''))==1
assert len(rows('observeRiver',room='room',selected='m-000001'))==2
assert len(rows('observeThreadWindow',rootId='root',ids='',latest=1))==60
assert len(rows('observeThreadWindow',rootId='root',ids='m-000001',latest=0))==1
assert rows('liveThreadCounts',room='room',now=0)==[('root',12000,200)]
d.execute("UPDATE messages SET expiresAtEpochMs=100 WHERE id='m-000001'")
assert rows('liveThreadCounts',room='room',now=0)==[('root',12000,100)]
assert rows('liveThreadCounts',room='room',now=100)==[('root',11999,200)]
assert rows('liveThreadCounts',room='room',now=200)==[]
assert len(rows('cachedThreadSlice',room='room',root='root',at='2000',id='m-000001',forward=0,size=60,now=0))==1
assert len(rows('cachedThreadSlice',room='room',root='root',at='2000',id='m-000001',forward=1,size=60,now=0))==60
d.execute("INSERT INTO wave7_cache VALUES ('thread-access-denied:v1|root','denied')")
assert rows('liveThreadCounts',room='room',now=0)==[]
assert rows('observeThreadWindow',rootId='root',ids='m-000001',latest=0)==[]
`
  const result = spawnSync('python3', ['-c', script], { input: JSON.stringify(queries), encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
})

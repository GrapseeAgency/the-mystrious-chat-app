import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
test('production native outbox queries rotate room heads without reordering a room or crossing owners',()=>{
  const kotlin=readFileSync('apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt','utf8')
  const match=[...kotlin.matchAll(/@Query\(([\s\S]*?)\)\s*(?:suspend\s+)?fun\s+(\w+)/g)].find(m=>m[2]==='readyHeadsForOwner')
  assert.ok(match)
  const android=[...match[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(m=>JSON.parse(m[0])).join('')
  const swift=readFileSync('apps/ios/Pulse/Core/Storage/PulseStore.swift','utf8').split('public func nextPendingOutbox(')[1].split('arguments: StatementArguments(rooms)')[0].split('sql: ')[1]
  const ios=[...swift.matchAll(/"([^"\n]*)"/g)].map(m=>m[1]).join('')
  assert.match(ios,/NOT EXISTS/)
  const script=String.raw`
import sqlite3,json,sys
q=json.load(sys.stdin);d=sqlite3.connect(':memory:')
d.executescript('CREATE TABLE outbox(id INTEGER PRIMARY KEY,conversationId TEXT,clientId TEXT,attempts INTEGER,failureReason TEXT);CREATE TABLE messages(id TEXT,conversationId TEXT,authorId TEXT);')
for n,room,owner,attempts in [(1,'a','owner',5),(2,'a','owner',0),(3,'b','owner',0),(4,'b','foreign',0),(5,'c','foreign',0)]:
 d.execute('INSERT INTO outbox VALUES (?,?,?,?,NULL)',(n,room,str(n),attempts));d.execute('INSERT INTO messages VALUES (?,?,?)',('local_'+str(n),room,owner))
assert d.execute(q['android'],{'owner':'owner','excludedRooms':''}).fetchone()[0]==3
assert d.execute(q['android'],{'owner':'owner','excludedRooms':'b'}).fetchone()[0]==1
# GRDB database is itself account scoped; strip synthetic foreign rows.
d.execute("DELETE FROM outbox WHERE id IN (4,5)")
assert d.execute(q['ios']).fetchone()[0]==3
d.execute('UPDATE outbox SET attempts=6 WHERE id=3')
assert d.execute(q['ios']).fetchone()[0]==1
assert d.execute(q['android'],{'owner':'owner','excludedRooms':''}).fetchone()[0]==1
d.execute('DELETE FROM outbox WHERE id=1')
assert d.execute(q['ios']).fetchone()[0]==2
`
 const result=spawnSync('python3',['-c',script],{input:JSON.stringify({android,ios}),encoding:'utf8'})
 assert.equal(result.status,0,result.stderr)
})

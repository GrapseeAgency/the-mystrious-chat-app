import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

test('production native composer recovery SQL pages every namespace without leaking wildcard owners', () => {
  const kotlin = readFileSync('apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt', 'utf8')
  const android = kotlin.match(/@Query\("""(SELECT \* FROM wave7_cache WHERE `key` > :after[\s\S]*?)"""\)\s*suspend fun composerRecoveryPage/)[1]
  const source = readFileSync('apps/ios/Pulse/Core/Storage/PulseStore.swift', 'utf8').split('func composerRecoveryPage(owner:')[1].split('private static func recoveryJSON')[0]
  const ios = source.match(/sql: "(SELECT key,json FROM wave7Cache[^"\n]+)"/)[1]
  const prefixes = [...source.split('let patterns = [')[1].split('].map')[0].matchAll(/"([^"]+)"/g)].map(m => m[1])
  assert.equal(prefixes.length, 7)
  const result = spawnSync('python3', ['-c', String.raw`
import json,sqlite3,sys
q=json.load(sys.stdin)
for platform,table in [('android','wave7_cache'),('ios','wave7Cache')]:
 db=sqlite3.connect(':memory:')
 db.execute('CREATE TABLE '+table+'(key TEXT PRIMARY KEY,json TEXT,updatedAt INTEGER)')
 prefixes=q['prefixes'] if platform=='ios' else q['prefixes'][:-1]
 for owner in ['owner','owner*?[','ownerXXz[','other']:
  for prefix in prefixes:
   for n in range(113):
    db.execute('INSERT INTO '+table+' VALUES (?,?,0)',(prefix+':v1|'+owner+'|%05d'%n,'retained'))
  db.execute('INSERT INTO '+table+' VALUES (?,?,0)',('composer-head:v1|'+owner+'|private','not a recovery row'))
 for owner in ['owner','owner*?[']:
  escaped=''.join({'[':'[[]','*':'[*]','?':'[?]'}.get(c,c) for c in owner)
  expected=sorted(prefix+':v1|'+owner+'|%05d'%n for prefix in prefixes for n in range(113))
  after='';found=[]
  while True:
   args={'ownerPattern':escaped,'after':after} if platform=='android' else [after]+[p+':v1|'+escaped+'|*' for p in prefixes]
   rows=db.execute(q[platform],args).fetchall()
   assert len(rows)<=101
   page=[row[0] for row in rows[:100]]
   found+=page
   if len(rows)<=100:break
   assert page[-1]>after
   after=page[-1]
  assert found==expected,(platform,owner,len(found),len(expected))
 db.close()
`], { input: JSON.stringify({ android, ios, prefixes }), encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { DatabaseSync } from 'node:sqlite'
import { readFile } from 'node:fs/promises'

const source = path => readFile(new URL(`../../${path}`, import.meta.url), 'utf8')
const kotlin = await source('apps/android/data/src/main/kotlin/app/pulse/data/local/PulseDatabase.kt')
const swift = await source('apps/ios/Pulse/Core/Storage/PulseStore.swift')
const androidSQL = kotlin.match(/@Query\("(UPDATE wave7_cache SET updatedAt[^\n]+)"\)\s+suspend fun deferReadIntent/)[1]
const iosSQL = swift.match(/UPDATE wave7Cache SET updatedAt = \([\s\S]+?WHERE key = \? AND json = \?/)[0]

for (const [platform, table, sql] of [['Android', 'wave7_cache', androidSQL], ['iOS', 'wave7Cache', iosSQL]]) {
  test(`${platform} real deferral SQL changes retry order only and guards the original payload`, () => {
    const db = new DatabaseSync(':memory:')
    try {
      db.exec(`CREATE TABLE ${table}(key TEXT PRIMARY KEY, json TEXT NOT NULL, updatedAt INTEGER NOT NULL)`)
      const put = db.prepare(`INSERT INTO ${table} VALUES (?, ?, ?)`)
      const prefix = 'read-journal:v1|owner|'
      const first = prefix + 'first', second = prefix + 'second'
      put.run(first, 'original fence and identity', 1)
      put.run(second, 'independent original', 2)
      put.run('read-journal:v1|other|first', 'foreign', 9999999999999)
      const defer = (key, json) => platform === 'Android'
        ? db.prepare(sql).run({ prefix, key, json })
        : db.prepare(sql).run(prefix, prefix, key, json)
      const own = () => db.prepare(`SELECT key,json,updatedAt FROM ${table} WHERE substr(key,1,length(?))=? ORDER BY updatedAt,key`).all(prefix, prefix)
      defer(first, 'original fence and identity')
      assert.deepEqual(own().map(r => r.key), [second, first])
      assert.equal(own()[1].updatedAt, 3, 'another account must not supply retry order')
      assert.equal(own()[1].json, 'original fence and identity')
      defer(first, 'obsolete replacement')
      assert.equal(own()[1].updatedAt, 3, 'a stale callback cannot reorder a replacement')
      defer(second, 'independent original')
      assert.deepEqual(own().map(r => r.key), [first, second])
      assert.equal(db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n, 3, 'nothing is acknowledged or discarded')
    } finally { db.close() }
  })
}

test('foreground and shared cold/live Swift replay both use persistent fair deferral', async () => {
  const foreground = await source('apps/ios/Pulse/Core/Support/PulseSession.swift')
  const shared = await source('apps/ios/Pulse/Core/Support/PulseReadReceiptGate.swift')
  assert.match(foreground, /try store\.deferRead\(read\)/)
  assert.match(foreground, /PulseReadJournalReplay\.maxDeferralsPerPass/)
  assert.match(foreground, /PulseReadJournalReplay\.canContinue\(after: error\)/)
  assert.match(shared, /try store\.deferRead\(read\)/)
  assert.match(shared, /maxDeferralsPerPass = 16/)
  assert.match(shared, /guard !Task\.isCancelled, isCurrent\(\) else \{ return false \}/)
})

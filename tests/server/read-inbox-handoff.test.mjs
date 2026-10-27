import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const source = path => readFile(new URL(`../../${path}`, import.meta.url), 'utf8')

test('Swift confirmed background inbox handoff is persisted, owned, synchronous and before alert settlement', async () => {
  const push = await source('apps/ios/Pulse/Core/Support/PulsePushNotifications.swift')
  const refresh = push.split('static func refreshUnreadBadge(')[1].split('static func reconcileDeliveredMessages(')[0]
  assert.match(refresh, /guard !Task.isCancelled, isCurrent\(\), snapshotFence.owns\(ticket\)/)
  assert.ok(refresh.indexOf('try store.upsert(') < refresh.indexOf('onSnapshot(rooms)'))
  assert.ok(refresh.indexOf('onSnapshot(rooms)') < refresh.indexOf('await reconcileDeliveredMessages'))
  const session = await source('apps/ios/Pulse/Core/Support/PulseSession.swift')
  assert.match(session, /onSnapshot: \{ rooms in\s*self.publishInboxSnapshot\(rooms, epoch: epoch\)/)
  const publish = session.split('func publishInboxSnapshot(')[1].split('\n    }')[0]
  assert.match(publish, /guard generation == epoch else \{ return \}/)
  assert.match(publish, /inboxSnapshots.send\(rooms\)/)
  assert.doesNotMatch(publish, /Task\s*\{|await |markRead\(/)
})

test('Both Swift inboxes consume confirmed snapshots and retire earlier requests; Android observes canonical Room updates', async () => {
  const primary = await source('apps/ios/Pulse/Features/Chats/ChatsView.swift')
  const mirror = await source('apps/ios/Pulse/Features/Mirror/MirrorRootView.swift')
  for (const src of [primary, mirror]) {
    assert.match(src, /session.inboxSnapshots[\s\S]*?sink[\s\S]*?session.sessionGeneration == inboxEpoch[\s\S]*?acceptCanonicalInbox\(rooms,/)
    const adopt = src.split('func acceptCanonicalInbox(')[1].split('\n    }')[0]
    assert.match(adopt, /refresh(?:Generation|Revision) &\+= 1/)
    assert.doesNotMatch(adopt, /await |Task\s*\{|\.conversations\(|markRead\(/)
  }
  assert.match(primary, /guard request == refreshGeneration, epoch == session.sessionGeneration/)
  assert.match(mirror, /if let page, revision == refreshRevision/)
  const android = await source('apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt')
  assert.match(android, /observeConversations\(query: String\)[\s\S]*?combine\(conversationDao.observeAll\(\), draftDao.observeAll\(\)\)/)
})

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
    assert.match(adopt, /(?:refreshRevision &\+= 1|inboxSnapshotFence.invalidate\(\))/)
    assert.doesNotMatch(adopt, /await |Task\s*\{|\.conversations\(|markRead\(/)
  }
  assert.match(primary, /guard epoch == session.sessionGeneration, inboxSnapshotFence.accept\(request\)/)
  assert.match(mirror, /if let page, revision == refreshRevision/)
  const android = await source('apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt')
  assert.match(android, /observeConversations\(query: String\)[\s\S]*?combine\(conversationDao.observeAll\(\), draftDao.observeAll\(\)\)/)
})

test('inbox GET ordering is separate from immediate mutation invalidation on both native platforms', async () => {
  const android = await source('apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt')
  const fetch = android.split('override suspend fun refreshConversations():')[1].split('override suspend fun refreshMessages(')[0]
  assert.match(fetch, /val version = inboxRefreshVersion.get\(\)/)
  assert.match(fetch, /request < inboxCommittedRequest/)
  assert.match(fetch, /inboxSnapshotMutex.withLock/)
  assert.match(fetch, /catch \(_: SupersededConversationSnapshot\)[\s\S]*?return Result.failure\(IllegalStateException/)

  assert.ok(fetch.indexOf('val accepted = database.withTransaction') < fetch.indexOf('if (accepted) inboxCommittedRequest = request'))
  const schedule = android.split('private fun scheduleConversationsRefresh(')[1].split('val awaitingDelay')[0]
  assert.ok(schedule.indexOf('inboxRefreshVersion.incrementAndGet()') < schedule.indexOf('refreshScheduled.compareAndSet'))
  assert.match(android, /requestReadMembershipRefresh\(conversationId: String\)[\s\S]*?scheduleConversationsRefresh\(invalidate = false\)/)
  const primary = await source('apps/ios/Pulse/Features/Chats/ChatsView.swift')
  const observer = primary.split('private func observe(session: PulseSession)')[1].split('func acceptCanonicalInbox(')[0]
  assert.equal((observer.match(/handleEvents\(receiveOutput:/g) ?? []).length, 3)
  assert.equal((observer.match(/inboxSnapshotFence.invalidate\(\)/g) ?? []).length, 3)
  for (const part of observer.split('.handleEvents(receiveOutput:').slice(1)) {
    assert.ok(part.indexOf('inboxSnapshotFence.invalidate()') < part.indexOf('.debounce('))
  }
  const mirror = await source('apps/ios/Pulse/Features/Mirror/MirrorRootView.swift')
  const invalidation = mirror.split('inboxInvalidations = Publishers.Merge')[1].split('inbox = ((try?')[0]
  assert.match(invalidation, /self.refreshRevision &\+= 1[\s\S]*?self.refresh\(\)/)
  assert.match(mirror, /inboxInvalidations\?\.cancel\(\)/)
})

test('rejoining repairs projections independently of outbox contents and Swift inbox lifetime', async () => {
  const android = await source('apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt')
  const reconnect = android.split('is PulseSocketClient.Signal.Connection ->')[1].split('is PulseSocketClient.Signal.SessionRejected')[0]
  assert.match(reconnect, /if \(signal.connected\) \{[\s\S]*?readStateRevisions.reconnected\(\)[\s\S]*?scheduleConversationsRefresh\(\)/)
  const session = await source('apps/ios/Pulse/Core/Support/PulseSession.swift')
  const live = session.split('case .connectionState(let isOn):')[1].split('case .typing(')[0]
  assert.match(live, /if isOn \{[\s\S]*?realtimeRefreshTick \+= 1\s*noteInboxChanged\(\)/)
  assert.doesNotMatch(live, /readGate.reset\(|prepareRead|captureReadIntent/)
  const recovery = session.split('private func reconcileMessageNotifications()')[1].split('/// Single enqueue entry point')[0]
  assert.match(recovery, /PulsePushNotifications.refreshUnreadBadgeState\(using: client, store: store/)
  assert.match(recovery, /self.generation == epoch && self.viewer\?\.id == client.userId/)
  assert.match(recovery, /self.publishInboxSnapshot\(rooms, epoch: epoch\)/)
  assert.match(recovery, /result.retryDelay/)
  assert.match(recovery, /revision != self.notificationRefreshRevision/)
  assert.ok(recovery.indexOf('Task.sleep(nanoseconds: 500_000_000)') < recovery.indexOf('let revision = self.notificationRefreshRevision'))

  for (const start of ['case .messageNew(let conversationId, let raw):', 'case .messageDeleted(let room, let raw), .messageReact(let room, let raw):', 'case .conversationUpdated:']) {
    const event = session.split(start)[1].split('\n        case ')[0]
    assert.match(event, /noteInboxChanged\(\)/)
  }
})

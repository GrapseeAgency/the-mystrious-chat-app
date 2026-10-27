import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const source = path => readFile(new URL(`../../${path}`, import.meta.url), 'utf8')

test('Android explicit-all always reconciles without a success-only or optimistic-zero branch', async () => {
  const src = await source('apps/android/data/src/main/kotlin/app/pulse/data/repository/PulseRepositoryImpl.kt')
  const action = src.split('private suspend fun acknowledgeRead(')[1].split('override suspend fun setTyping')[0]
  assert.match(action, /finally\s*\{\s*if \(viewerId == owner && attachmentSession == epoch\)/)
  assert.match(action, /noteReadStateChanged\(conversationId\)/)
  assert.match(action, /scope\.launch\s*\{\s*requireConversationOwner\(owner, epoch\)\s*refreshMessages/)
  assert.doesNotMatch(action, /if \(result\.isSuccess/)
})

test('Swift single, batch and mirror read actions reconcile in settlement defers', async () => {
  const src = await source('apps/ios/Pulse/Features/Chats/ChatsView.swift')
  for (const [start, end, all] of [
    ['private func markConversationRead()', 'private func toggleMarkUnread()', true],
    ['private func toggleMarkUnread()', 'private func setMuted(', false],
    ['func toggleMarkUnread(_ conv:', '// ── batch actions', false],
    ['func batchMarkRead(', '// ── Note to Self', true],
  ]) {
    const action = src.split(start)[1].split(end)[0]
    assert.match(action, new RegExp(`defer \\{\\s*session\\.reconcileReadAction\\([^\\n]+all: ${all}, epoch: epoch\\)`))
  }
  const mirror = await source('apps/ios/Pulse/Features/Mirror/MirrorRootView.swift')
  assert.match(mirror, /session\.reconcileReadAction\(row.id, all: readAll, epoch: epoch\)/)
  const session = await source('apps/ios/Pulse/Core/Support/PulseSession.swift')
  const settlement = session.split('func reconcileReadAction(')[1].split('func noteReadAllChanged(')[0]
  assert.match(settlement, /guard generation == epoch else \{ return \}/)
  assert.doesNotMatch(settlement, /markRead\(|markUnread\(|captureReadIntent\(/)
})

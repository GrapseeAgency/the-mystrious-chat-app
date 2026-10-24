import test from 'node:test'
import assert from 'node:assert/strict'
import { channelIdentity, normalizeChannelPhoto } from '../../src/lib/channel-contract.ts'

test('both native photo-less channel payloads remain valid', () => {
  for (const value of [undefined, null, '', '   ']) assert.equal(normalizeChannelPhoto(value), null)
})
test('uploaded channel photos normalize while malformed paths and types reject', () => {
  assert.equal(normalizeChannelPhoto(' /api/uploads/abc-123.jpg '), '/api/uploads/abc-123.jpg')
  for (const value of [false, 123, {}, [], '/api/uploads/../private.jpg', '/api/uploads/file.svg', 'https://example.com/image.jpg']) {
    assert.equal(normalizeChannelPhoto(value), undefined)
  }
})
test('directory and creation use the same canonical room identity', () => {
  assert.deepEqual(channelIdentity('broadcast-room'), { id: 'broadcast-room', conversationId: 'broadcast-room' })
})

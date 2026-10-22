import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizedReactionGroups } from '../../src/lib/reaction-contract.ts'
import { REACTION_LEGACY, REACTION_IDS, reactionId } from '../../src/lib/icon-ids.ts'

test('canonical reaction groups count each member once across old and new stored values', () => {
  assert.deepEqual(normalizedReactionGroups([
    { userId: 'a', emoji: '\u{1F525}\uFE0F' }, { userId: 'a', emoji: 'fire' },
    { userId: 'b', emoji: '\u{1F525}' }, { userId: 'a', emoji: '\u2764' }, { userId: 'a', emoji: 'heart' },
  ]), [{ emoji: 'fire', userIds: ['a', 'b'], count: 2 }, { emoji: 'heart', userIds: ['a'], count: 1 }])
})
test('legacy selectors and stable IDs share one normalization contract', () => {
  for (const id of REACTION_IDS) assert.equal(reactionId(id), id)
  for (const [emoji, id] of Object.entries(REACTION_LEGACY)) {
    assert.equal(reactionId(emoji), id)
    assert.equal(reactionId(emoji + '\uFE0E'), id)
    assert.equal(reactionId(emoji + '\uFE0F'), id)
  }
  assert.equal(reactionId('unknown'), 'heart') // retain the existing display fallback
})

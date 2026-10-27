import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
import path from 'node:path'
import { privacyPreferenceEnabled } from '../../src/lib/privacy-preferences.ts'
import { normalizedReactionGroups } from '../../src/lib/reaction-contract.ts'
import { redactDeletedMessage, quoteSnippet } from '../../src/lib/message-disclosure.ts'
import * as sync from '../../src/lib/message-sync.ts'

async function load(file, bindings, expression) {
  const source = (await readFile(new URL(`../../${file}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '')
    .replace(/^export \{[^}]+\} from [^\n]+$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(source, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

test('every history window retains the authenticated reader own private proof without disclosing another reader', async () => {
  const at = new Date('2026-10-26T00:00:00Z')
  const proof = (id, visible = false) => ({ participant: { id: `${id}-membership`, userId: id,
    user: { preferences: JSON.stringify({ readReceipts: visible }) } } })
  const row = { id: 'message', conversationId: 'room', senderId: 'peer', sender: { id: 'peer', name: 'Peer', color: 'amber' },
    content: 'Hello', createdAt: at, deletedAt: null, reactions: [], translations: [], linkPreview: null,
    readObservations: [proof('reader'), proof('private-peer'), proof('public-peer', true)],
    poll: { id: 'poll', question: 'Ready?', closedAt: null,
      options: [{ id: 'option', text: 'Yes', position: 0, votes: [{ userId: 'reader' }] }] } }
  const db = { conversationParticipant: { findUnique: async () => ({ id: 'reader-membership' }) },
    conversation: { findUnique: async () => ({ id: 'room' }) }, message: {
      deleteMany: async () => ({}), findMany: async () => [row], count: async () => 1,
      findFirst: async () => ({ id: row.id, createdAt: at }),
    } }
  const serializers = await load('src/lib/serializers.ts', {
    path, db, privacyPreferenceEnabled, normalizedReactionGroups, redactDeletedMessage, quoteSnippet,
  }, '({mapMessage,parseIsoDate,strField,MESSAGE_FULL_INCLUDE,MESSAGES_DEFAULT_LIMIT,MESSAGES_MAX_LIMIT})')
  const GET = await load('src/app/api/conversations/[id]/messages/route.ts', {
    ...serializers, ...sync, db, NextResponse: { json: Response.json },
    requireActionActor: async () => ({ userId: 'reader' }),
  }, 'GET')
  const cursor = sync.encodeMessageSyncCursor({ v: 1, room: 'room', topic: null, from: at.toISOString(),
    upper: { at: at.toISOString(), id: row.id } })
  for (const query of ['', '&q=Hello', '&before=2026-10-27T00:00:00Z',
    `&syncFrom=${at.toISOString()}`, `&syncCursor=${encodeURIComponent(cursor)}`]) {
    // A caller-supplied userId cannot choose whose private receipts/vote are returned.
    const response = await GET(new Request(`https://history.test?userId=private-peer${query}`),
      { params: Promise.resolve({ id: 'room' }) })
    assert.equal(response.status, 200, query)
    assert.equal(response.headers.get('Cache-Control'), 'private, no-store')
    const message = (await response.json()).messages[0]
    assert.deepEqual(message.readByMembershipIds, ['reader-membership', 'public-peer-membership'], query)
    assert.equal(message.poll.myOptionId, 'option', query)
  }
})

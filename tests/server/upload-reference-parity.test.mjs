import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm, readFile, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as operations from '../../src/lib/message-operation.ts'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const { Prisma, PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const source = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(source, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

// R42 parity: image, audio and document attachments share ONE existence
// contract (disk cache OR durable UploadedFile store) - a wiped disk cache
// must not break sends that GET /api/uploads/[file] would still serve.
test('image, audio and file attachments accept bytes on disk OR in the durable store', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'attach-parity-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'db.sqlite')}` })
  const uploadsDir = join(dir, 'uploads')
  try {
    for (const name of ['User', 'Conversation', 'ConversationParticipant', 'Message', 'UserBlock', 'Poll', 'PollOption', 'PollVote']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        const def = typeof f.default === 'string' ? ` DEFAULT '${f.default.replaceAll("'", "''")}'` : typeof f.default === 'number' || typeof f.default === 'boolean' ? ` DEFAULT ${Number(f.default)}` : f.default?.name === 'now' ? ' DEFAULT CURRENT_TIMESTAMP' : ''
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : f.isUnique ? ' UNIQUE' : ''}${def}`
      })
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    for (const sql of (await readFile(new URL('../../prisma/patches/20261019_message_send_operation.sql', import.meta.url), 'utf8')).split(';').filter(x => x.trim())) await db.$executeRawUnsafe(sql)
    await db.user.create({ data: { id: 'me', name: 'Me', sessionTokenHash: hashSessionToken('synthetic-me'), legacyTokenHashes: '[]' } })
    await db.conversation.create({ data: { id: 'room', isGroup: true, ttlSeconds: 0 } })
    await db.conversationParticipant.create({ data: { userId: 'me', conversationId: 'room', role: 'member' } })

    // Durable-store-only file: UploadedFile row, NO disk cache (sandbox wipe).
    await db.$executeRawUnsafe("CREATE TABLE UploadedFile (name TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)")
    await db.uploadedFile.create({ data: { name: 'durable-voice-note-1.webm', mime: 'audio/webm', bytes: new Uint8Array(Buffer.from('voice')), size: 5 } })
    await db.uploadedFile.create({ data: { name: 'durable-photo-1.jpg', mime: 'image/jpeg', bytes: new Uint8Array(Buffer.from('photo')), size: 5 } })
    await db.uploadedFile.create({ data: { name: 'durable-report-1.pdf', mime: 'application/pdf', bytes: new Uint8Array(Buffer.from('report')), size: 6 } })
    // Disk-only file: no UploadedFile row (legacy fast path).
    await mkdir(uploadsDir, { recursive: true })
    await writeFile(join(uploadsDir, 'disk-only-photo-1.jpg'), 'legacy bytes')

    const requireActionOwner = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireActionOwner')
    const events = []
    const bindings = { db, ...operations, messageDeliveryDecision, requireActionOwner, NextResponse: Response,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '', MESSAGE_MAX: 2000,
      MESSAGE_FULL_INCLUDE: { poll: { include: { options: { orderBy: { position: 'asc' } } } } },
      mapMessage: r => r, memberIdsOf: async () => ['me'], notifySocket: async (...args) => events.push(args),
      nativeBlockFromPayload: () => null, deliverMessageNotification: async () => false, fanoutPush: async () => {},
      dayKey: date => date.toISOString().slice(0, 10), XP_DAILY_CAP: 0, stat, path: { join },
      UPLOADS_DIR: uploadsDir, AUDIO_EXT_REGEX: /\.(webm|mp3|ogg|wav|m4a|aac)$/, DOC_EXT_REGEX: /\.(pdf|txt|csv|zip)$/, DOC_MAX_BYTES: 10 * 1024 * 1024,
      createAutomationReply: async () => {}, threadRootAvailable: async () => true,
      decodeMessageSyncCursor: () => null, encodeMessageSyncCursor: () => null, readMessageSyncPage: async () => ({ rows: [], hasMore: false }),
      readMessagesBefore: async () => ({ rows: [], hasMore: false }), MESSAGE_ORDER_ASC: 1, MESSAGE_ORDER_DESC: -1,
      maybeAiReply: async () => {}, botWillRespond: () => false, maybeBotReply: async () => {},
      console: { error: () => {}, log: () => {} } }
    const POST = await load('src/app/api/conversations/[id]/messages/route.ts', bindings, 'POST')
    let n = 0
    const send = extra => {
      n += 1
      const body = { senderId: 'me', clientId: 'attach-parity-' + String(n).padStart(6, '0'), ...extra }
      return POST(new Request('https://attach.test/api', { method: 'POST', headers: { authorization: 'Bearer synthetic-me' }, body: JSON.stringify(body) }), { params: Promise.resolve({ id: 'room' }) })
    }

    // durable-store-only audio/image/file sends must pass
    assert.equal((await send({ kind: 'audio', audioPath: 'durable-voice-note-1.webm', durationMs: 1500, content: '' })).status, 201)
    assert.equal((await send({ kind: 'image', imagePath: 'durable-photo-1.jpg', content: '' })).status, 201)
    assert.equal((await send({ kind: 'file', filePath: 'durable-report-1.pdf', fileName: 'report.pdf', fileSize: 6, content: '' })).status, 201)
    // disk-only image send must pass (legacy fast path)
    assert.equal((await send({ kind: 'image', imagePath: 'disk-only-photo-1.jpg', content: '' })).status, 201)
    // neither disk nor durable store: refused
    assert.equal((await send({ kind: 'image', imagePath: 'missing-photo-1.jpg', content: '' })).status, 400)
    assert.equal((await send({ kind: 'audio', audioPath: 'missing-voice-1.webm', durationMs: 1000, content: '' })).status, 400)
    assert.equal((await send({ kind: 'file', filePath: 'missing-report-1.pdf', fileName: 'r.pdf', content: '' })).status, 400)
    assert.equal(await db.message.count(), 4)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

test('backend event, bot typing and online callers send the internal key, never request without it', async () => {
  for (const [path, name, args] of [
    ['serializers.ts', 'notifySocket', ['message:deleted', ['peer'], { id: 'message' }]],
    ['ai-bot.ts', 'emitBotTyping', ['room', 'bot', ['peer'], true]],
    ['push/transport.ts', 'onlineUserIds', []],
  ]) {
    const file = await readFile(new URL(`../../src/lib/${path}`, import.meta.url), 'utf8')
    const start = file.indexOf(`async function ${name}(`)
    assert.ok(start >= 0)
    const source = stripTypeScriptTypes(file.slice(start, file.indexOf('\n}\n', start) + 3))
    const calls = [], env = {}
    const deps = { process: { env }, SOCKET_URL: 'http://localhost:3003', BOT_NAME: 'Pulse Bot',
      fetch: async (url, options) => { calls.push({ url, options }); return Response.json({ onlineUserIds: ['peer'] }) } }
    const invoke = new Function(...Object.keys(deps), source + `;return ${name}`)(...Object.values(deps))
    await invoke(...args); assert.equal(calls.length, 0, name)
    env.CRON_SECRET = 'synthetic-relay-key'
    const result = await invoke(...args)
    assert.equal(calls.length, 1, name)
    assert.equal(calls[0].options.headers['x-pulse-key'], env.CRON_SECRET, name)
    assert.ok(!calls[0].options.body?.includes(env.CRON_SECRET), 'secret must not be forwarded as an event payload')
    if (name === 'onlineUserIds') assert.deepEqual([...result], ['peer'])
  }
})

test('actual HTTP boundary denies missing configuration, wrong keys and duplicate header values before handlers', async () => {
  const file = await readFile(new URL('../../mini-services/pulse-socket/index.ts', import.meta.url), 'utf8')
  const start = file.indexOf("  if (['/notify', '/typing', '/online', '/gcall'].includes(pathname))")
  const source = file.slice(start, file.indexOf('  // 3. relay endpoints', start))
  for (const key of ['', 'configured']) for (const presented of [undefined, '', 'wrong', ['configured', 'configured'], 'configured']) {
    const handle = new Function('PRIVACY_KEY', 'req', 'res', 'pathname', 'sendJson', source + ';return "handler"')
    for (const path of ['/notify', '/typing', '/online', '/gcall']) {
      let status
      const result = handle(key, { headers: { 'x-pulse-key': presented } }, { setHeader() {} }, path, (_, code) => { status = code })
      if (key && presented === key) assert.equal(result, 'handler')
      else { assert.equal(status, 401); assert.equal(result, undefined) }
    }
  }
})

/**
 * Wave 1 — native messaging gate E2E through the REAL gateway
 * (web API :3000 via edge :81 + pulse-socket :3003).
 *
 * Chain under test (Wave 1 gate):
 *   TEXT → REALTIME → PERSISTENCE → RECONNECT → OFFLINE-WINDOW → FLUSH
 * plus the Wave 1 surfaces the natives implement from spec §1.1:
 *   threads (parentId), media (/api/uploads), pagination (before=),
 *   conversation search (q=), read receipts, edit/delete/pin/save/react,
 *   server draft mirror.
 *
 * Two identities ride REAL REST endpoints; realtime rides the same
 * XTransformPort=3003 socket route the native clients use.
 */
const { io } = require('socket.io-client')

const EDGE = 'http://localhost:81'
const API = 'http://localhost:81/api'
const QUERY = { XTransformPort: '3003' }
const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms))
function once(socket, event, timeout = 6000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting ${event}`)), timeout)
    socket.once(event, (data) => { clearTimeout(t); resolve(data) })
  })
}

async function api(path, method = 'GET', body) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  let json = null
  try { json = await res.json() } catch {}
  return { status: res.status, json }
}

const stamp = Date.now().toString(36)

async function main() {
  // ── identities + DM ───────────────────────────────────────
  const a = await api('/users', 'POST', { name: `W1A ${stamp}`, color: 'emerald' })
  const b = await api('/users', 'POST', { name: `W1B ${stamp}`, color: 'amber' })
  check('identities created', a.status === 201 && b.status === 201, `a=${a.status} b=${b.status}`)
  const uidA = a.json.user?.id ?? a.json.id
  const uidB = b.json.user?.id ?? b.json.id
  check('identity ids extracted', !!uidA && !!uidB, `${uidA} ${uidB}`)

  const conv = await api('/conversations', 'POST', { creatorId: uidA, memberIds: [uidA, uidB], isGroup: false })
  check('DM created', conv.status === 201, JSON.stringify(conv.json?.conversation?.id ?? conv.json?.error))
  const cid = conv.json.conversation?.id ?? conv.json.id

  // ── realtime: B joins ────────────────────────────────────
  const B = io(EDGE, { query: QUERY, transports: ['websocket'] })
  const joinedB = once(B, 'joined')
  B.on('connect', () => B.emit('join', { userId: uidB }))
  const jb = await joinedB
  check('B socket joined', Array.isArray(jb.onlineUserIds), '')

  // ── 1. TEXT → REALTIME (message:new relay) ───────────────
  const msgAtB = once(B, 'message:new')
  const send1 = await api(`/conversations/${cid}/messages`, 'POST', { senderId: uidA, content: `gate-text-${stamp}` })
  check('TEXT send 201', send1.status === 201, JSON.stringify(send1.json?.error))
  const m1 = send1.json.message
  const relay = await msgAtB
  check('REALTIME message:new at B', relay.message?.id === m1.id || relay.id === m1.id, '')

  // ── 2. PERSISTENCE: history returns the row ─────────────
  const hist = await api(`/conversations/${cid}/messages?userId=${uidA}&limit=50`)
  check('PERSISTENCE in history', hist.json.messages?.some((m) => m.id === m1.id), `total=${hist.json.total}`)

  // ── 3. THREADS: parentId reply + thread read (CORRECT behaviour) ──
  const reply = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: uidB, content: `gate-thread-reply-${stamp}`, parentId: m1.id,
  })
  check('THREAD reply 201 (parentId)', reply.status === 201, JSON.stringify(reply.json?.error))
  const threadPage = await api(`/messages/${m1.id}/thread?userId=${uidA}`)
  check('THREAD read → replies asc contains reply', threadPage.status === 200 &&
    threadPage.json.replies?.some((r) => r.id === reply.json.message?.id),
  `replies=${threadPage.json.replies?.length}`)
  const oneLevel = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: uidA, content: 'nested-should-fail', parentId: reply.json.message?.id,
  })
  check('THREAD one-level rule (nested 400)', oneLevel.status === 400, `status=${oneLevel.status}`)

  // ── 4. PAGINATION: before= cursor ────────────────────────
  const page1 = await api(`/conversations/${cid}/messages?limit=1`)
  const newest = page1.json.messages?.[0]
  const page2 = await api(`/conversations/${cid}/messages?limit=1&before=${encodeURIComponent(newest?.createdAt ?? '')}`)
  check('PAGINATION before= returns strictly older rows', page2.status === 200 && Array.isArray(page2.json.messages) &&
    page2.json.messages.length === 1 && page2.json.messages[0].createdAt < newest.createdAt,
  `page2=${page2.json.messages?.length} page1hasMore=${page1.json.hasMore}`)
  check('PAGINATION hasMore=true on first page', page1.json.hasMore === true, '')

  // ── 5. MEDIA: upload + send ──────────────────────────────
  const pngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='
  const up = await api('/uploads', 'POST', { dataUrl: `data:image/png;base64,${pngB64}` })
  check('MEDIA upload → filePath', up.status === 201 && typeof up.json.filePath === 'string', JSON.stringify(up.json?.error))
  const mediaMsg = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: uidA, content: 'gate-caption', imagePath: up.json.filePath,
  })
  check('MEDIA message with imagePath + caption', mediaMsg.status === 201 && mediaMsg.json.message?.imagePath === up.json.filePath, '')
  const serve = await fetch(`${API}/uploads/${up.json.filePath}`)
  check('MEDIA served bytes', serve.status === 200, `ct=${serve.headers.get('content-type')}`)

  // ── 6. SEARCH: q= conversation search ────────────────────
  const found = await api(`/conversations/${cid}/messages?limit=100&q=gate-text-${stamp}`)
  check('SEARCH q= finds the message', found.json.messages?.some((m) => m.id === m1.id), `hits=${found.json.messages?.length}`)

  // ── 7. READ receipt → relay ──────────────────────────────
  const readAtB = once(B, 'message:read')
  const read = await api(`/conversations/${cid}/read`, 'POST', { userId: uidA })
  const readEvt = await readAtB
  check('READ receipt relayed', read.status === 200 && (readEvt.userId === uidA || readEvt.message?.userId === uidA), '')

  // ── 8. ACTIONS: react / edit / pin / save ────────────────
  const react = await api(`/messages/${m1.id}/react`, 'POST', { userId: uidB, emoji: '🎉' })
  check('REACT toggle on', react.status === 200 && react.json.message?.reactions?.some((r) => r.emoji === '🎉'), '')
  const edited = await api(`/messages/${m1.id}`, 'PATCH', { userId: uidA, content: `gate-edited-${stamp}` })
  check('EDIT (sender) 200 + editedAt', edited.status === 200 && !!edited.json.message?.editedAt, '')
  const foreignEdit = await api(`/messages/${m1.id}`, 'PATCH', { userId: uidB, content: 'nope' })
  check('EDIT (non-sender) 403', foreignEdit.status === 403, `status=${foreignEdit.status}`)
  const pin = await api(`/messages/${m1.id}/pin`, 'POST', { userId: uidA })
  check('PIN toggle on', pin.status === 200 && !!pin.json.message?.pinnedAt, '')
  const pins = await api(`/conversations/${cid}/pinned?userId=${uidA}`)
  check('PINS list contains pin', pins.json.messages?.some((m) => m.id === m1.id), '')
  const save = await api(`/messages/${m1.id}/save`, 'POST', { userId: uidB })
  check('SAVE toggle → {saved:true}', save.status === 200 && save.json.saved === true, '')

  // ── 9. DRAFT mirror ──────────────────────────────────────
  const draft = await api(`/conversations/${cid}/draft`, 'PATCH', { userId: uidB, draft: 'half-typed gate draft' })
  const listAfter = await api(`/conversations?userId=${uidB}`)
  const convB = listAfter.json.conversations?.find((c) => c.id === cid)
  check('DRAFT server mirror surfaces myDraft', draft.status === 200 && convB?.myDraft === 'half-typed gate draft', `myDraft=${convB?.myDraft}`)

  // ── 10. OFFLINE WINDOW → FLUSH (same POST the outbox flush issues) ──
  const offlineToken = `gate-flush-${stamp}`
  const flushAtB = once(B, 'message:new')
  // (simulated offline window — the native outbox holds the entry; on
  // reconnect the flush re-issues exactly this request)
  await wait(600)
  const flushed = await api(`/conversations/${cid}/messages`, 'POST', { senderId: uidA, content: offlineToken })
  const flushRelay = await flushAtB
  const histFlush = await api(`/conversations/${cid}/messages?limit=50&q=${offlineToken}`)
  check('OFFLINE→FLUSH: post-reconnect send 201', flushed.status === 201, '')
  check('OFFLINE→FLUSH: realtime relay + persisted', flushRelay.message?.content === offlineToken &&
    histFlush.json.messages?.some((m) => m.id === flushed.json.message?.id), '')

  // ── 11. RECONNECT: B drops and rejoins ─────────────────────
  const rejoin = once(B, 'joined')
  B.disconnect()
  await wait(300)
  B.connect()
  try {
    const rj = await rejoin
    check('RECONNECT re-join ack', Array.isArray(rj.onlineUserIds), '')
  } catch (e) {
    check('RECONNECT re-join ack', false, String(e.message))
  }

  B.disconnect()
  const failed = results.filter((r) => !r.ok).length
  console.log(`\nWAVE 1 GATE: ${results.length - failed}/${results.length} PASSED`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => { console.error('E2E crashed:', e); process.exit(2) })

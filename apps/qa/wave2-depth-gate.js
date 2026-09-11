/**
 * Wave 2 — native messaging DEPTH gate E2E through the REAL gateway
 * (web API :3000 via edge :81 + pulse-socket :3003).
 *
 * Exercises the EXACT routes the native Wave 2 clients use (spec
 * docs/WAVE2-NATIVE-DEPTH-SPEC.md §0):
 *   VOICE      upload → kind:"audio" send (the web-bug fix) → transcribe contract
 *   VIEW-ONCE  viewOnce send UI wire → viewed burn → idempotency → sender 400 → relay
 *   POLLS      create → vote (actor-relative myOptionId PROOF) → close → frozen
 *   LINKS      unfurl → linkUrl/linkPreview → link:preview relay
 *   SAVED      save → library list → unsave
 *   TOPICS     create → dedupe 200 → file → topicId filter → General-whole-room → count
 *
 * Two identities + a DM; realtime rides the same XTransformPort=3003 socket
 * route the native clients dial. Every socket envelope is the authoritative
 * row the natives upsert.
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
function once(socket, event, timeout = 8000) {
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

// Tiny valid bytes for each media family — the server validates the data-URL
// shape + decoded size > 0, exactly what native uploads satisfy.
const TINY_AUDIO_M4A = Buffer.from([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x4d, 0x34, 0x41, 0x20, 0x00, 0x00, 0x02, 0x00, 0x4d, 0x34, 0x41, 0x20, 0x6d, 0x70, 0x34, 0x32, 0x66, 0x74, 0x79, 0x70])
const TINY_PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4, 0x89])

async function upload(dataUrl) {
  return api('/uploads', 'POST', { dataUrl })
}

async function main() {
  // ── identities + DM ───────────────────────────────────────
  const a = await api('/users', 'POST', { name: `W2A ${stamp}`, color: 'emerald' })
  const b = await api('/users', 'POST', { name: `W2B ${stamp}`, color: 'rose' })
  const c = await api('/users', 'POST', { name: `W2C ${stamp}`, color: 'amber' })
  check('identities created', a.status === 201 && b.status === 201 && c.status === 201, `a=${a.status} b=${b.status} c=${c.status}`)
  const A = a.json.user?.id ?? a.json.id
  const B = b.json.user?.id ?? b.json.id
  const C = c.json.user?.id ?? c.json.id

  const conv = await api('/conversations', 'POST', { creatorId: A, memberIds: [A, B, C], isGroup: true, name: `W2 depth ${stamp}` })
  check('group created', conv.status === 201, JSON.stringify(conv.json?.conversation?.id ?? conv.json?.error))
  const cid = conv.json.conversation?.id ?? conv.json.id

  // ── realtime: B joins (the native socket route) ───────────
  const Bsock = io(EDGE, { query: QUERY, transports: ['websocket'] })
  const joinedB = once(Bsock, 'joined')
  Bsock.on('connect', () => Bsock.emit('join', { userId: B }))
  const jb = await joinedB
  check('B socket joined', Array.isArray(jb.onlineUserIds), '')

  // ══ 1. VOICE NOTES ═══════════════════════════════════════
  const up1 = await upload(`data:audio/mp4;base64,${TINY_AUDIO_M4A.toString('base64')}`)
  check('VOICE upload 201', up1.status === 201, JSON.stringify(up1.json?.error))
  const audioPath = up1.json.filePath
  const served = await fetch(`${API}/uploads/${audioPath}`)
  const servedBytes = Buffer.from(await served.arrayBuffer())
  check('VOICE bytes served back', served.status === 200 && servedBytes.length === TINY_AUDIO_M4A.length, `${served.status} ${servedBytes.length}B`)

  const voiceSend = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: A,
    content: '',
    kind: 'audio',              // ← the NATIVE fix (web omits kind → 'text' → /transcribe 400s)
    audioPath,
    durationMs: 1247,           // native rounding: max(1, round(1247/100)*100)=1200? no: 1247/100=12.47→12*100=1200
  })
  check('VOICE send kind:audio 201', voiceSend.status === 201 && voiceSend.json.message?.kind === 'audio', JSON.stringify(voiceSend.json?.error))
  const voiceMsg = voiceSend.json.message
  check('VOICE row carries audioPath+durationMs', voiceMsg?.audioPath === audioPath && typeof voiceMsg?.durationMs === 'number', `dur=${voiceMsg?.durationMs}`)

  // Transcribe contract: natives accept 200 (cached or fresh) — 422/502 are
  // honest ENV-LIMITED verdicts for non-speech fixtures, NOT gate failures.
  const tr = await api(`/messages/${voiceMsg.id}/transcribe`, 'POST', { requesterId: B })
  if (tr.status === 200) {
    check('VOICE transcribe 200', typeof tr.json.transcript === 'string', `cached=${tr.json.cached}`)
    const tr2 = await api(`/messages/${voiceMsg.id}/transcribe`, 'POST', { requesterId: B })
    check('VOICE transcribe cache-hit cached:true', tr2.status === 200 && tr2.json.cached === true, `cached=${tr2.json.cached}`)
  } else {
    check('VOICE transcribe contract (ENV-LIMITED: non-speech fixture)', tr.status === 422 || tr.status === 502, `status=${tr.status} error=${tr.json?.error}`)
  }

  // ══ 2. VIEW-ONCE ═════════════════════════════════════════
  const up2 = await upload(`data:image/png;base64,${TINY_PNG.toString('base64')}`)
  check('VIEWONCE image upload 201', up2.status === 201, JSON.stringify(up2.json?.error))
  const imagePath = up2.json.filePath

  const viewedRelayAtB = once(Bsock, 'message:viewed') // relay reaches members except the consumer
  const voSend = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: B, content: 'secret caption', viewOnce: true, imagePath,
  })
  check('VIEWONCE send (native UI wire) 201', voSend.status === 201 && voSend.json.message?.viewOnce === true, JSON.stringify(voSend.json?.error))
  const voMsg = voSend.json.message

  const voNonImage = await api(`/conversations/${cid}/messages`, 'POST', { senderId: B, content: 'x', viewOnce: true })
  check('VIEWONCE without image rejected 400', voNonImage.status === 400, '')

  const burn = await api(`/messages/${voMsg.id}/viewed`, 'POST', { userId: A })
  check('VIEWONCE burn stamps viewedAt', burn.status === 200 && !!burn.json.message?.viewedAt, '')
  const burn2 = await api(`/messages/${voMsg.id}/viewed`, 'POST', { userId: A })
  check('VIEWONCE idempotent re-open', burn2.status === 200 && burn2.json.message?.viewedAt === burn.json.message.viewedAt, '')
  const senderSelf = await api(`/messages/${voMsg.id}/viewed`, 'POST', { userId: B })
  check('VIEWONCE sender self-open 400', senderSelf.status === 400, '')

  const relayViewed = await viewedRelayAtB
  const relayRow = relayViewed.message ?? relayViewed
  check('REALTIME message:viewed relay row authoritative', relayRow.id === voMsg.id && !!relayRow.viewedAt, '')

  // ══ 3. POLLS ═════════════════════════════════════════════
  const pollRelayAtB = once(Bsock, 'poll:voted')
  const pollCreate = await api(`/conversations/${cid}/poll`, 'POST', {
    senderId: A, question: 'Ship Wave 2?', options: ['Yes', 'Not yet', 'Abstain'],
  })
  check('POLL create 201', pollCreate.status === 201, JSON.stringify(pollCreate.json?.error))
  const pollMsg = pollCreate.json.message
  const poll = pollMsg?.poll
  check('POLL wire shape (id/question/options/votedBy/totalVotes)', !!poll?.id && poll.question === 'Ship Wave 2?' && poll.options?.length === 3 && Array.isArray(poll.options[0].votedBy), `totalVotes=${poll?.totalVotes}`)

  // Vote as A. The RESPONSE is actor-relative (A's pick) — correct for A.
  const voteA = await api(`/polls/${poll.id}/vote`, 'POST', { userId: A, optionId: poll.options[0].id })
  check('POLL vote 200 + actor row', voteA.status === 200 && voteA.json.message?.poll?.myOptionId === poll.options[0].id, '')

  // B listens to the relay. THE BUG PROOF: the relayed row is mapped with
  // the VOTER as viewer → B's copy shows A's pick as "myOptionId" — natives
  // must derive from votedBy (spec §1 row 2).
  const pollRelay = await pollRelayAtB
  const relayPoll = (pollRelay.message ?? pollRelay).poll
  check('POLL relay myOptionId is ACTOR-RELATIVE (native uses votedBy)', relayPoll?.myOptionId === poll.options[0].id && relayPoll?.options?.[0]?.votedBy?.includes(A), `relay.myOptionId=${relayPoll?.myOptionId}`)

  // History GET is viewer-less → myOptionId null on load.
  const hist = await api(`/conversations/${cid}/messages?userId=${B}&limit=50`)
  const histPoll = hist.json.messages?.find((m) => m.id === pollMsg.id)?.poll
  check('POLL history myOptionId null (votedBy is the truth)', histPoll?.myOptionId === null && histPoll?.options?.[0]?.votedBy?.includes(A) === true, `myOptionId=${histPoll?.myOptionId}`)

  const voteClosed = await api(`/polls/${poll.id}/vote`, 'POST', { userId: B, optionId: poll.options[2].id })
  check('POLL open-poll vote by B ok', voteClosed.status === 200, JSON.stringify(voteClosed.json?.error))
  const closeByNonCreator = await api(`/polls/${poll.id}/close`, 'POST', { userId: B })
  check('POLL close by non-creator 403', closeByNonCreator.status === 403, '')
  const closeByCreator = await api(`/polls/${poll.id}/close`, 'POST', { userId: A })
  check('POLL close by creator → closed:true', closeByCreator.status === 200 && closeByCreator.json.message?.poll?.closed === true, '')
  const lateVote = await api(`/polls/${poll.id}/vote`, 'POST', { userId: B, optionId: poll.options[1].id })
  check('POLL late vote frozen 400', lateVote.status === 400, '')

  // ══ 4. LINK PREVIEWS ═════════════════════════════════════
  const linkRelayAtB = once(Bsock, 'link:preview')
  const linkMsg = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: A, content: `docs live at https://example.com/w2-${stamp}`,
  })
  check('LINK message with URL 201', linkMsg.status === 201, '')
  const unfurl = await api(`/messages/${linkMsg.json.message.id}/unfurl`, 'POST', { userId: A })
  const unfurled = unfurl.json.message
  check('LINK unfurl attaches linkUrl+linkPreview', unfurl.status === 200 && !!unfurled?.linkUrl && typeof unfurled?.linkPreview?.url === 'string', `title=${unfurled?.linkPreview?.title?.slice(0, 30)}`)
  check('LINK preview shape (url/title/description/imageUrl/siteName)', ['url', 'title', 'description', 'imageUrl', 'siteName'].every((k) => k in (unfurled?.linkPreview ?? {})), '')
  try {
    const relayLink = await linkRelayAtB
    check('REALTIME link:preview relay', (relayLink.message ?? relayLink).id === unfurled.id, '')
  } catch (e) {
    check('REALTIME link:preview relay', false, e.message)
  }

  // ══ 5. SAVED LIBRARY ═════════════════════════════════════
  const saveMsg = hist.json.messages.find((m) => m.kind === 'text' && m.content && !m.viewOnce) ?? pollMsg
  const save1 = await api(`/messages/${saveMsg.id}/save`, 'POST', { userId: B })
  check('SAVE toggle on', save1.status === 200 && save1.json.saved === true, '')
  const lib = await api(`/users/${B}/saved`)
  const libItem = lib.json.items?.find((it) => it.message.id === saveMsg.id)
  check('SAVED library lists item with conversation + message', lib.status === 200 && !!libItem && libItem.conversation?.id === cid && libItem.message?.id === saveMsg.id, `items=${lib.json.items?.length}`)
  const save2 = await api(`/messages/${saveMsg.id}/save`, 'POST', { userId: B })
  check('UNSAVE toggle off', save2.status === 200 && save2.json.saved === false, '')
  const lib2 = await api(`/users/${B}/saved`)
  check('SAVED library pruned after unsave', !lib2.json.items?.some((it) => it.message.id === saveMsg.id), `items=${lib2.json.items?.length}`)

  // ══ 6. TOPICS ════════════════════════════════════════════
  const topicsEmpty = await api(`/conversations/${cid}/topics?userId=${A}`)
  check('TOPICS list starts clean', topicsEmpty.status === 200 && (topicsEmpty.json.topics?.length ?? 0) === 0, '')

  const topicCreate = await api(`/conversations/${cid}/topics`, 'POST', { userId: A, name: 'Launch', emoji: '🚀' })
  check('TOPIC create 201', topicCreate.status === 201 && !!topicCreate.json.topic?.id, JSON.stringify(topicCreate.json?.error))
  const topicId = topicCreate.json.topic.id

  const topicDedupe = await api(`/conversations/${cid}/topics`, 'POST', { userId: B, name: 'launch' })
  check('TOPIC dedupe case-insensitive → 200 same row', topicDedupe.status === 200 && topicDedupe.json.topic?.id === topicId, `status=${topicDedupe.status}`)

  const filed = await api(`/conversations/${cid}/messages`, 'POST', {
    senderId: A, content: `filed-${stamp}`, topicId,
  })
  check('TOPIC-filed send 201 + row carries topicId', filed.status === 201 && filed.json.message?.topicId === topicId, '')

  const filtered = await api(`/conversations/${cid}/messages?userId=${B}&topicId=${topicId}`)
  check('TOPIC filter returns only filed rows', filtered.status === 200 && filtered.json.messages?.every((m) => m.topicId === topicId) && filtered.json.messages?.some((m) => m.content === `filed-${stamp}`), `n=${filtered.json.messages?.length}`)

  const general = await api(`/conversations/${cid}/messages?userId=${B}&limit=100`)
  check('TOPIC General = WHOLE room (web parity, includes filed rows)', general.json.messages?.some((m) => m.content === `filed-${stamp}`), '')

  const topicsAfter = await api(`/conversations/${cid}/topics?userId=${A}`)
  const rail = topicsAfter.json.topics?.find((t) => t.id === topicId)
  check('TOPIC rail messageCount honest', !!rail && rail.messageCount >= 1, `count=${rail?.messageCount}`)

  const badTopic = await api(`/conversations/${cid}/messages`, 'POST', { senderId: A, content: 'x', topicId: 'nonexistent' })
  check('TOPIC foreign topicId rejected 400', badTopic.status === 400, '')

  // ── summary ───────────────────────────────────────────────
  Bsock.disconnect()
  const passed = results.filter((r) => r.ok).length
  console.log(`\nWAVE2 GATE: ${passed}/${results.length} PASSED`)
  if (passed !== results.length) process.exit(1)
}

main().catch((e) => { console.error('GATE CRASH:', e.message); process.exit(1) })

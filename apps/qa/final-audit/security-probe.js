// FINAL FULL-APP AUDIT — security probe suite (audit-only artifact, not product code)
// Targets: token lifecycle, internal-key boundaries, role/ownership enforcement,
// privacy enforcement, server caps. Complements (does not duplicate) wave suites.
const BASE = 'http://localhost:3000'
const PULSE_KEY = process.env.CRON_SECRET // bun loads .env from repo root; probe fails fast if unset
let pass = 0, fail = 0
const results = []
function check(name, cond, evidence) {
  if (cond) { pass++; results.push(`PASS  ${name}${evidence ? ' — ' + evidence : ''}`) }
  else { fail++; results.push(`FAIL  ${name}${evidence ? ' — ' + evidence : ''}`) }
}
async function j(method, path, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(Object.keys(headers).length ? headers : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let json = null
  try { json = await res.json() } catch {}
  return { status: res.status, json, headers: res.headers }
}
const uniq = `audit${Date.now().toString(36)}`

async function main() {
  // --- identities
  const mk = async (n) => (await j('POST', '/api/users', { name: n, color: 'emerald' })).json.user
  const A = await mk(`${uniq}-A`), B = await mk(`${uniq}-B`), C = await mk(`${uniq}-C`)
  check('identities created (3)', A?.id && B?.id && C?.id)

  // --- token semantics
  const tokRes = await j('POST', '/api/users', { name: `${uniq}-T`, color: 'cyan' })
  const tok1 = tokRes.json?.token
  check('create issues token (32B hex)', typeof tok1 === 'string' && tok1.length === 64, `len=${tok1?.length}`)
  const login1 = await j('POST', '/api/users/login', { name: `${uniq}-T` })
  const tok2 = login1.json?.token
  check('login rotates token', !!tok2 && tok2 !== tok1)
  const rOld = await j('GET', `/api/users/${tokRes.json.user.id}`, undefined, { Authorization: `Bearer ${tok1}` })
  check('rotated old token → 401', rOld.status === 401, `got ${rOld.status}`)
  const rNew = await j('GET', `/api/users/${tokRes.json.user.id}`, undefined, { Authorization: `Bearer ${tok2}` })
  check('current token accepted', rNew.status === 200, `got ${rNew.status}`)
  const rBad = await j('GET', `/api/users/${tokRes.json.user.id}`, undefined, { Authorization: 'Bearer deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef' })
  check('invalid bearer → 401', rBad.status === 401, `got ${rBad.status}`)
  const rNone = await j('GET', `/api/users/${tokRes.json.user.id}`)
  check('headerless (optional-verify) passes per migration semantics', rNone.status === 200, `got ${rNone.status}`)

  // --- internal boundary
  const vOK = await j('GET', `/api/internal/verify?userId=${tokRes.json.user.id}&token=${tok2}`, undefined, { 'x-pulse-key': PULSE_KEY })
  check('internal/verify valid token → {valid:true}', vOK.status === 200 && vOK.json?.valid === true, `got ${vOK.status} ${JSON.stringify(vOK.json)}`)
  const vBad = await j('GET', `/api/internal/verify?userId=${tokRes.json.user.id}&token=nope`, undefined, { 'x-pulse-key': PULSE_KEY })
  check('internal/verify invalid token → {valid:false}', vBad.json?.valid === false)
  const vNoKey = await j('GET', `/api/internal/verify?userId=x&token=y`)
  const pNoKey = await j('GET', '/api/internal/privacy')
  const dNoKey = await j('POST', '/api/maintenance/dispatch', {})
  check('internal/verify without key refused', vNoKey.status === 401 || vNoKey.status === 403, `got ${vNoKey.status}`)
  check('internal/privacy without key refused', pNoKey.status === 401 || pNoKey.status === 403, `got ${pNoKey.status}`)
  check('maintenance/dispatch without key refused', dNoKey.status === 401 || dNoKey.status === 403, `got ${dNoKey.status}`)
  if (!PULSE_KEY) { console.error('PROBE CONFIG: CRON_SECRET not set (bun .env load) — aborting'); process.exit(2) }
  const dDefaultKey = await j('POST', '/api/maintenance/dispatch', {}, { 'x-pulse-key': 'pulse-dispatch-key' })
  check('S5 FIXED: well-known default constant refused on dispatch', dDefaultKey.status === 401 || dDefaultKey.status === 403, `got ${dDefaultKey.status}`)

  // --- conversation + membership enforcement
  const conv = await j('POST', '/api/conversations', { creatorId: A.id, memberIds: [B.id, C.id], isGroup: true, name: `${uniq}-grp` })
  const convId = conv.json?.conversation?.id ?? conv.json?.id
  check('group created', !!convId)
  const renameNonAdmin = await j('PATCH', `/api/conversations/${convId}`, { requesterId: B.id, name: 'hijacked' })
  check('non-admin rename → 403', renameNonAdmin.status === 403, `got ${renameNonAdmin.status}`)
  const removeByNonAdmin = await j('DELETE', `/api/conversations/${convId}/members/${C.id}`, { requesterId: B.id })
  check('non-admin remove member → 403', removeByNonAdmin.status === 403, `got ${removeByNonAdmin.status}`)
  const demoteLastAdmin = await j('PATCH', `/api/conversations/${convId}/members/${A.id}`, { requesterId: A.id, action: 'demote' })
  check('demote sole admin → 400', demoteLastAdmin.status === 400, `got ${demoteLastAdmin.status}`)
  const selfRemove = await j('DELETE', `/api/conversations/${convId}/members/${A.id}`, { requesterId: A.id })
  check('self-removal → 400 (honest leave-group pointer)', selfRemove.status === 400 && /Leave group/.test(selfRemove.json?.error ?? ''), `got ${selfRemove.status}`)
  await j('PATCH', `/api/conversations/${convId}/members/${C.id}`, { requesterId: A.id, action: 'promote' })
  const removeAdmin = await j('DELETE', `/api/conversations/${convId}/members/${C.id}`, { requesterId: A.id })
  check('remove another admin → 403', removeAdmin.status === 403, `got ${removeAdmin.status}`)

  // --- message rules
  const send = async (uid, content) => j('POST', `/api/conversations/${convId}/messages`, { senderId: uid, content })
  const m1 = await send(A.id, 'audit-root')
  const mid = m1.json?.message?.id
  check('message sent', m1.status === 200 || m1.status === 201, `got ${m1.status}`)
  const long = 'x'.repeat(2001)
  const over = await send(A.id, long)
  check('2001-char message refused', over.status >= 400 && over.status < 500, `got ${over.status}`)
  const editForeign = await j('PATCH', `/api/messages/${mid}`, { userId: B.id, content: 'forged' })
  check('foreign edit refused', editForeign.status >= 400 && editForeign.status < 500, `got ${editForeign.status}`)
  const delForeign = await j('DELETE', `/api/messages/${mid}?userId=${B.id}`)
  check('foreign delete refused', delForeign.status >= 400 && delForeign.status < 500, `got ${delForeign.status}`)

  // --- slow mode
  const sm = await j('PATCH', `/api/conversations/${convId}/slow-mode`, { userId: A.id, seconds: 30 })
  check('admin sets slow mode', sm.status === 200, `got ${sm.status}`)
  const s2 = await send(B.id, 'first-ok')
  const s3 = await send(B.id, 'second-should-429')
  check('slow-mode 429 with retryAfter', s3.status === 429 && (s3.json?.retryAfter ?? s3.json?.error?.retryAfter) !== undefined, `got ${s3.status} body=${JSON.stringify(s3.json).slice(0, 120)}`)
  await j('PATCH', `/api/conversations/${convId}/slow-mode`, { userId: A.id, seconds: 0 })

  // --- privacy enforcement (readReceipts off)
  await j('PATCH', '/api/settings', { userId: B.id, preferences: { readReceipts: false } })
  await send(B.id, 'read-me')
  await j('POST', `/api/conversations/${convId}/read`, { userId: B.id })
  const convGet = await j('GET', `/api/conversations/${convId}?userId=${A.id}`)
  const row = convGet.json?.conversation
  const memberB = (row?.members ?? []).find((m) => m.id === B.id)
  const hidden = memberB && (memberB.lastReadAt === '1970-01-01T00:00:00.000Z' || memberB.lastReadAt?.startsWith('1970'))
  check('readReceipts=false → lastReadAt epoch for viewers', !!hidden, `lastReadAt=${memberB?.lastReadAt}`)

  // --- blocks
  await j('POST', `/api/users/${A.id}/block`, { userId: B.id }) // B blocks A (id param = target)
  const dmBlocked = await j('POST', '/api/conversations', { creatorId: A.id, memberIds: [B.id], isGroup: false })
  check('blocked DM create refused (409/4xx)', dmBlocked.status >= 400 && dmBlocked.status < 500, `got ${dmBlocked.status}`)
  await j('DELETE', `/api/users/${A.id}/block?userId=${B.id}`)

  // --- report idempotency
  const rep1 = await j('POST', `/api/users/${A.id}/report`, { userId: B.id, reason: 'spam', details: 'audit' })
  const rep2 = await j('POST', `/api/users/${A.id}/report`, { userId: B.id, reason: 'spam', details: 'audit' })
  check('report accepted + idempotent', rep1.status < 300 && rep2.status < 300, `${rep1.status}/${rep2.status}`)

  // --- uploads cap
  const big = 'A'.repeat(4_600_000)
  const up = await j('POST', '/api/uploads', { dataUrl: 'data:image/jpeg;base64,' + big })
  check('oversize media upload refused', up.status >= 400, `got ${up.status}`)

  console.log(results.join('\n'))
  console.log(`\nSECURITY PROBE: ${pass} PASS / ${fail} FAIL`)
  process.exit(fail ? 1 : 0)
}
main().catch((e) => { console.error('PROBE ERROR', e); process.exit(1) })

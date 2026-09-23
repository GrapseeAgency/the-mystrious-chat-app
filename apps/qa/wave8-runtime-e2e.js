// ─────────────────────────────────────────────────────────────
// PULSE — WAVE 8 RUNTIME E2E (real backend, no mocks).
// Master-spec §7 "Platform & hardening": session tokens (A-1/A-2),
// settings prefs blob, optional-verify proxy semantics, relay join
// gate. Mirrors the wave5/6/7 ledger style: PASS/FAIL per case.
// Run: node apps/qa/wave8-runtime-e2e.js
// ─────────────────────────────────────────────────────────────
const { io } = require('socket.io-client')

const BASE = process.env.PULSE_E2E_BASE || 'http://localhost:3000'
const SOCKET_URL = process.env.PULSE_E2E_SOCKET || 'http://localhost:3003'
let passed = 0
let failed = 0

function check(name, cond, extra) {
  if (cond) {
    passed++
    console.log(`PASS ${name}`)
  } else {
    failed++
    console.log(`FAIL ${name}`, extra ?? '')
  }
}

async function api(path, { method = 'GET', body, auth, key } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (auth) headers.Authorization = `Bearer ${auth}`
  if (key) headers['x-pulse-key'] = key
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let json = null
  try { json = await res.json() } catch {}
  return { status: res.status, json }
}

function joinOnce(payload) {
  return new Promise((resolve) => {
    const s = io(SOCKET_URL, { path: '/socket.io', transports: ['websocket'], reconnection: false, timeout: 5000 })
    const out = { joined: false, error: null, serverDisconnected: false }
    const done = () => { try { s.disconnect() } catch {}; resolve(out) }
    s.on('connect', () => s.emit('join', payload))
    s.on('joined', () => { out.joined = true; done() })
    s.on('join:error', (d) => { out.error = d?.error ?? 'join:error'; })
    s.on('disconnect', (reason) => {
      if (reason === 'io server disconnect') out.serverDisconnected = true
      done()
    })
    s.on('connect_error', (e) => { out.error = e.message; done() })
    setTimeout(done, 6000)
  })
}

async function main() {
  const stamp = Date.now()
  // ── A-1: create issues a token ─────────────────────────────
  const name = `Wave8 Probe ${stamp}`
  const created = await api('/api/users', { method: 'POST', body: { name, color: 'teal' } })
  check('create: 201 with user + 64-hex token', created.status === 201 && typeof created.json.token === 'string' && created.json.token.length === 64, created)
  const userId = created.json.user.id
  const token = created.json.token

  // ── A-2: optional-verify proxy semantics ───────────────────
  const withValid = await api(`/api/users/${userId}`, { auth: token })
  check('proxy: valid Bearer accepted', withValid.status === 200, withValid)
  const withBad = await api(`/api/users/${userId}`, { auth: 'f'.repeat(64) })
  check('proxy: invalid Bearer 401 + honest copy', withBad.status === 401 && /invalid or has been rotated/.test(withBad.json.error || ''), withBad)
  const noAuth = await api(`/api/users/${userId}`)
  check('proxy: headerless accepted (web migration)', noAuth.status === 200, noAuth)

  // ── login: reclaim rotates ─────────────────────────────────
  const login = await api('/api/users/login', { method: 'POST', body: { name } })
  check('login: 200 + fresh 64-hex token', login.status === 200 && typeof login.json.token === 'string' && login.json.token.length === 64, login)
  const rotated = login.json.token
  const oldToken = await api(`/api/users/${userId}`, { auth: token })
  check('login: old token rejected after rotation', oldToken.status === 401, oldToken)
  const loginUnknown = await api('/api/users/login', { method: 'POST', body: { name: `Nobody ${stamp}` } })
  check('login: unknown name 404 verbatim', loginUnknown.status === 404 && loginUnknown.json.error === 'No identity with that name on this Pulse.', loginUnknown)
  const loginMissing = await api('/api/users/login', { method: 'POST', body: {} })
  check('login: missing name 400', loginMissing.status === 400, loginMissing)

  // ── internal verify (relay-facing) ─────────────────────────
  const verifyOk = await api(`/api/internal/verify?userId=${userId}&token=${rotated}`, { key: process.env.CRON_SECRET ?? '' })
  check('internal/verify: valid → true', verifyOk.status === 200 && verifyOk.json.valid === true, verifyOk)
  const verifyBad = await api(`/api/internal/verify?userId=${userId}&token=abcd`, { key: process.env.CRON_SECRET ?? '' })
  check('internal/verify: invalid → false', verifyBad.status === 200 && verifyBad.json.valid === false, verifyBad)
  const verifyNoKey = await api(`/api/internal/verify?userId=${userId}&token=${rotated}`)
  check('internal/verify: no shared key → 401', verifyNoKey.status === 401, verifyNoKey)

  // ── settings blob (F-SE contract) ──────────────────────────
  const prefsGet = await api(`/api/settings?userId=${userId}`, { auth: rotated })
  check('settings GET: defaults merged', prefsGet.status === 200 && prefsGet.json.preferences.bubbleRadius === 'lg' && prefsGet.json.preferences.notifVibrate === false, prefsGet)
  const prefsPatch = await api('/api/settings', {
    method: 'PATCH',
    body: { userId, preferences: { bubbleRadius: 'pill', wallpaper: 'dusk', reducedMotion: true } },
    auth: rotated,
  })
  check('settings PATCH: carried fields applied', prefsPatch.status === 200 && prefsPatch.json.preferences.bubbleRadius === 'pill' && prefsPatch.json.preferences.wallpaper === 'dusk' && prefsPatch.json.preferences.reducedMotion === true, prefsPatch)
  check('settings PATCH: untouched fields keep defaults', prefsPatch.json.preferences.density === 'cozy' && prefsPatch.json.preferences.notifSound === true, prefsPatch)
  const prefsJunk = await api('/api/settings', {
    method: 'PATCH',
    body: { userId, preferences: { bubbleRadius: 'neon', notifSound: 'yes' } },
    auth: rotated,
  })
  // web truth (src/app/api/settings PATCH → mergePrefs): an invalid value
  // falls back to the DEFAULT (not the previously stored value)
  check('settings PATCH: junk clamped to defaults', prefsJunk.status === 200 && prefsJunk.json.preferences.bubbleRadius === 'lg' && prefsJunk.json.preferences.notifSound === true, prefsJunk)
  const prefsNoUser = await api('/api/settings', { method: 'PATCH', body: { preferences: {} }, auth: rotated })
  check('settings PATCH: missing userId 400', prefsNoUser.status === 400, prefsNoUser)

  // ── relay join gate ────────────────────────────────────────
  const joined = await joinOnce({ userId, token: rotated })
  check('relay: valid-token join joined', joined.joined === true && joined.error === null, joined)
  const rejected = await joinOnce({ userId, token: 'ff'.repeat(32) })
  check('relay: invalid-token join refused (join:error + server disconnect)', rejected.joined === false && rejected.error !== null && rejected.serverDisconnected === true, rejected)
  const anonymous = await joinOnce({ userId })
  check('relay: token-less join accepted (migration window)', anonymous.joined === true, anonymous)

  console.log(`\n==== WAVE 8 E2E: ${passed} PASS / ${failed} FAIL ====`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('E2E crashed:', e)
  process.exit(1)
})

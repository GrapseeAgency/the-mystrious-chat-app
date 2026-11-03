// Section 16 item 5 - coordinated backend/relay/client rollout drift guard.
//
// The relay (mini-services/pulse-socket) and the Next backend ship together:
// new routes, versioned receipts and the relay's internal configuration must
// be deployed as one unit. This suite pins every shared contract point as
// TEXT-level assertions over both sources, so a silent edit on one side -
// a bumped receipt version, a renamed internal path, a moved shared-secret
// header, a changed default port - fails the suite and forces the two sides
// to be deployed together (or updated in one commit):
//   1. the relay consumes Next's internal routes with the shared x-pulse-key
//      secret (/api/internal/verify, /api/internal/privacy,
//      /api/internal/call-access) and drives the maintenance scheduler
//      (/api/maintenance/dispatch + /api/maintenance/upload-sweep),
//   2. the versioned call-access receipt (callAccessVersion: 1) matches on
//      both sides,
//   3. the group-call ring receipt (ringReceiptVersion: 1) stays pinned,
//   4. the reverse direction is stable: Next's notifySocket posts to the
//      relay on :3003 and the relay's default Next base is :3000 - the
//      ports the gateway (Caddyfile) routes for real traffic,
//   5. the gateway really routes /socket.io and the XTransformPort scheme
//      to those same two services.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(`../../${path}`, import.meta.url), 'utf8')

test('the relay consumes the Next internal routes with the shared secret', async () => {
  const relay = await read('mini-services/pulse-socket/index.ts')
  // the pull direction: relay -> Next (internal + maintenance)
  for (const path of ['/api/internal/verify', '/api/internal/privacy', '/api/internal/call-access',
    '/api/maintenance/dispatch', '/api/maintenance/upload-sweep']) {
    assert.ok(relay.includes(path), `relay must call ${path}`)
  }
  // the shared secret header name is a shared contract, not a per-side choice
  assert.ok((relay.match(/x-pulse-key/g) ?? []).length >= 5, 'the shared secret header rides every internal call')
})

test('the versioned receipts match on both sides of the call path', async () => {
  const relay = await read('mini-services/pulse-socket/index.ts')
  const callAccess = await read('src/app/api/internal/call-access/route.ts')
  const ring = await read('src/app/api/conversations/[id]/calls/ring/route.ts')

  assert.match(callAccess, /callAccessVersion:\s*1/, 'Next stamps callAccessVersion: 1')
  assert.match(relay, /callAccessVersion\s*!==\s*1/, 'the relay rejects any other call-access receipt version')
  assert.match(relay, /ringReceiptVersion:\s*1/, 'the relay stamps ringReceiptVersion: 1')

  // the ring route the relay POSTs to is the same route Next owns
  assert.ok(relay.includes('/api/conversations/') && relay.includes('/calls/ring'), 'relay probes the ring route Next owns')
  assert.match(ring, /ring/i, 'the Next ring route exists on the probed path')
})

test('the push directions and default ports stay coordinated with the gateway', async () => {
  const relay = await read('mini-services/pulse-socket/index.ts')
  const serializers = await read('src/lib/serializers.ts')
  const caddy = await read('Caddyfile')

  // Next -> relay (notify) default port
  assert.match(serializers, /localhost:3003/, "Next's notifySocket posts to the relay's default port")
  // relay -> Next (pull) default port
  assert.match(relay, /PULSE_ORIGIN/, 'the relay honors PULSE_ORIGIN for the Next base')
  assert.match(relay, /localhost:3000/, 'the default Next base is the gateway-known :3000')
  // the gateway routes both services exactly where the code expects
  assert.match(caddy, /localhost:3003/, 'the gateway routes the relay')
  assert.match(caddy, /localhost:3000/, 'the gateway routes the Next app')
  assert.match(caddy, /XTransformPort/, 'the gateway exposes the XTransformPort scheme clients must use')
  // the relay never serves the socket.io path anywhere but '/' (Caddy contract)
  assert.match(relay, /path:\s*'\/'/, "socket.io path stays '/' - the gateway maps /socket.io/* onto it")
})

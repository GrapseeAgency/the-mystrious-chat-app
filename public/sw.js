/* ─────────────────────────────────────────────────────────────
   Pulse service worker — offline app shell (v3, dev-safe).
   Strategy:
   - ALL same-origin GETs: NETWORK-FIRST (fresh always wins).
     Cache is ONLY a fallback when the network fails → it is
     impossible to serve stale JS/CSS while online.
   - API + socket.io traffic: never intercepted (always network).
   - activate: purge every legacy cache, claim clients, and tell
     them to hard-reload once so no stale bundle survives.
   ───────────────────────────────────────────────────────────── */
const CACHE = 'pulse-shell-v3'
const PRECACHE = [
  '/manifest.json',
  '/onboarding-hero.png',
  '/empty-chats.png',
  '/logo.svg',
  '/pwa-icon-192.png',
  '/pwa-icon-512.png',
  '/apple-touch-icon.png',
  '/favicon.png',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
      .then(() =>
        self.clients.matchAll({ type: 'window' }).then((clients) => {
          for (const client of clients) {
            client.postMessage({ type: 'PULSE_SW_UPDATED', cache: CACHE })
          }
        }),
      ),
  )
})

async function networkFirst(request) {
  try {
    const fresh = await fetch(request)
    if (fresh && fresh.ok && request.method === 'GET') {
      const copy = fresh.clone()
      caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => {})
    }
    return fresh
  } catch (err) {
    const hit = (await caches.match(request)) || (await caches.match('/'))
    if (hit) return hit
    if (request.mode === 'navigate') return offlineResponse()
    throw err
  }
}

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  // realtime + REST stay live — offline queueing is out of scope here
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/socket.io')) return
  if (url.searchParams.has('EIO') || url.searchParams.has('XTransformPort')) return

  // network-first for everything (navigations, JS chunks, assets):
  // online → always fresh; offline → best cached copy available
  event.respondWith(networkFirst(req))
})

function offlineResponse() {
  return new Response(
    `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>Pulse — offline</title><style>html,body{height:100%;margin:0}body{display:grid;place-items:center;background:#09090b;color:#fafafa;font:15px/1.5 ui-sans-serif,system-ui,-apple-system,sans-serif}.card{text-align:center;padding:2.5rem}.dot{width:64px;height:64px;margin:0 auto 1.25rem;border-radius:20px;background:linear-gradient(135deg,#34d399,#059669);box-shadow:0 10px 30px rgba(16,185,129,.35);display:grid;place-items:center}.dot::after{content:"";width:26px;height:26px;border-radius:50%;border:3px solid rgba(255,255,255,.9);border-top-color:transparent;animation:spin 1s linear infinite}h1{margin:0 0 .4rem;font-size:1.35rem;letter-spacing:-.02em}p{margin:0;color:#a1a1aa;font-size:.85rem;max-width:220px}@keyframes spin{to{transform:rotate(360deg)}}</style></head><body><div class="card"><div class="dot"></div><h1>Pulse</h1><p>You're offline. Your chats wake up the moment you reconnect.</p></div></body></html>`,
    { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } },
  )
}

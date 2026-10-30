/*
 * Pulse push service worker (Web Push display side).
 *
 * Registered by src/lib/push-client.ts. Handles:
 *   • push        → render the notification (title/body from the transport
 *                   payload; data rides along for the click route).
 *   • pushsubscriptionchange → resubscribe against the stored VAPID key and
 *                   rebind on the server (registry row is keyed by endpoint).
 *   • notificationclick → focus an open Pulse window or open a new one.
 */

self.addEventListener('install', (event) => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

function payloadOf(event) {
  try {
    return event.data ? event.data.json() : null
  } catch {
    try {
      const text = event.data ? event.data.text() : ''
      return text ? { title: 'Pulse', body: text } : null
    } catch {
      return null
    }
  }
}

self.addEventListener('push', (event) => {
  const payload = payloadOf(event) || {}
  // Retraction: close any shown notification for the retracted message
  // instead of displaying anything new. Provider TTL cannot erase a shown
  // notification; this is the recall path for deleted messages.
  if (payload.kind === 'retract') {
    const messageId = typeof payload.messageId === 'string' ? payload.messageId : ''
    const conversationId = typeof payload.conversationId === 'string' ? payload.conversationId : ''
    event.waitUntil((async () => {
      const shown = await self.registration.getNotifications()
      for (const notification of shown) {
        const data = notification.data || {}
        if ((messageId && data.messageId === messageId) ||
            (!messageId && conversationId && data.conversationId === conversationId)) {
          notification.close()
        }
      }
    })())
    return
  }
  const title = typeof payload.title === 'string' && payload.title ? payload.title : 'Pulse'
  const body = typeof payload.body === 'string' && payload.body ? payload.body : 'New activity'
  const conversationId = typeof payload.conversationId === 'string' ? payload.conversationId : ''
  const messageId = typeof payload.messageId === 'string' ? payload.messageId : ''
  event.waitUntil((async () => {
    // Socket/push dedup: a FOCUSED window already receives the realtime
    // event (in-app ping/haptic); a banner on top of it is duplicate
    // attention. No focused window (tabs closed/backgrounded) → show.
    try {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      if (clientList.some((client) => client.focused)) return
    } catch {
      // matchAll failure must not suppress a legitimate banner
    }
    await self.registration.showNotification(title, {
      body,
      tag: conversationId ? `pulse-${conversationId}` : 'pulse',
      renotify: Boolean(conversationId),
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      data: { conversationId, ...(messageId ? { messageId } : {}) },
    })
  })())
})

self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(
    (async () => {
      // Best-effort resubscribe; the client will also re-run registerPush on
      // its next focus, so this is a belt-and-braces refresh. The rebind POST
      // carries no credentials (the SW holds none): the server refuses it
      // until the next focused re-registration stamps the binding.
      try {
        const existing = await self.registration.pushManager.getSubscription()
        if (existing) await fetch('/api/push/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            platform: 'web',
            token: existing.toJSON(),
            endpoint: existing.endpoint,
          }),
        })
      } catch {
        // offline — client-side refresh covers it
      }
    })(),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const conversationId = event.notification.data && event.notification.data.conversationId
  const target = conversationId ? `/?conversation=${encodeURIComponent(conversationId)}` : '/'
  event.waitUntil(
    (async () => {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      for (const client of clientList) {
        if ('focus' in client) {
          await client.focus()
          if (conversationId && 'postMessage' in client) {
            client.postMessage({ type: 'pulse:open-conversation', conversationId })
          }
          return
        }
      }
      await self.clients.openWindow(target)
    })(),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// Pulse web push client — subscription lifecycle for the browser.
//
// registerWebPush(me):
//   1. asks /api/push/vapid-public (empty key → feature honestly disabled),
//   2. registers /sw-push.js,
//   3. Notification.requestPermission() → PushManager.subscribe (VAPID),
//   4. POSTs the serialized subscription to /api/push/register.
// unregisterWebPush(): deletes the subscription + the registry row.
//
// Result is a status string the settings screen can render honestly — this
// module NEVER fakes success (sandbox browsers may deny or lack the API).
// ─────────────────────────────────────────────────────────────────────────────
'use client'

export type WebPushStatus =
  | 'unsupported'
  | 'denied'
  | 'no-key'
  | 'error'
  | 'subscribed'
  | 'unsubscribed'

function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(base64)
  const output = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i += 1) output[i] = raw.charCodeAt(i)
  return output
}

async function subscribeExisting(me: {
  id: string
}): Promise<WebPushStatus> {
  const reg = await navigator.serviceWorker.ready
  const existing = await reg.pushManager.getSubscription()
  if (!existing) return 'unsubscribed'
  const res = await fetch('/api/push/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      userId: me.id,
      platform: 'web',
      token: existing.toJSON(),
      endpoint: existing.endpoint,
    }),
  })
  return res.ok ? 'subscribed' : 'error'
}

export async function checkWebPushSubscription(): Promise<WebPushStatus> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    return 'unsupported'
  }
  try {
    if (Notification.permission === 'denied') return 'denied'
    const reg = await navigator.serviceWorker.getRegistration('/sw-push.js')
    if (!reg) return 'unsubscribed'
    const sub = await reg.pushManager.getSubscription()
    return sub ? 'subscribed' : 'unsubscribed'
  } catch {
    return 'error'
  }
}

export async function registerWebPush(me: { id: string }): Promise<WebPushStatus> {
  if (typeof window === 'undefined') return 'unsupported'
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
    return 'unsupported'
  }
  try {
    if (Notification.permission === 'denied') return 'denied'
    const keyRes = await fetch('/api/push/vapid-public')
    const keyBody = (await keyRes.json()) as { publicKey?: string }
    const publicKey = keyBody.publicKey ?? ''
    if (!publicKey) return 'no-key'

    const reg = await navigator.serviceWorker.register('/sw-push.js')
    await navigator.serviceWorker.ready

    if (Notification.permission !== 'granted') {
      const permission = await Notification.requestPermission()
      if (permission !== 'granted') return 'denied'
    }

    const existing = await reg.pushManager.getSubscription()
    const sub =
      existing ??
      (await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey).buffer as ArrayBuffer,
      }))

    const res = await fetch('/api/push/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: me.id,
        platform: 'web',
        token: sub.toJSON(),
        endpoint: sub.endpoint,
      }),
    })
    return res.ok ? 'subscribed' : 'error'
  } catch {
    return 'error'
  }
}

export async function unregisterWebPush(): Promise<WebPushStatus> {
  if (typeof window === 'undefined' || !('serviceWorker' in navigator)) return 'unsupported'
  try {
    const reg = await navigator.serviceWorker.getRegistration('/sw-push.js')
    const sub = reg ? await reg.pushManager.getSubscription() : null
    if (sub) {
      await fetch('/api/push/register', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: sub.toJSON() }),
      })
      await sub.unsubscribe()
    }
    return 'unsubscribed'
  } catch {
    return 'error'
  }
}

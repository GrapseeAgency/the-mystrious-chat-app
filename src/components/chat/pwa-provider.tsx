// ─────────────────────────────────────────────────────────────
// Pulse Chat — PWA side-effects host (no UI).
// Registers /sw.js (offline shell), captures the install
// prompt, and toasts offline/online/update transitions.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect } from 'react'
import { toast } from 'sonner'
import { usePulsePwa } from '@/lib/pwa-store'

export function PwaProvider() {
  useEffect(() => {
    // install prompt capture — must happen before any user gesture
    const onBeforeInstall = (event: Event) => {
      event.preventDefault()
      usePulsePwa.getState().setInstallEvent(event)
    }
    window.addEventListener('beforeinstallprompt', onBeforeInstall)

    const onOffline = () => {
      toast.warning('You are offline — chats resume when you reconnect', {
        description: 'Pulse can still open from your last visit.',
      })
    }
    const onOnline = () => toast.success('Back online')
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)

    let swRegistration: ServiceWorkerRegistration | null = null
    const onControllerChange = () => {
      // a freshly-activated SW took over (update or first install)
      if (navigator.serviceWorker.controller) {
        toast('Pulse is ready offline', {
          description: 'The app shell is now cached on this device.',
        })
      }
    }
    // Auto-heal: when a new service worker activates it broadcasts
    // PULSE_SW_UPDATED — hard-reload ONCE per version so a stale
    // bundle can never linger in the tab.
    const onSwMessage = (event: MessageEvent) => {
      const data = event.data as { type?: string; cache?: string } | null
      if (data?.type !== 'PULSE_SW_UPDATED') return
      const flagKey = `pulse.sw.reloaded.${data.cache || 'v'}`
      if (sessionStorage.getItem(flagKey)) return
      sessionStorage.setItem(flagKey, '1')
      window.location.reload()
    }

    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.addEventListener('message', onSwMessage)
      navigator.serviceWorker
        .register('/sw.js', { scope: '/' })
        .then((registration) => {
          swRegistration = registration
          navigator.serviceWorker.addEventListener('controllerchange', onControllerChange)
        })
        .catch(() => {
          // SW unsupported/blocked — app keeps working normally online
        })
    }

    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstall)
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
      navigator.serviceWorker?.removeEventListener('controllerchange', onControllerChange)
      navigator.serviceWorker?.removeEventListener('message', onSwMessage)
      swRegistration = null
    }
  }, [])

  return null
}

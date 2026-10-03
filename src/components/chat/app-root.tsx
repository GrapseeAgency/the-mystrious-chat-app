// Pulse Chat - application root & boot gate.
// Waits for store hydration, validates any stored session,
// then renders Onboarding or the main tab shell.
'use client'

import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { MessageCircleHeart } from 'lucide-react'
import type { AppUser } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import { ApiError, apiJson } from '@/lib/pulse-utils'
import { Providers } from '@/components/chat/providers'
import { UiThemeAttr } from '@/components/chat/ui-theme-attr'
import { OnboardingScreen } from '@/components/chat/onboarding-screen'
import { MainShell } from '@/components/chat/main-shell'
import UserRoutePage from '@/components/chat/user-route-page'
import { ParticleLayer } from '@/components/fx/particle-layer'
import { WebGLAmbient } from '@/components/fx/webgl-glow'

type BootStatus = 'checking' | 'onboarding' | 'ready'

interface UsersResponse {
  user: AppUser
}

function Splash({ visible }: { visible: boolean }) {
  return (
    <AnimatePresence>
      {visible ? (
        <motion.div
          key="splash"
          initial={{ opacity: 1 }}
          exit={{ opacity: 0, transition: { duration: 0.25 } }}
          className="fixed inset-0 z-[60] flex items-center justify-center bg-[#f5eee6] dark:bg-[#150f0b]"
          role="status"
          aria-label="Loading Pulse"
        >
          <div className="flex flex-col items-center gap-5">
            <motion.div
              animate={{ scale: [1, 1.08, 1] }}
              transition={{ repeat: Infinity, duration: 1.6, ease: 'easeInOut' }}
              className="flex size-20 items-center justify-center rounded-[1.75rem] bg-gradient-to-br from-amber-400 to-amber-600 shadow-lg shadow-amber-500/30"
            >
              <MessageCircleHeart className="size-10 text-white" aria-hidden />
            </motion.div>
            <motion.p
              animate={{ opacity: [0.4, 0.9, 0.4] }}
              transition={{ repeat: Infinity, duration: 1.6, ease: 'easeInOut' }}
              className="text-sm font-medium text-zinc-500"
            >
              Warming up your chats…
            </motion.p>
          </div>
        </motion.div>
      ) : null}
    </AnimatePresence>
  )
}

function PhoneFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh w-full items-stretch justify-center bg-[#f5eee6] sm:items-center dark:bg-[#0d0906]">
      <div className="w-full sm:w-[420px] h-dvh sm:h-[860px] sm:max-h-[92vh] sm:rounded-[2.5rem] sm:border sm:border-zinc-200 sm:shadow-2xl overflow-hidden relative flex flex-col bg-background dark:sm:border-white/10">
        {children}
      </div>
    </div>
  )
}

const DEMO_IDENTITY = 'Alice Chen'
const DEMO_OPTOUT_KEY = 'pulse.demo.optout'

/**
 * R54 - silent demo sign-in: a browser session with no stored identity
 * lands STRAIGHT in home as the seeded demo user (the "no shitty login
 * display" directive). Explicit sign-out sets the opt-out flag so the
 * demo door never traps the user. Any failure (server down, identity
 * absent) falls back to the real onboarding - honest, no mocks.
 */
function useDemoAutoSignIn(enabled: boolean) {
  const setUser = usePulseSession((s) => s.setUser)
  const attempted = useRef(false)
  const [demoPending, setDemoPending] = useState(false)
  const [demoDone, setDemoDone] = useState(false)
  // Captured at FIRST RENDER (before any effect): the onboarding screen's
  // ?login= effect rewrites the URL during its own effect (which runs before
  // this parent hook's effect), so reading location.search inside the effect
  // would miss the deep link and race it for setUser.
  const bootHasLogin = useRef(
    typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('login'),
  )

  useEffect(() => {
    if (!enabled || attempted.current) return
    attempted.current = true
    // A ?login= deep link (the Android web shell boots this way, signing in
    // the stored native identity) OWNS the boot - the demo must not race it
    // to setUser and flip the identity underneath the deep link.
    if (bootHasLogin.current) {
      setDemoDone(true)
      return
    }
    let optOut = false
    try {
      optOut = sessionStorage.getItem(DEMO_OPTOUT_KEY) === '1'
    } catch {
      optOut = false
    }
    if (optOut) {
      setDemoDone(true)
      return
    }
    setDemoPending(true)
    const run = async () => {
      try {
        const res = await fetch(`/api/users?name=${encodeURIComponent(DEMO_IDENTITY)}`)
        if (!res.ok) throw new Error(`demo lookup ${res.status}`)
        const data = (await res.json()) as { user?: AppUser }
        if (!data.user?.id) throw new Error('demo identity missing')
        setUser(data.user)
        toast.success(`Demo sign-in - ${DEMO_IDENTITY}`, { description: 'You can switch identity from Profile.' })
      } catch {
        /* fall through to onboarding */
      } finally {
        setDemoPending(false)
        setDemoDone(true)
      }
    }
    void run()
  }, [enabled, setUser])

  // Resolving ONLY while the demo path is actually armed; a session that
  // boots with a stored user never enters the pending state.
  return enabled ? demoPending || !demoDone : false
}

function BootGate() {
  const hydrated = usePulseSession((s) => s._hasHydrated)
  const user = usePulseSession((s) => s.user)
  const clear = usePulseSession((s) => s.clear)

  // R54: no stored identity -> the silent demo sign-in decides whether we
  // still owe the user an onboarding (true = keep showing the splash).
  const demoResolving = useDemoAutoSignIn(hydrated && !user)

  // minimum splash duration so the brand moment reads on fast devices
  const [minDelayDone, setMinDelayDone] = useState(false)
  useEffect(() => {
    const timer = setTimeout(() => setMinDelayDone(true), 650)
    return () => clearTimeout(timer)
  }, [])

  // validate a stored session against the API (404 → wipe)
  const validation = useQuery({
    queryKey: ['me', user?.id ?? '-'],
    queryFn: async ({ queryKey }): Promise<AppUser> => {
      const [, id] = queryKey as [string, string]
      const res = await apiJson<UsersResponse>(`/api/users/${encodeURIComponent(id)}`)
      return res.user
    },
    enabled: hydrated && !!user,
    retry: false,
    staleTime: Infinity,
  })

  const storedUserMissing = validation.isError && validation.error instanceof ApiError && validation.error.status === 404

  useEffect(() => {
    if (storedUserMissing) clear()
  }, [storedUserMissing, clear])

  let status: BootStatus = 'checking'
  if (minDelayDone && hydrated && !demoResolving) {
    if (!user) status = 'onboarding'
    else if (!validation.isPending && !storedUserMissing) status = 'ready'
    else if (validation.isError) status = 'ready' // network flake - proceed optimistically
  }

  return (
    <>
      <Splash visible={status === 'checking'} />
      {status !== 'checking' ? (
        <PhoneFrame>
          {status === 'onboarding' ? <OnboardingScreen /> : user ? <MainShell me={user} /> : null}
          {/* hash-routed user pages (#/u/…) mount beside the shell */}
          <UserRoutePage />
        </PhoneFrame>
      ) : null}
    </>
  )
}

export function AppRoot() {
  return (
    <Providers>
      {/* the active design language rides on <body data-ui=...> so every
          surface - tabs, overlays, portals - speaks one token set */}
      <UiThemeAttr />
      {/* global FX layers - fixed, survive route/tab switches.
          WebGLAmbient renders the prefs-selected shader field ('off' → nothing);
          ParticleLayer suppresses itself while a WebGL mode is active (R26-e contract). */}
      <WebGLAmbient layered />
      <ParticleLayer />
      <BootGate />
    </Providers>
  )
}

// ─────────────────────────────────────────────────────────────
// Pulse Chat — application root & boot gate.
// Waits for store hydration, validates any stored session,
// then renders Onboarding or the main tab shell.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useQuery } from '@tanstack/react-query'
import { MessageCircleHeart } from 'lucide-react'
import type { AppUser } from '@/lib/types'
import { usePulseSession } from '@/lib/pulse-store'
import { ApiError, apiJson } from '@/lib/pulse-utils'
import { Providers } from '@/components/chat/providers'
import { OnboardingScreen } from '@/components/chat/onboarding-screen'
import { MainShell } from '@/components/chat/main-shell'

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
          className="fixed inset-0 z-[60] flex items-center justify-center bg-zinc-100 dark:bg-zinc-950"
          role="status"
          aria-label="Loading Pulse"
        >
          <div className="flex flex-col items-center gap-5">
            <motion.div
              animate={{ scale: [1, 1.08, 1] }}
              transition={{ repeat: Infinity, duration: 1.6, ease: 'easeInOut' }}
              className="flex size-20 items-center justify-center rounded-[1.75rem] bg-gradient-to-br from-emerald-400 to-emerald-600 shadow-lg shadow-emerald-500/30"
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
    <div className="flex min-h-dvh w-full items-stretch justify-center bg-zinc-100 sm:items-center dark:bg-zinc-950">
      <div className="w-full sm:w-[420px] h-dvh sm:h-[860px] sm:max-h-[92vh] sm:rounded-[2.5rem] sm:border sm:border-zinc-200 sm:shadow-2xl overflow-hidden relative flex flex-col bg-white dark:bg-zinc-900 dark:sm:border-zinc-800">
        {children}
      </div>
    </div>
  )
}

function BootGate() {
  const hydrated = usePulseSession((s) => s._hasHydrated)
  const user = usePulseSession((s) => s.user)
  const clear = usePulseSession((s) => s.clear)

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
  if (minDelayDone && hydrated) {
    if (!user) status = 'onboarding'
    else if (!validation.isPending && !storedUserMissing) status = 'ready'
    else if (validation.isError) status = 'ready' // network flake — proceed optimistically
  }

  return (
    <>
      <Splash visible={status === 'checking'} />
      {status !== 'checking' ? (
        <PhoneFrame>
          {status === 'onboarding' ? <OnboardingScreen /> : user ? <MainShell me={user} /> : null}
        </PhoneFrame>
      ) : null}
    </>
  )
}

export function AppRoot() {
  return (
    <Providers>
      <BootGate />
    </Providers>
  )
}

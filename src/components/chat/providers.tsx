// ─────────────────────────────────────────────────────────────
// Pulse Chat — app-wide client providers.
// Order matters: Query → Theme → Realtime(socket+cache bridge).
// ─────────────────────────────────────────────────────────────
'use client'

import { useState, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from 'next-themes'
import { PulseRealtimeProvider } from '@/components/chat/pulse-realtime-provider'
import { PwaProvider } from '@/components/chat/pwa-provider'

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: 1,
            staleTime: 10_000,
            refetchOnWindowFocus: true,
          },
        },
      }),
  )

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false} disableTransitionOnChange>
        <PwaProvider />
        <PulseRealtimeProvider>{children}</PulseRealtimeProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}

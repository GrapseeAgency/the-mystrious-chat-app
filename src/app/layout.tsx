import type { Metadata, Viewport } from 'next'
import { Space_Grotesk, JetBrains_Mono } from 'next/font/google'
import './globals.css'
import { Toaster } from '@/components/ui/toaster'
import { Toaster as SonnerToaster } from '@/components/ui/sonner'

// R35 "Neo" identity - Space Grotesk carries the whole UI (techy,
// geometric, unmistakably not-a-default), JetBrains Mono speaks
// for timestamps, handles, IDs and stat numerals. The legacy
// --font-geist-* variable names are kept so every existing
// font-[family-name:var(--font-geist-sans)] usage keeps working.
const pulseSans = Space_Grotesk({
  variable: '--font-geist-sans',
  subsets: ['latin'],
  display: 'swap',
})

const pulseMono = JetBrains_Mono({
  variable: '--font-geist-mono',
  subsets: ['latin'],
  display: 'swap',
})

export const metadata: Metadata = {
  title: 'Pulse - Chat',
  description: 'Pulse is a real-time messenger: instant delivery, presence, typing indicators, read receipts, groups and more.',
  keywords: ['Pulse', 'chat', 'messenger', 'real-time'],
  manifest: '/manifest.json',
  applicationName: 'Pulse',
  appleWebApp: {
    capable: true,
    title: 'Pulse',
    statusBarStyle: 'default',
  },
  formatDetection: {
    telephone: false,
  },
  icons: {
    icon: [
      { url: '/favicon.png', type: 'image/png', sizes: '64x64' },
      { url: '/pwa-icon-192.png', type: 'image/png', sizes: '192x192' },
    ],
    apple: '/apple-touch-icon.png',
  },
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: 'cover',
  themeColor: '#07090b',
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body
        className={`${pulseSans.variable} ${pulseMono.variable} antialiased bg-background text-foreground`}
      >
        {children}
        <Toaster />
        <SonnerToaster position="top-center" richColors />
      </body>
    </html>
  );
}

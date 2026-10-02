// /api/native/manifest - the native capability contract (GET).
// Native shells (Android Compose, iOS SwiftUI, installed PWA) fetch this
// once per boot and configure their device-native behaviors from server
// truth: haptic patterns, full-screen effect specs + motion curves,
// presence/typing cadence, push capability. Static-by-design values ride
// in code (single source of truth: @/lib/native-bridge), so the response
// is stable and cache-friendly.
import { NextResponse } from 'next/server'
import {
  NATIVE_EFFECT_SPECS,
  NATIVE_HAPTICS,
} from '@/lib/native-bridge'

export const dynamic = 'force-dynamic'

export async function GET() {
  return NextResponse.json(
    {
      manifest: {
        app: 'pulse',
        contract: 1,
        generatedAt: new Date().toISOString(),
        // device-feature surface the backend drives
        capabilities: {
          haptics: {
            supported: true,
            moments: NATIVE_HAPTICS,
          },
          messageEffects: {
            supported: true,
            effects: NATIVE_EFFECT_SPECS,
          },
          realtime: {
            // keep in sync with the socket service + provider cadences
            typingDebounceMs: 2200,
            presenceBroadcast: 'connect',
            unreadRefetchMs: 25000,
          },
          push: {
            webPush: true,
            fcm: true,
            apns: true,
          },
        },
      },
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

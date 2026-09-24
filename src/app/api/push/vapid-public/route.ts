// GET /api/push/vapid-public → { publicKey }
// The VAPID public key web clients pass to PushManager.subscribe().
// Empty string = web push not configured (clients disable the feature honestly).
import { NextResponse } from 'next/server'
import { vapidPublicKey } from '@/lib/push/transport'

export const dynamic = 'force-dynamic'

export async function GET() {
  return NextResponse.json({ publicKey: vapidPublicKey() })
}

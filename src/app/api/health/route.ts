// /api/health - liveness + dependency probe for monitors and native gateways.
// The socket mini-service has one; the gateway had nothing to ping (the
// Android/iOS "Test" buttons hit /api/users, which is heavier than needed).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

export async function GET() {
  let database: 'up' | 'down' = 'down'
  try {
    await db.user.findFirst({ select: { id: true } })
    database = 'up'
  } catch {
    database = 'down'
  }
  return NextResponse.json({
    ok: database === 'up',
    service: 'pulse-gateway',
    database,
    time: new Date().toISOString(),
  })
}

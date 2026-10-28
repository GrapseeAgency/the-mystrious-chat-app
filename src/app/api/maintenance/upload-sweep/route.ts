// /api/maintenance/upload-sweep - reconcile the durable upload store:
// republish disk caches for referenced files, delete unreferenced rows
// past their grace window. Called on an hourly interval by the socket
// mini-service (no DB access over there) and safe to call manually.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { UPLOADS_DIR } from '@/lib/serializers'
import { sweepUploads } from '@/lib/upload-sweep'

export const dynamic = 'force-dynamic'

/** Shared-secret gate (matches the interval caller; audit finding S5). */
function authorized(req: Request): boolean {
  const expected = process.env.CRON_SECRET
  if (!expected) return false
  return req.headers.get('x-pulse-key') === expected
}

/** POST /api/maintenance/upload-sweep → { scanned, removed, republished, retained } */
export async function POST(req: Request) {
  if (!authorized(req)) {
    return NextResponse.json({ error: 'Unauthorized.' }, { status: 401 })
  }
  try {
    const report = await sweepUploads(db, UPLOADS_DIR)
    return NextResponse.json(report)
  } catch (error) {
    console.error('[upload-sweep] failed:', error instanceof Error ? error.message : error)
    return NextResponse.json({ error: 'Upload sweep failed; nothing was removed.' }, { status: 500 })
  }
}

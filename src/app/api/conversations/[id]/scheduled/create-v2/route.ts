import { NextResponse } from 'next/server'
import { safeJson } from '@/lib/serializers'
import { POST as createScheduledRequest } from '../route'

export const dynamic = 'force-dynamic'

/** Separate path prevents old servers silently creating rows while ignoring clientId. */
export async function POST(req: Request, context: { params: Promise<{ id: string }> }) {
  const body = await safeJson(req.clone())
  if (typeof body.clientId !== 'string' || body.clientId.length < 16 || body.clientId.length > 128 || /[^A-Za-z0-9_-]/.test(body.clientId)) {
    return NextResponse.json({ error: 'A valid scheduled-send clientId is required.' }, { status: 400 })
  }
  return createScheduledRequest(req, context)
}

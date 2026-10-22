import { NextResponse } from 'next/server'
import { POST as create } from '../route'
import { safeJson } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** Versioned path: an old gateway must refuse, not ignore the operation ID and
 * create another room. Native clients never downgrade to the legacy POST. */
export async function POST(req: Request) {
  const body = await safeJson(req.clone())
  if (typeof body.clientId !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(body.clientId)) {
    return NextResponse.json({ error: 'A valid creation clientId is required.' }, { status: 400 })
  }
  if (body.isGroup !== true) {
    return NextResponse.json({ error: 'This endpoint requires an explicit group creation.' }, { status: 400 })
  }
  return create(req)
}

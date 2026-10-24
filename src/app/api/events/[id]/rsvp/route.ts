import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { mutateEvent, EventOperationError } from '@/lib/event-operation'

export const dynamic = 'force-dynamic'
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req), userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'events')
    if (denied) return denied
  }
  try { return NextResponse.json(await mutateEvent(db, id, userId, { kind: 'rsvp', status: strField(body.status) })) }
  catch (error) {
    if (error instanceof EventOperationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

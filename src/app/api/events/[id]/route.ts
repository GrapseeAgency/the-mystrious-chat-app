import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { mutateEvent, EventOperationError } from '@/lib/event-operation'

export const dynamic = 'force-dynamic'
export async function DELETE(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const userId = strField(new URL(req.url).searchParams.get('userId'))
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  // Preserve the existing browser session path; presented native credentials
  // must belong to the actor, not merely to some valid account.
  if (req.headers.has('authorization')) {
    const denied = await requireActionOwner(req, userId, 'events')
    if (denied) return denied
  }
  try { return NextResponse.json(await mutateEvent(db, id, userId, { kind: 'delete' })) }
  catch (error) {
    if (error instanceof EventOperationError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

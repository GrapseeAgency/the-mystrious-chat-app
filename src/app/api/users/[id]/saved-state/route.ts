import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { readSavedMessageStates, SavedMessageError } from '@/lib/saved-message-operation'

export const dynamic = 'force-dynamic'
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id: userId } = await params
  const denied = await requireActionOwner(req, userId, 'saved messages')
  if (denied) return denied
  const body = await safeJson(req)
  if (!Array.isArray(body.ids)) return NextResponse.json({ error: 'Message identities are required.' }, { status: 400 })
  try {
    return NextResponse.json({ savedStateVersion: 2, userId, states: await readSavedMessageStates(db, userId, body.ids) },
      { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (error instanceof SavedMessageError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField, notifySocket } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { mutateSavedMessage, mutateSavedIntent, SavedMessageError } from '@/lib/saved-message-operation'

export const dynamic = 'force-dynamic'
export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req), userId = strField(body.userId)
  if (!userId || typeof body.saved !== 'boolean') return NextResponse.json({ error: 'userId and a boolean saved are required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'saved messages')
  if (denied) return denied
  try {
    const versioned = body.clientId !== undefined || body.expectedRevision !== undefined || body.predecessorClientId !== undefined
    const receipt = versioned
      ? await mutateSavedIntent(db, userId, id, body.saved, { clientId: strField(body.clientId), expectedRevision: body.expectedRevision === undefined ? undefined : strField(body.expectedRevision), predecessorClientId: body.predecessorClientId === undefined ? undefined : strField(body.predecessorClientId) })
      : await mutateSavedMessage(db, userId, id, body.saved)
    await notifySocket('saved:changed', [userId], { userId, messageId: id })
    return NextResponse.json(receipt, { headers: { 'Cache-Control': 'private, no-store' } })
  }
  catch (error) {
    if (error instanceof SavedMessageError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

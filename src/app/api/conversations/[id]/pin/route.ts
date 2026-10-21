// /api/conversations/[id]/pin - explicit state, with legacy toggle compatibility
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { ConversationPinError, setConversationPin } from '@/lib/conversation-pin'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/pin  body { userId, pinned?: boolean }
 * Sets the explicit state, or (legacy omission only) toggles the viewer's pinnedAt watermark.
 * → { ok: true, pinned: boolean, pinnedAt: string | null }
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  if (body.pinned !== undefined && typeof body.pinned !== 'boolean') {
    return NextResponse.json({ error: 'pinned must be a boolean.' }, { status: 400 })
  }
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'conversation pin')
    if (denied) return denied
  }
  try {
    return NextResponse.json(await setConversationPin(db, id, userId, body.pinned as boolean | undefined))
  } catch (error) {
    if (error instanceof ConversationPinError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

// /api/conversations/[id]/mark-unread - per-VIEWER unread dot
// R44 - WhatsApp/Telegram "mark as unread": a participant flags a row so it
// re-appears with the unread badge even with nothing new to read. The dot
// clears the next time the room is opened (the read route flips it off).
// Self-service comfort setting - never admin-gated (mirrors R42 screenPrivacy).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { setManualUnread, ReadReceiptError } from '@/lib/read-receipt'
import { safeJson, strField, notifySocket } from '@/lib/serializers'

import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/mark-unread  body { userId, on }
 * on: boolean - true = badge my own row; false = clear it.
 * Participant-only (403 for everyone else). A viewer may only flag their
 * OWN row - userId IS the requester by construction of the call sites.
 * → versioned owner/room/desired-state receipt | 400 | 403 | 404 | 409
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'unread marker')
    if (denied) return denied
  }
  if (typeof body.on !== 'boolean') {
    return NextResponse.json({ error: 'on must be a boolean.' }, { status: 400 })
  }

  try {
    const receipt = await setManualUnread(db, id, userId, body.on)
    await notifySocket('conversation:updated', [userId], { conversationId: id })
    return NextResponse.json(receipt)
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

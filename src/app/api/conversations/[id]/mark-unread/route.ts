import { invalidateNativeReadState } from '@/lib/push/transport'
// /api/conversations/[id]/mark-unread - per-VIEWER unread dot
// R44 - WhatsApp/Telegram "mark as unread": a participant flags a row so it
// re-appears with the unread badge even with nothing new to read. The dot
// clears only under a current prepared read intent, never an unfenced late read.
// Self-service comfort setting - never admin-gated (mirrors R42 screenPrivacy).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { setManualUnread, ReadReceiptError } from '@/lib/read-receipt'
import { safeJson, strField, notifySocket } from '@/lib/serializers'

import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/conversations/[id]/mark-unread  body { userId, on, readIntent }
 * on: boolean - true = badge my own row; false = clear it.
 * Participant-only (403 for everyone else). A viewer may only flag their
 * OWN row - the bearer credential must prove ownership of userId.
 * → versioned owner/room/desired-state receipt | 400 | 403 | 404 | 409
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'unread marker')
  if (denied) return denied
  const readIntent = typeof body.readIntent === 'string' && body.readIntent.length <= 128 ? body.readIntent.trim() : ''
  if (!readIntent) return NextResponse.json({ error: 'Capture a read intent before changing unread state.' }, { status: 400 })
  if (typeof body.on !== 'boolean') {
    return NextResponse.json({ error: 'on must be a boolean.' }, { status: 400 })
  }

  try {
    const receipt = await setManualUnread(db, id, userId, body.on, readIntent)
    await notifySocket('conversation:updated', [userId], { conversationId: id })
    invalidateNativeReadState(userId, id)
    return NextResponse.json(receipt, { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

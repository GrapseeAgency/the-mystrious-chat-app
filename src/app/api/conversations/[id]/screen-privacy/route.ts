// Personal screen security is participant-only and never changes another viewer's preference.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { notifySocket, safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { ConversationMetadataError, patchMyScreenPrivacy } from '@/lib/conversation-metadata'

export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (typeof body.on !== 'boolean')
    return NextResponse.json({ error: 'on must be a boolean.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'personal screen-privacy settings')
  if (denied) return denied
  try {
    await patchMyScreenPrivacy(db, id, userId, body.on)
    await notifySocket('conversation:updated', [userId], {
      type: 'conversation:updated',
      conversationId: id,
      recipientIds: [userId],
    })
    return NextResponse.json({ ok: true, screenPrivacy: body.on })
  } catch (error) {
    if (error instanceof ConversationMetadataError)
      return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

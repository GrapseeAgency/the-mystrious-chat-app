import { invalidateNativeReadState } from '@/lib/push/transport'
// Per-participant archive; authority and mutation share one transaction.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField, notifySocket } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { ConversationPersonalSettingsError, setConversationArchive } from '@/lib/conversation-personal-settings'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'conversation archive')
  if (denied) return denied
  try {
    const receipt = await setConversationArchive(db, id, userId, body.archived)
    invalidateNativeReadState(userId, id)
    await notifySocket('conversation:updated', [userId], { type: 'conversation:updated', conversationId: id, recipientIds: [userId] })
    return NextResponse.json(receipt)
  } catch (error) {
    if (error instanceof ConversationPersonalSettingsError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

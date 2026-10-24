// Group admins configure the member send throttle; send enforcement is in messages POST.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { notifySocket, safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import {
  ConversationMetadataError,
  patchConversationMetadata,
  requirePreset,
  SLOW_MODE_PRESETS,
} from '@/lib/conversation-metadata'

export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'slow-mode settings')
  if (denied) return denied
  try {
    const seconds = body.seconds
    requirePreset(seconds, SLOW_MODE_PRESETS, 'seconds')
    const recipients = await patchConversationMetadata(db, id, userId, { slowModeSeconds: seconds })
    await notifySocket('conversation:updated', recipients, {
      type: 'conversation:updated',
      conversationId: id,
      recipientIds: recipients,
    })
    return NextResponse.json({ ok: true, slowModeSeconds: seconds })
  } catch (error) {
    if (error instanceof ConversationMetadataError)
      return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

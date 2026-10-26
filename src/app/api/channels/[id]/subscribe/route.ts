import { invalidateNativeReadState } from '@/lib/push/transport'
// Channel aliases share group-governance transactions, including last-admin checks.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { GroupMembershipError, joinChannel, leaveGroup } from '@/lib/group-membership'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { notifySocket, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ id: string }>
}
function failure(error: unknown) {
  if (error instanceof GroupMembershipError)
    return NextResponse.json({ error: error.message }, { status: error.status })
  throw error
}
async function notify(id: string, recipients: string[]) {
  await notifySocket('conversation:updated', recipients, {
    type: 'conversation:updated',
    conversationId: id,
    recipientIds: recipients,
  })
}
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req),
    userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'channel membership')
    if (denied) return denied
  }
  try {
    const result = await joinChannel(db, id, userId)
    if (!result.already) {
      invalidateNativeReadState(userId, id)
      await notify(id, result.recipients)
    }
    return NextResponse.json({ already: result.already, memberCount: result.recipients.length })
  } catch (error) {
    return failure(error)
  }
}
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req),
    userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'channel membership')
    if (denied) return denied
  }
  try {
    const result = await leaveGroup(db, id, userId, true)
    invalidateNativeReadState(userId, id)
    await notify(id, [...result.recipients, userId])
    return NextResponse.json({ ok: true, memberCount: result.recipients.length })
  } catch (error) {
    return failure(error)
  }
}

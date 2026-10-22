// Admin-owned invite creation/rotation shares the membership write snapshot.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { groupInvite, GroupMembershipError } from '@/lib/group-membership'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { generateInviteCode, notifySocket, safeJson, strField } from '@/lib/serializers'
export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ id: string }>
}
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req),
    requesterId = strField(body.requesterId)
  if (!requesterId) return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, requesterId, 'group invite')
    if (denied) return denied
  }
  try {
    const result = await groupInvite(
      db,
      id,
      requesterId,
      body.regenerate === true,
      generateInviteCode,
    )
    if (result.changed)
      await notifySocket('conversation:updated', result.recipients, {
        type: 'conversation:updated',
        conversationId: id,
        recipientIds: result.recipients,
      })
    return NextResponse.json({ inviteCode: result.inviteCode })
  } catch (error) {
    if (error instanceof GroupMembershipError)
      return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

// Invite redemption rechecks the code and membership in the write transaction.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { joinGroupInvite, GroupMembershipError } from '@/lib/group-membership'
import { bearerToken } from '@/lib/session-token'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { notifySocket, safeJson, strField } from '@/lib/serializers'
export const dynamic = 'force-dynamic'
interface RouteCtx {
  params: Promise<{ code: string }>
}
export async function POST(req: Request, { params }: RouteCtx) {
  const { code } = await params
  const body = await safeJson(req),
    userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, userId, 'group invite')
    if (denied) return denied
  }
  try {
    const result = await joinGroupInvite(db, code, userId)
    if (!result.alreadyMember)
      await notifySocket('conversation:updated', result.recipients, {
        type: 'conversation:updated',
        conversationId: result.conversationId,
        recipientIds: result.recipients,
      })
    return NextResponse.json({
      conversationId: result.conversationId,
      alreadyMember: result.alreadyMember,
    })
  } catch (error) {
    if (error instanceof GroupMembershipError)
      return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

// POST /api/conversations/[id]/calls/ring - group-call ring fanout.
//
// The starting member's client has ALREADY joined the call over the socket
// (`gcall:join`). This route tells everyone ELSE the call exists:
//   • online members  → `gcall:ring` relayed to their socket room (banner).
//   • offline members → remote push (`fanoutPush`, high priority) so the
//                       notification reaches a closed app.
// Muted members are skipped entirely (mute means no rings, same rule the
// message relay honours). The relay confirms the exact live admission before
// push fanout; clients remain asynchronous and show a failure toast on refusal.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { fanoutPush } from '@/lib/push/transport'
import { safeJson, strField } from '@/lib/serializers'
import { dispatchGroupCallRing, relayGroupCallRing, GroupCallRingError } from '@/lib/group-call-ringing'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { bearerToken } from '@/lib/session-token'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = (await safeJson(req)) as Record<string, unknown>
  const callerId = strField(body.userId)
  const kind = body.kind
  const callId = strField(body.callId)

  if (!callerId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  if (!callId || (kind !== 'voice' && kind !== 'video')) {
    return NextResponse.json({ error: 'An admitted callId and valid kind are required.' }, { status: 400 })
  }
  if (bearerToken(req)) {
    const denied = await requireActionOwner(req, callerId, 'ring call')
    if (denied) return denied
  }

  const conversation = await db.conversation.findUnique({
    where: { id },
    select: { id: true, isGroup: true, name: true, participants: true },
  })
  if (!conversation) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  const caller = await db.user.findUnique({
    where: { id: callerId },
    select: { id: true, name: true, color: true, avatar: true },
  })
  if (!caller) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  const isMember = conversation.participants.some((p) => p.userId === callerId)
  if (!isMember) {
    return NextResponse.json({ error: 'Only members can ring this conversation.' }, { status: 403 })
  }

  const title =
    conversation.isGroup && conversation.name
      ? conversation.name
      : caller.name

  // Apply the same mute snapshot BEFORE either delivery channel. Previously online
  // muted members still received a ringing banner even though their push was skipped.
  try {
    let expiresAt: string | undefined
    const report = await dispatchGroupCallRing(conversation.participants, callerId,
      async recipients => {
        const receipt = await relayGroupCallRing(recipients, {
          conversationId: id, callId, kind,
          caller: { id: caller.id, name: caller.name, color: caller.color, avatar: caller.avatar },
          title,
        })
        expiresAt = receipt.expiresAt
        return receipt.accepted
      },
      recipients => fanoutPush(recipients, {
        kind: 'gcall', title,
        body: `${caller.name} started a ${kind === 'video' ? 'video' : 'voice'} call`,
        conversationId: id, callId, callKind: kind, callerName: caller.name, expiresAt,
      }),
    )
    return NextResponse.json({ ok: true, ...report })
  } catch (error) {
    if (error instanceof GroupCallRingError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

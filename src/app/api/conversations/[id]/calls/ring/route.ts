// POST /api/conversations/[id]/calls/ring - group-call ring fanout.
//
// The starting member's client has ALREADY joined the call over the socket
// (`gcall:join`). This route tells everyone ELSE the call exists:
//   • online members  → `gcall:ring` relayed to their socket room (banner).
//   • offline members → remote push (`fanoutPush`, high priority) so the
//                       notification reaches a closed app.
// Muted members are skipped entirely (mute means no rings, same rule the
// message relay honours). Ring is fire-and-forget for the caller UX: the
// response only confirms validation, never blocks on delivery.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { fanoutPush } from '@/lib/push/transport'
import { notifySocket, safeJson, strField } from '@/lib/serializers'
import { dispatchGroupCallRing } from '@/lib/group-call-ringing'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = (await safeJson(req)) as Record<string, unknown>
  const callerId = strField(body.userId)
  const kind = body.kind === 'video' ? 'video' : 'voice'

  if (!callerId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
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
  const report = await dispatchGroupCallRing(conversation.participants, callerId,
    recipients => notifySocket('gcall:ring', recipients, {
      type: 'gcall:ring', conversationId: id, kind,
      caller: { id: caller.id, name: caller.name, color: caller.color, avatar: caller.avatar },
      title,
    }),
    recipients => fanoutPush(recipients, {
      kind: 'gcall', title,
      body: `${caller.name} started a ${kind === 'video' ? 'video' : 'voice'} call`,
      conversationId: id, callKind: kind, callerName: caller.name,
    }),
  )
  return NextResponse.json({ ok: true, ...report })
}

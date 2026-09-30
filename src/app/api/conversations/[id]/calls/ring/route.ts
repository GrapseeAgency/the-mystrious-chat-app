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

  const memberIds = conversation.participants.map((p) => p.userId)
  const recipients = memberIds.filter((memberId) => memberId !== callerId)
  if (recipients.length === 0) {
    return NextResponse.json({ ok: true, rang: 0, pushed: 0 })
  }

  const title =
    conversation.isGroup && conversation.name
      ? conversation.name
      : caller.name

  // 1. Realtime ring to everyone still connected.
  await notifySocket('gcall:ring', recipients, {
    type: 'gcall:ring',
    conversationId: id,
    kind,
    caller: { id: caller.id, name: caller.name, color: caller.color, avatar: caller.avatar },
    title,
  })

  // 2. High-priority push for members whose apps are closed (transport
  //    skips currently-online users itself; muted members opted out here -
  //    same watermark semantics as message notifications: mutedUntil > now).
  const now = new Date()
  const muted = await db.conversationParticipant.findMany({
    where: { conversationId: id, mutedUntil: { gt: now } },
    select: { userId: true },
  })
  const mutedIds = new Set(muted.map((row) => row.userId))
  const pushTargets = recipients.filter((memberId) => !mutedIds.has(memberId))
  const report = await fanoutPush(pushTargets, {
    kind: 'gcall',
    title,
    body: `${caller.name} started a ${kind === 'video' ? 'video' : 'voice'} call`,
    conversationId: id,
    callKind: kind,
    callerName: caller.name,
  })

  return NextResponse.json({ ok: true, rang: recipients.length, pushed: report.sent })
}

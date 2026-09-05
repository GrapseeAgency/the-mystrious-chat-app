// ─────────────────────────────────────────────────────────────
// /api/topics/[id] — delete a Zulip-style topic (R24-b)
//
// Contract:
//   DELETE ?userId= (or JSON body { userId })
//     → 404 unknown topic
//     → 403 when the requester is not a participant, or is a plain
//       member who is neither the topic creator nor a group admin
//     → 200 { ok: true } — the topic row is hard-deleted and every
//       filed message drops back to General automatically (the
//       Message.topic relation is onDelete: SetNull).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  // userId may arrive as a query param or a JSON body (DELETE semantics).
  const url = new URL(req.url)
  let userId = strField(url.searchParams.get('userId'))
  if (!userId) {
    const body = await safeJson(req)
    userId = strField(body.userId)
  }
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const topic = await db.topic.findUnique({
    where: { id },
    select: { id: true, conversationId: true, createdById: true },
  })
  if (!topic) {
    return NextResponse.json({ error: 'Topic not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: topic.conversationId } },
    select: { role: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const isCreator = topic.createdById !== null && topic.createdById === userId
  const isAdmin = participant.role === 'admin'
  if (!isCreator && !isAdmin) {
    return NextResponse.json(
      { error: 'Only the topic creator or a group admin can delete this topic.' },
      { status: 403 },
    )
  }

  await db.topic.delete({ where: { id } })

  return NextResponse.json({ ok: true })
}

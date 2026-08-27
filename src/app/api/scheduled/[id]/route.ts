// ─────────────────────────────────────────────────────────────
// /api/scheduled/[id] — cancel a pending delayed-send (owner only)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** DELETE /api/scheduled/[id]  body { requesterId } → { ok: true } */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const row = await db.scheduledMessage.findUnique({ where: { id } })
  if (!row) {
    return NextResponse.json({ error: 'Scheduled message not found.' }, { status: 404 })
  }
  if (row.senderId !== requesterId) {
    return NextResponse.json({ error: 'Only the sender can cancel this message.' }, { status: 403 })
  }
  if (row.sentAt !== null) {
    return NextResponse.json({ error: 'This message was already sent.' }, { status: 400 })
  }

  await db.scheduledMessage.delete({ where: { id } })
  return NextResponse.json({ ok: true })
}

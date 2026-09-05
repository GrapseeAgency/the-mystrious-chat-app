// ─────────────────────────────────────────────────────────────
// /api/reminders/[id] — resolve or cancel one of YOUR reminders.
// PATCH marks the row fired (the client due-loop calls this after
// showing the nudge); DELETE cancels it outright. Owner-only, both.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * PATCH /api/reminders/[id]  body { userId } → { ok: true, firedAt }
 * Idempotent: an already-fired row returns ok without touching firedAt.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const row = await db.reminder.findUnique({ where: { id } })
  if (!row) {
    return NextResponse.json({ error: 'Reminder not found.' }, { status: 404 })
  }
  if (row.userId !== userId) {
    return NextResponse.json({ error: 'Only the owner can resolve this reminder.' }, { status: 403 })
  }

  if (row.firedAt !== null) {
    return NextResponse.json({ ok: true, firedAt: row.firedAt.toISOString() })
  }

  const firedAt = new Date()
  await db.reminder.update({ where: { id }, data: { firedAt } })
  return NextResponse.json({ ok: true, firedAt: firedAt.toISOString() })
}

/** DELETE /api/reminders/[id]  body { userId } → { ok: true } */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const row = await db.reminder.findUnique({ where: { id } })
  if (!row) {
    return NextResponse.json({ error: 'Reminder not found.' }, { status: 404 })
  }
  if (row.userId !== userId) {
    return NextResponse.json({ error: 'Only the owner can cancel this reminder.' }, { status: 403 })
  }

  await db.reminder.delete({ where: { id } })
  return NextResponse.json({ ok: true })
}

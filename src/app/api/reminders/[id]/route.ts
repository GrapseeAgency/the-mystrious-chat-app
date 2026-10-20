// /api/reminders/[id] - resolve or cancel one of YOUR reminders.
// PATCH marks the row fired (the client due-loop calls this after
// showing the nudge); DELETE cancels it outright. Owner-only, both.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { resolveReminder, deleteReminder, ReminderContractError } from '@/lib/reminder-contract'
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

  try { return NextResponse.json(await resolveReminder(db, id, userId)) }
  catch (error) {
    if (error instanceof ReminderContractError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

/** DELETE /api/reminders/[id]  body { userId } → { ok: true } */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  try { return NextResponse.json(await deleteReminder(db, id, userId)) }
  catch (error) {
    if (error instanceof ReminderContractError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

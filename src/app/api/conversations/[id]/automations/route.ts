// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/automations — R39 keyword auto-replies.
// GET  ?userId= (participant-only) → { automations: AutomationSummary[] }
//      rows ordered createdAt desc, creator {id,name,color,avatar} included.
// POST { userId, trigger, reply } — conversation ADMINS only (mirrors the
//      webhook/members admin gating). Validation: trigger 2-40 chars after
//      trim, reply 1-500 chars; same trigger (case-insensitive) in the same
//      conversation → 409.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapAutomation, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const AUTOMATION_TRIGGER_MIN = 2
const AUTOMATION_TRIGGER_MAX = 40
const AUTOMATION_REPLY_MAX = 500

const AUTOMATION_INCLUDE = {
  createdBy: { select: { id: true, name: true, color: true, avatar: true } },
} as const

/** GET — participant-only listing of this conversation's automation rules. */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const userId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const conv = await db.conversation.findUnique({ where: { id }, select: { id: true } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  const rows = await db.automation.findMany({
    where: { conversationId: id },
    orderBy: { createdAt: 'desc' },
    include: AUTOMATION_INCLUDE,
  })
  return NextResponse.json({ automations: rows.map(mapAutomation) })
}

/**
 * POST — create an automation rule. Admin-only (403 otherwise, honest error),
 * duplicate triggers (case-insensitive, per conversation) → 409.
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const trigger = strField(body.trigger)
  if (trigger.length < AUTOMATION_TRIGGER_MIN || trigger.length > AUTOMATION_TRIGGER_MAX) {
    return NextResponse.json(
      {
        error: `trigger must be ${AUTOMATION_TRIGGER_MIN}-${AUTOMATION_TRIGGER_MAX} characters.`,
      },
      { status: 400 },
    )
  }
  const reply = strField(body.reply)
  if (reply.length < 1 || reply.length > AUTOMATION_REPLY_MAX) {
    return NextResponse.json(
      { error: `reply must be 1-${AUTOMATION_REPLY_MAX} characters.` },
      { status: 400 },
    )
  }

  const conv = await db.conversation.findUnique({ where: { id }, select: { id: true } })
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: id } },
    select: { role: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }
  if (participant.role !== 'admin') {
    return NextResponse.json(
      { error: 'Only admins can create automations.' },
      { status: 403 },
    )
  }

  // Dedupe: same trigger (case-insensitive) already exists in THIS conversation.
  // SQLite has no case-insensitive collation here, so normalize in Node.
  const existing = await db.automation.findMany({
    where: { conversationId: id },
    select: { id: true, trigger: true },
  })
  if (existing.some((row) => row.trigger.toLowerCase() === trigger.toLowerCase())) {
    return NextResponse.json(
      { error: 'An automation with this trigger already exists in this conversation.' },
      { status: 409 },
    )
  }

  const created = await db.automation.create({
    data: { conversationId: id, trigger, reply, createdById: userId },
    include: AUTOMATION_INCLUDE,
  })
  return NextResponse.json({ automation: mapAutomation(created) }, { status: 201 })
}

// ─────────────────────────────────────────────────────────────
// /api/automations/[id] — R39 manage one keyword auto-reply rule.
// PATCH  { userId, enabled?, reply?, trigger? } — admin-only (creator counts
//        only when they are an admin of the rule's conversation). R41: trigger
//        is renamable after create — same validation as create (2-40 chars
//        trimmed, case-insensitive per-conversation dedupe EXCLUDING this rule
//        → 409). 400 nothing to update / bad types · 403 non-admin · 404
//        unknown rule.
// DELETE ?userId= (or JSON body) — admin-only, removes the rule.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mapAutomation, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

// Mirrors the create route's validation constants (kept local so route
// modules never import from each other).
const AUTOMATION_TRIGGER_MIN = 2
const AUTOMATION_TRIGGER_MAX = 40
const AUTOMATION_REPLY_MAX = 500

/**
 * Shared gate: the automation must exist and the requester must be an ADMIN
 * of the rule's conversation. Being the creator alone is not enough — the
 * creator counts only while they still hold the admin role.
 */
async function requireAdmin(userId: string, automationId: string) {
  const automation = await db.automation.findUnique({
    where: { id: automationId },
    select: { id: true, conversationId: true },
  })
  if (!automation) {
    return {
      error: NextResponse.json({ error: 'Automation not found.' }, { status: 404 }),
    }
  }
  const participant = await db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId, conversationId: automation.conversationId } },
    select: { role: true },
  })
  if (!participant) {
    return {
      error: NextResponse.json(
        { error: 'You are not a participant of this conversation.' },
        { status: 403 },
      ),
    }
  }
  if (participant.role !== 'admin') {
    return {
      error: NextResponse.json({ error: 'Only admins can manage automations.' }, { status: 403 }),
    }
  }
  return { automation }
}

/** PATCH — flip enabled, rewrite the reply and/or rename the trigger (R41). */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const hasEnabled = typeof body.enabled === 'boolean'
  const hasReply = body.reply !== undefined
  const hasTrigger = body.trigger !== undefined
  if (!hasEnabled && !hasReply && !hasTrigger) {
    return NextResponse.json(
      { error: 'Nothing to update — provide enabled, reply and/or trigger.' },
      { status: 400 },
    )
  }
  let reply: string | undefined
  if (hasReply) {
    reply = strField(body.reply)
    if (reply.length < 1 || reply.length > AUTOMATION_REPLY_MAX) {
      return NextResponse.json(
        { error: `reply must be 1-${AUTOMATION_REPLY_MAX} characters.` },
        { status: 400 },
      )
    }
  }
  // R41 — trigger rename: same validation as create (2-40 chars after trim;
  // a non-string fails the length check exactly like create does).
  let trigger: string | undefined
  if (hasTrigger) {
    trigger = strField(body.trigger)
    if (trigger.length < AUTOMATION_TRIGGER_MIN || trigger.length > AUTOMATION_TRIGGER_MAX) {
      return NextResponse.json(
        {
          error: `trigger must be ${AUTOMATION_TRIGGER_MIN}-${AUTOMATION_TRIGGER_MAX} characters.`,
        },
        { status: 400 },
      )
    }
  }

  const gate = await requireAdmin(userId, id)
  if ('error' in gate) return gate.error

  // Dedupe (R41): the new trigger must be unique per conversation,
  // case-insensitively, EXCLUDING the rule being renamed.
  if (trigger !== undefined) {
    const siblings = await db.automation.findMany({
      where: { conversationId: gate.automation.conversationId, id: { not: id } },
      select: { id: true, trigger: true },
    })
    if (siblings.some((row) => row.trigger.toLowerCase() === trigger.toLowerCase())) {
      return NextResponse.json(
        { error: 'An automation with this trigger already exists in this conversation.' },
        { status: 409 },
      )
    }
  }

  const updated = await db.automation.update({
    where: { id },
    data: {
      ...(hasEnabled ? { enabled: body.enabled as boolean } : {}),
      ...(reply !== undefined ? { reply } : {}),
      ...(trigger !== undefined ? { trigger } : {}),
    },
    include: { createdBy: { select: { id: true, name: true, color: true, avatar: true } } },
  })
  return NextResponse.json({ automation: mapAutomation(updated) })
}

/** DELETE — remove the rule (idempotent 404 when already gone). */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params

  let userId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    const body = await safeJson(req)
    userId = strField(body.userId)
  }
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const gate = await requireAdmin(userId, id)
  if ('error' in gate) return gate.error

  await db.automation.delete({ where: { id } })
  return NextResponse.json({ ok: true })
}

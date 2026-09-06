// ─────────────────────────────────────────────────────────────
// /api/automations/[id] — R39 manage one keyword auto-reply rule.
// PATCH  { userId, enabled?, reply? } — admin-only (creator counts only
//        when they are an admin of the rule's conversation). 400 nothing
//        to update / bad types · 403 non-admin · 404 unknown rule.
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

/** PATCH — flip enabled and/or rewrite the reply. */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const hasEnabled = typeof body.enabled === 'boolean'
  const hasReply = body.reply !== undefined
  if (!hasEnabled && !hasReply) {
    return NextResponse.json(
      { error: 'Nothing to update — provide enabled and/or reply.' },
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

  const gate = await requireAdmin(userId, id)
  if ('error' in gate) return gate.error

  const updated = await db.automation.update({
    where: { id },
    data: {
      ...(hasEnabled ? { enabled: body.enabled as boolean } : {}),
      ...(reply !== undefined ? { reply } : {}),
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

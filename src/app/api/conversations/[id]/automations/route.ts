// /api/conversations/[id]/automations - R39 keyword auto-replies.
// GET  ?userId=&limit=&cursor= (participant-only) →
//      { automations: AutomationSummary[], revision, page: { nextCursor, hasMore } }
//      (createdAt, id) DESC keyset; without cursor the first `limit` rows
//      come back (default 100 = the per-room cap, so legacy single-call
//      clients still see a full legal list). `revision` is the conversation's
//      monotonic integration revision (Section 12 item 2).
// POST { userId, trigger, reply, clientId? } - conversation ADMINS only.
//      Validation: trigger 2-40 chars after trim, reply 1-500 chars;
//      same trigger (case-insensitive) in the same conversation → 409.
//      With clientId the create carries a durable operation identity: a
//      lost-ACK retry replays the SAME rule (replayed:true) and a reused
//      clientId with different instructions is a 409 (Section 12 item 1).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { roomControl } from '@/lib/room-control'
import { mapAutomation, safeJson, strField } from '@/lib/serializers'
import {
  AUTOMATIONS_PER_ROOM_MAX,
  bumpIntegrationRevision,
  probeIntegrationOp,
  settleIntegrationOp,
} from '@/lib/integration-contracts.ts'
import {
  clientIdFrom,
  decodeKeysetCursor,
  keysetBefore,
  pageLimit,
  pageVerdict,
} from '@/lib/section10-contracts.ts'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const AUTOMATION_TRIGGER_MIN = 2
const AUTOMATION_TRIGGER_MAX = 40
const AUTOMATION_REPLY_MAX = 500
const AUTOMATION_PAGE_DEFAULT = 100
const AUTOMATION_PAGE_MAX = 200

const AUTOMATION_INCLUDE = {
  createdBy: { select: { id: true, name: true, color: true, avatar: true } },
} as const

/** GET - participant-only keyset page of this conversation's automation rules. */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  const refusal = await requireActionOwner(req, userId, 'automation')
  if (refusal) return refusal
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }
  const limit = pageLimit(url.searchParams.get('limit'), AUTOMATION_PAGE_MAX)
  if (!limit.ok) {
    return NextResponse.json({ error: `limit must be an integer 1-${AUTOMATION_PAGE_MAX}.` }, { status: 400 })
  }
  const cursor = decodeKeysetCursor(url.searchParams.get('cursor'))
  if (!cursor.ok) {
    return NextResponse.json({ error: 'cursor must be a `<epochMs>.<id>` keyset cursor.' }, { status: 400 })
  }

  return roomControl(db, async tx => {
    const conv = await tx.conversation.findUnique({ where: { id }, select: { id: true } })
    if (!conv) {
      return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
    }

    const participant = await tx.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
      select: { id: true },
    })
    if (!participant) {
      return NextResponse.json(
        { error: 'You are not a participant of this conversation.' },
        { status: 403 },
      )
    }

    const cap = limit.limit ?? AUTOMATION_PAGE_DEFAULT
    const rows = await tx.automation.findMany({
      where: { conversationId: id, ...(cursor.absent ? {} : keysetBefore(cursor.atMs, cursor.id)) },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: cap + 1,
      include: AUTOMATION_INCLUDE,
    })
    const verdict = pageVerdict(rows, cap, row => ({ atMs: row.createdAt.getTime(), id: row.id }))
    const revision = await tx.integrationRevision.findUnique({
      where: { conversationId: id }, select: { rev: true },
    })
    return NextResponse.json({
      automations: verdict.rows.map(mapAutomation),
      revision: revision?.rev ?? 0,
      page: { nextCursor: verdict.nextCursor, hasMore: verdict.hasMore },
    })
  })
}

/**
 * POST - create an automation rule. Admin-only (403 otherwise, honest error),
 * duplicate triggers (case-insensitive, per conversation) → 409, per-room
 * rule cap → 409. With clientId: durable create receipt (item 1).
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  const refusal = await requireActionOwner(req, userId, 'automation')
  if (refusal) return refusal
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const clientId = clientIdFrom(body)
  if (!clientId.ok) {
    return NextResponse.json({ error: 'clientId must be 16-128 url-safe characters.' }, { status: 400 })
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

  return roomControl(db, async tx => {
    const conv = await tx.conversation.findUnique({ where: { id }, select: { id: true } })
    if (!conv) {
      return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
    }

    const participant = await tx.conversationParticipant.findUnique({
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

    // Lost-ACK replay FIRST: a retried create must replay its stored rule
    // even though the trigger dedupe below would now refuse it as a duplicate.
    if (clientId.clientId) {
      const args = { kind: 'automation.create' as const, clientId: clientId.clientId, conversationId: id, userId, identity: [id, userId, trigger, reply] }
      const probe = await probeIntegrationOp<{ automation: ReturnType<typeof mapAutomation> }>(tx, args)
      if (probe.status === 'mismatch') {
        return NextResponse.json(
          { error: 'clientId already used for a different integration operation.' },
          { status: 409 },
        )
      }
      if (probe.status === 'replay') {
        return NextResponse.json({ ...probe.result, replayed: true }, { status: 201 })
      }
    }

    // Item 5: bounded rule scans start at admission - a room past the cap
    // cannot grow its evaluation cost further.
    const ruleCount = await tx.automation.count({ where: { conversationId: id } })
    if (ruleCount >= AUTOMATIONS_PER_ROOM_MAX) {
      return NextResponse.json(
        { error: `This conversation already holds the maximum of ${AUTOMATIONS_PER_ROOM_MAX} automations.` },
        { status: 409 },
      )
    }

    // Dedupe: same trigger (case-insensitive) already exists in THIS conversation.
    // SQLite has no case-insensitive collation here, so normalize in Node.
    const existing = await tx.automation.findMany({
      where: { conversationId: id },
      select: { id: true, trigger: true },
    })
    if (existing.some((row) => row.trigger.toLowerCase() === trigger.toLowerCase())) {
      return NextResponse.json(
        { error: 'An automation with this trigger already exists in this conversation.' },
        { status: 409 },
      )
    }

    const created = await tx.automation.create({
      data: { conversationId: id, trigger, reply, createdById: userId },
      include: AUTOMATION_INCLUDE,
    })
    const revision = await bumpIntegrationRevision(tx, id)
    const payload = { automation: mapAutomation(created), revision }
    if (clientId.clientId) {
      const settled = await settleIntegrationOp(
        tx,
        { kind: 'automation.create', clientId: clientId.clientId, conversationId: id, userId, identity: [id, userId, trigger, reply] },
        created.id,
        payload,
      )
      return NextResponse.json({ ...settled.result, replayed: settled.replayed }, { status: 201 })
    }
    return NextResponse.json(payload, { status: 201 })
  })
}

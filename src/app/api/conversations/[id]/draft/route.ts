// /api/conversations/[id]/draft - server-synced composer draft
// R45 - Telegram-style cross-device drafts: a participant's half-typed
// message is persisted on THEIR participant row so it restores from any
// device/tab and surfaces as a "Draft: …" preview in the chats list even
// where no local draft exists. Empty string clears. The client debounces
// writes (~600ms) and mirrors every local store mutation through this
// route, so all existing clear/save paths sync with zero call-site churn.
// Self-service - a viewer may only write their OWN row (userId IS the
// requester only after checking the existing session credential).
import { NextResponse } from 'next/server'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Same cap as the composer + localStorage draft store. */
const MAX_DRAFT = 2000

/**
 * GET /api/conversations/[id]/draft?userId=… → { draft: string | null }
 * Spec row F-CL-10 lists GET+PATCH; the GET serves a direct draft read for
 * clients that open a room without first fetching the full summary/detail
 * (the summary's `myDraft` stays the bulk path - same column, same shape).
 * Self-service: `userId` must be the requester AND a participant of the
 * conversation - the exact guard PATCH enforces. null = no draft stored.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'draft')
  if (denied) return denied

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: id } },
      select: { id: true, draft: true },
    }),
  ])
  if (!conv) {
    return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  }
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  return NextResponse.json({ draft: participant.draft ?? null }, { headers: { 'Cache-Control': 'private, no-store' } })
}

/**
 * PATCH /api/conversations/[id]/draft  body { userId, draft }
 * draft: string ≤ 2000 chars ('' = clear).
 * → { ok: true, draft: string } | 400 | 403 | 404
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'draft')
  if (denied) return denied
  if (typeof body.draft !== 'string') {
    return NextResponse.json({ error: 'draft must be a string.' }, { status: 400 })
  }
  if (body.draft.length > MAX_DRAFT) {
    return NextResponse.json({ error: `Draft must be ${MAX_DRAFT} UTF-16 units or fewer. The saved draft was not changed.` }, { status: 400 })
  }
  const draft = body.draft

  try {
    return await db.$transaction(async tx => {
      if (!await tx.conversation.findUnique({ where: { id }, select: { id: true } })) {
        return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
      }
      const participant = await tx.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId, conversationId: id } }, select: { id: true, draft: true, draftRevision: true },
      })
      if (!participant) return NextResponse.json({ error: 'You are not a participant of this conversation.' }, { status: 403 })
      const saved = await tx.conversationParticipant.update({
        where: { id: participant.id }, data: { draft: draft.length > 0 ? draft : null, draftRevision: (BigInt(participant.draftRevision === "0" && participant.draft ? "1" : participant.draftRevision) + BigInt(1)).toString() }, select: { draft: true },
      })
      return NextResponse.json({ ok: true, draftReceiptVersion: 1, conversationId: id, userId, draft: saved.draft ?? '' },
        { headers: { 'Cache-Control': 'private, no-store' } })
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      return NextResponse.json({ error: 'Draft storage is busy. Your draft was not confirmed.' }, { status: 409 })
    }
    throw error
  }
}

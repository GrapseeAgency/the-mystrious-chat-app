// ─────────────────────────────────────────────────────────────
// /api/calls — 1:1 call history (R33-a).
//
// GET  ?userId=X → the viewer's call log, newest first, cap 50.
//      Rows where the viewer was the caller OR the callee are merged;
//      each row resolves the PEER (the other party) with name/username/
//      color/avatar + an `outgoing` flag, mirroring how reminders resolve
//      conversation labels.
//
// POST { userId, conversationId, peerId, kind, status, durationSec? }
//      → 201 { item }. SINGLE-WRITER RULE: the CALLER's client writes every
//      terminal row (completed / missed / declined) exactly once — the callee
//      never writes, so no double rows are possible. The viewer is therefore
//      always the caller (callerId = userId, calleeId = peerId).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { isCallKind, isCallStatus, type CallLogItem, type CallPeerInfo } from '@/lib/call-types'

export const dynamic = 'force-dynamic'

const HISTORY_CAP = 50
const MAX_DURATION_SEC = 24 * 3600

function asDurationSec(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  const sec = Math.max(0, Math.floor(value))
  return sec <= MAX_DURATION_SEC ? sec : null
}

/** Resolve the other party of a 1:1 conversation (id/name/username/color/avatar). */
async function resolvePeer(peerId: string): Promise<CallPeerInfo | null> {
  const user = await db.user.findUnique({
    where: { id: peerId },
    select: { id: true, name: true, username: true, color: true, avatar: true },
  })
  if (!user) return null
  return {
    id: user.id,
    name: user.name,
    username: user.username,
    color: user.color,
    avatar: user.avatar,
  }
}

function toItem(
  row: {
    id: string
    conversationId: string
    callerId: string
    calleeId: string
    kind: string
    status: string
    durationSec: number
    startedAt: Date
  },
  viewerId: string,
  peer: CallPeerInfo | null,
): CallLogItem {
  return {
    id: row.id,
    conversationId: row.conversationId,
    callerId: row.callerId,
    calleeId: row.calleeId,
    kind: isCallKind(row.kind) ? row.kind : 'voice',
    status: isCallStatus(row.status) ? row.status : 'missed',
    durationSec: row.durationSec,
    startedAt: row.startedAt.toISOString(),
    outgoing: row.callerId === viewerId,
    peer: peer ?? { id: '', name: 'Unknown', username: null, color: 'emerald', avatar: null },
  }
}

/**
 * GET /api/calls?userId=X → { items: CallLogItem[] }
 * Newest-first merge of the viewer's caller and callee rows, capped at 50
 * (indexes @@index([callerId, startedAt]) + @@index([calleeId, startedAt])).
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')
  if (!userId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const rows = await db.callLog.findMany({
    where: { OR: [{ callerId: userId }, { calleeId: userId }] },
    orderBy: { startedAt: 'desc' },
    take: HISTORY_CAP,
  })

  const items = await Promise.all(
    rows.map(async (row) => {
      const peerId = row.callerId === userId ? row.calleeId : row.callerId
      const peer = await resolvePeer(peerId)
      return toItem(row, userId, peer)
    }),
  )

  return NextResponse.json({ items })
}

/**
 * POST /api/calls { userId, conversationId, peerId, kind, status, durationSec? }
 * → 201 { item: CallLogItem }. Only a DM both parties participate in can be
 * logged; status must be terminal; nobody logs a call against themselves.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const conversationId = strField(body.conversationId)
  const peerId = strField(body.peerId)
  const kind = body.kind
  const status = body.status
  const durationSec = asDurationSec(body.durationSec ?? 0)

  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  if (!conversationId) {
    return NextResponse.json({ error: 'conversationId is required.' }, { status: 400 })
  }
  if (!peerId) return NextResponse.json({ error: 'peerId is required.' }, { status: 400 })
  if (peerId === userId) {
    return NextResponse.json({ error: 'A call needs two different people.' }, { status: 400 })
  }
  if (!isCallKind(kind)) {
    return NextResponse.json({ error: 'kind must be "voice" or "video".' }, { status: 400 })
  }
  if (!isCallStatus(status)) {
    return NextResponse.json(
      { error: 'status must be "completed", "missed" or "declined".' },
      { status: 400 },
    )
  }

  // The conversation must be a DM the viewer participates in.
  const conversation = await db.conversation.findUnique({
    where: { id: conversationId },
    select: {
      id: true,
      isGroup: true,
      isSelf: true,
      participants: { select: { userId: true } },
    },
  })
  if (!conversation || conversation.isGroup) {
    return NextResponse.json({ error: 'Calls are only logged for direct messages.' }, { status: 400 })
  }
  const participantIds = new Set(conversation.participants.map((p) => p.userId))
  if (!participantIds.has(userId)) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }
  if (!participantIds.has(peerId)) {
    return NextResponse.json(
      { error: 'The peer is not a participant of this conversation.' },
      { status: 400 },
    )
  }

  const row = await db.callLog.create({
    data: {
      conversationId,
      callerId: userId,
      calleeId: peerId,
      kind,
      status,
      durationSec: durationSec ?? 0,
    },
  })
  const peer = await resolvePeer(peerId)

  return NextResponse.json({ item: toItem(row, userId, peer) }, { status: 201 })
}

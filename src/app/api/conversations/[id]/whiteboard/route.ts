// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/whiteboard — collaborative canvas sync
// (Task R21-c "Beyond Chat" wave — Zoom/Miro-style shared board)
//
// Every stroke is a REAL persisted WhiteboardStroke row so the
// canvas survives close/reopen and syncs across members by
// timestamp polling (delta fetch with an overlap window + id
// dedupe on the client).
//
// Contracts:
//   GET    ?requesterId=&since=<ISO|epoch ms>
//          → { strokes: [{id,userId,color,width,points:number[][],createdAt}],
//              serverTime: epochMs, resetAt: epochMs|null }
//          `since` omitted → full snapshot (fresh joiners).
//          `resetAt` = latest "whiteboard-clear" LogEvent marker —
//          clients wipe their canvas whenever it changes.
//   POST   body { requesterId, strokes: [{color,width,points}] }
//          → 201 { ids: string[], serverTime, created: n }   (batch 1..40)
//   POST   body { action: 'undo', requesterId }
//          → 200 { success: true, removedId: string|null, serverTime }
//          (deletes the caller's most recent stroke only)
//   DELETE ?requesterId=
//          → 200 { success: true, reset: true, at: epochMs }
//          (wipes every stroke + writes the reset marker LogEvent)
//
// All handlers verify conversation participation — no anonymous
// board access, no mocks, everything lands in Prisma/SQLite.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

// ── validation bounds (mirrored by the client-side canvas) ────
const STROKES_MAX_PER_CALL = 40
const POINTS_MIN = 2
const POINTS_MAX = 500
const WIDTH_MIN = 0.5
const WIDTH_MAX = 40
const COLOR_MAX = 32

/** 403/404 response when the requester is not a conversation member, else null. */
async function participantGuard(
  conversationId: string,
  requesterId: string,
): Promise<NextResponse | null> {
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: requesterId, conversationId } },
      select: { id: true },
    }),
  ])
  if (!conv) return NextResponse.json({ error: 'Conversation not found.' }, { status: 404 })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }
  return null
}

/** Parse the `since` query param — accepts ISO strings or epoch milliseconds. */
function parseSince(raw: string | null): { ok: Date | null } | { error: string } {
  if (raw === null || raw.trim() === '') return { ok: null }
  const trimmed = raw.trim()
  if (/^-?\d+$/.test(trimmed)) {
    const date = new Date(Number(trimmed))
    if (Number.isNaN(date.getTime())) return { error: 'since is not a valid epoch timestamp.' }
    return { ok: date }
  }
  const parsed = Date.parse(trimmed)
  if (Number.isNaN(parsed)) return { error: 'since must be an ISO date or epoch milliseconds.' }
  return { ok: new Date(parsed) }
}

/** Latest whiteboard-clear marker (epoch ms) for this conversation, or null. */
async function latestResetAt(conversationId: string): Promise<number | null> {
  const marker = await db.logEvent.findFirst({
    where: { kind: 'whiteboard-clear', message: conversationId },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    select: { createdAt: true },
  })
  return marker ? marker.createdAt.getTime() : null
}

/**
 * Validate + normalize one incoming stroke payload. Returns the ready-to-insert
 * row or an error string. Points are clamped into normalized 0..1 coords.
 */
function normalizeStroke(
  raw: unknown,
): { ok: { color: string; width: number; points: string } } | { error: string } {
  if (typeof raw !== 'object' || raw === null) return { error: 'each stroke must be an object.' }
  const s = raw as Record<string, unknown>

  const color = strField(s.color).slice(0, COLOR_MAX)
  if (color.length === 0) return { error: 'each stroke needs a color.' }

  const widthRaw = typeof s.width === 'number' ? s.width : Number(s.width)
  if (!Number.isFinite(widthRaw)) return { error: 'each stroke needs a numeric width.' }
  const width = Math.min(Math.max(widthRaw, WIDTH_MIN), WIDTH_MAX)

  if (!Array.isArray(s.points)) return { error: 'each stroke needs a points array.' }
  if (s.points.length < POINTS_MIN || s.points.length > POINTS_MAX) {
    return { error: `each stroke needs ${POINTS_MIN}-${POINTS_MAX} points.` }
  }
  const pts: Array<[number, number]> = []
  for (const p of s.points) {
    if (!Array.isArray(p) || p.length < 2) return { error: 'each point must be an [x, y] pair.' }
    const x = typeof p[0] === 'number' ? p[0] : Number(p[0])
    const y = typeof p[1] === 'number' ? p[1] : Number(p[1])
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      return { error: 'point coordinates must be finite numbers.' }
    }
    pts.push([
      Math.min(Math.max(x, 0), 1), // normalized canvas coords, clamped
      Math.min(Math.max(y, 0), 1),
    ])
  }
  return { ok: { color, width, points: JSON.stringify(pts) } }
}

/** Shape a DB stroke row for the wire (points parsed back into number[][]). */
function serializeStroke(row: {
  id: string
  userId: string
  color: string
  width: number
  points: string
  createdAt: Date
}): { id: string; userId: string; color: string; width: number; points: number[][]; createdAt: string } {
  let points: number[][] = []
  try {
    const parsed: unknown = JSON.parse(row.points)
    if (Array.isArray(parsed)) {
      points = parsed.filter(
        (p): p is number[] =>
          Array.isArray(p) && p.length >= 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]),
      )
    }
  } catch {
    // corrupt row — ship it empty rather than crashing the whole board
  }
  return {
    id: row.id,
    userId: row.userId,
    color: row.color,
    width: row.width,
    points,
    createdAt: row.createdAt.toISOString(),
  }
}

/**
 * GET /api/conversations/[id]/whiteboard?requesterId=&since=<ISO|epoch ms>
 * Delta (or full, when `since` is omitted) stroke fetch + clear-marker watermark.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const requesterId = strField(url.searchParams.get('requesterId'))
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, requesterId)
  if (guard) return guard

  const since = parseSince(url.searchParams.get('since'))
  if ('error' in since) {
    return NextResponse.json({ error: since.error }, { status: 400 })
  }
  const sinceDate = since.ok

  const [rows, resetAt] = await Promise.all([
    db.whiteboardStroke.findMany({
      where: {
        conversationId: id,
        ...(sinceDate ? { createdAt: { gt: sinceDate } } : {}),
      },
      orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
      take: 4000,
      select: { id: true, userId: true, color: true, width: true, points: true, createdAt: true },
    }),
    latestResetAt(id),
  ])

  return NextResponse.json(
    {
      strokes: rows.map(serializeStroke),
      serverTime: Date.now(),
      resetAt,
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

/**
 * POST /api/conversations/[id]/whiteboard
 * Two modes on one endpoint:
 *  · { requesterId, strokes: [...] } → batch-insert 1..40 strokes
 *  · { action: 'undo', requesterId } → delete the caller's most recent stroke
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, requesterId)
  if (guard) return guard

  // ── undo mode: remove only the caller's latest stroke ────────
  if (body.action === 'undo') {
    const latest = await db.whiteboardStroke.findFirst({
      where: { conversationId: id, userId: requesterId },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      select: { id: true },
    })
    if (latest) {
      await db.whiteboardStroke.delete({ where: { id: latest.id } })
    }
    return NextResponse.json({
      success: true,
      removedId: latest?.id ?? null,
      serverTime: Date.now(),
    })
  }

  // ── insert mode: batch of 1..40 strokes ──────────────────────
  if (!Array.isArray(body.strokes)) {
    return NextResponse.json({ error: 'strokes must be an array.' }, { status: 400 })
  }
  if (body.strokes.length < 1 || body.strokes.length > STROKES_MAX_PER_CALL) {
    return NextResponse.json(
      { error: `send 1-${STROKES_MAX_PER_CALL} strokes per call.` },
      { status: 400 },
    )
  }
  const normalized: Array<{ color: string; width: number; points: string }> = []
  for (const raw of body.strokes) {
    const result = normalizeStroke(raw)
    if ('error' in result) {
      return NextResponse.json({ error: result.error }, { status: 400 })
    }
    normalized.push(result.ok)
  }

  // create() per stroke inside a transaction so the response can
  // hand back the real ids in input order (createMany returns none)
  const created = await db.$transaction(
    normalized.map((stroke) =>
      db.whiteboardStroke.create({
        data: { conversationId: id, userId: requesterId, ...stroke },
        select: { id: true },
      }),
    ),
  )

  return NextResponse.json(
    {
      ids: created.map((c) => c.id),
      created: created.length,
      serverTime: Date.now(),
    },
    { status: 201 },
  )
}

/**
 * DELETE /api/conversations/[id]/whiteboard?requesterId=
 * "Clear board" — wipes every stroke and appends a whiteboard-clear
 * LogEvent marker; pollers see `resetAt` move and wipe their canvas.
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const requesterId = strField(url.searchParams.get('requesterId'))
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, requesterId)
  if (guard) return guard

  const at = new Date()
  const results = await db.$transaction([
    db.whiteboardStroke.deleteMany({ where: { conversationId: id } }),
    db.logEvent.create({
      data: {
        userId: requesterId,
        kind: 'whiteboard-clear',
        message: id,
        meta: JSON.stringify({ at: at.toISOString() }),
      },
      select: { id: true },
    }),
  ])

  return NextResponse.json({
    success: true,
    reset: true,
    at: at.getTime(),
    cleared: results[0].count,
    serverTime: Date.now(),
  })
}

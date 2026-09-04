// ─────────────────────────────────────────────────────────────
// /api/conversations/[id]/kanban — group task board (Task R23-c)
// "Pulse group kanban board" — three real columns (todo/doing/done)
// persisted as KanbanCard rows so the board survives close/reopen
// and syncs across members by client polling (1500ms while open).
//
// Contracts:
//   GET  ?userId=
//        → requester must be a ConversationParticipant (403 otherwise).
//        → { cards: [{ id, title, column, position, assigneeId,
//              assigneeName, createdById, createdByName, createdAt,
//              updatedAt, conversationId }] }
//        ordered by board column (todo → doing → done), then position.
//        assignee/creator names resolved via the users table.
//   POST body { userId, title (1..120), column? ('todo' default),
//               assigneeId? (must be a room participant) }
//        → 201 { card } — position = (max position in that column) + 1.
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

// ── validation bounds ────────────────────────────────────────
const COLUMNS = ['todo', 'doing', 'done'] as const
type KanbanColumnId = (typeof COLUMNS)[number]
const TITLE_MIN = 1
const TITLE_MAX = 120
const CARDS_CAP = 500

function isColumn(value: string): value is KanbanColumnId {
  return (COLUMNS as readonly string[]).includes(value)
}

/** 403/404 response when the requester is not a conversation member, else null. */
async function participantGuard(
  conversationId: string,
  userId: string,
): Promise<NextResponse | null> {
  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({ where: { id: conversationId }, select: { id: true } }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId } },
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

/** id → display-name map for assignees/creators (one query, users table). */
async function resolveNames(userIds: Array<string | null>): Promise<Map<string, string>> {
  const ids = [...new Set(userIds.filter((v): v is string => typeof v === 'string' && v !== ''))]
  if (ids.length === 0) return new Map()
  const users = await db.user.findMany({ where: { id: { in: ids } }, select: { id: true, name: true } })
  return new Map(users.map((u) => [u.id, u.name]))
}

interface KanbanRow {
  id: string
  conversationId: string
  title: string
  column: string
  position: number
  assigneeId: string | null
  createdById: string | null
  createdAt: Date
  updatedAt: Date
}

/** Shape a DB card row for the wire (contract of the R23-c board sheet). */
function serializeCard(row: KanbanRow, names: Map<string, string>) {
  return {
    id: row.id,
    conversationId: row.conversationId,
    title: row.title,
    column: row.column,
    position: row.position,
    assigneeId: row.assigneeId,
    assigneeName: row.assigneeId ? (names.get(row.assigneeId) ?? null) : null,
    createdById: row.createdById,
    createdByName: row.createdById ? (names.get(row.createdById) ?? null) : null,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
  }
}

/**
 * GET /api/conversations/[id]/kanban?userId=
 * Full board snapshot for this room — every member polls this while
 * the sheet is open (1.5s) so moves/adds/removes propagate to all.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, userId)
  if (guard) return guard

  const rows = await db.kanbanCard.findMany({
    where: { conversationId: id },
    orderBy: [{ position: 'asc' }, { createdAt: 'asc' }],
    take: CARDS_CAP,
  })

  // Board order todo → doing → done, then position (alphabetical SQL
  // order would put "done" first — the board reads left-to-right).
  const orderIndex = (col: string): number => {
    const idx = (COLUMNS as readonly string[]).indexOf(col)
    return idx === -1 ? COLUMNS.length : idx
  }
  const sorted = [...rows].sort(
    (a, b) =>
      orderIndex(a.column) - orderIndex(b.column) ||
      a.position - b.position ||
      a.createdAt.getTime() - b.createdAt.getTime() ||
      (a.id < b.id ? -1 : 1),
  )

  const names = await resolveNames(sorted.flatMap((r) => [r.assigneeId, r.createdById]))

  return NextResponse.json(
    { cards: sorted.map((row) => serializeCard(row, names)) },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}

/**
 * POST /api/conversations/[id]/kanban
 * Create a card: { userId, title, column?, assigneeId? }.
 * Lands at the bottom of its column (max position + 1).
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const guard = await participantGuard(id, userId)
  if (guard) return guard

  const title = strField(body.title)
  if (title.length < TITLE_MIN || title.length > TITLE_MAX) {
    return NextResponse.json(
      { error: `title must be ${TITLE_MIN}-${TITLE_MAX} characters.` },
      { status: 400 },
    )
  }

  const column = strField(body.column) === '' ? 'todo' : strField(body.column)
  if (!isColumn(column)) {
    return NextResponse.json({ error: 'column must be one of todo, doing, done.' }, { status: 400 })
  }

  // Assignee (when set) must be a member of THIS room.
  let assigneeId: string | null = null
  const assigneeRaw = strField(body.assigneeId)
  if (assigneeRaw !== '') {
    const assigneeParticipant = await db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId: assigneeRaw, conversationId: id } },
      select: { userId: true },
    })
    if (!assigneeParticipant) {
      return NextResponse.json(
        { error: 'Assignee must be a participant of this conversation.' },
        { status: 400 },
      )
    }
    assigneeId = assigneeRaw
  }

  // Bottom of the column: (max position) + 1 — 0 for the first card.
  const agg = await db.kanbanCard.aggregate({
    where: { conversationId: id, column },
    _max: { position: true },
  })
  const position = (agg._max.position ?? -1) + 1

  const row = await db.kanbanCard.create({
    data: { conversationId: id, title, column, position, assigneeId, createdById: userId },
  })
  const names = await resolveNames([row.assigneeId, row.createdById])

  return NextResponse.json({ card: serializeCard(row, names) }, { status: 201 })
}

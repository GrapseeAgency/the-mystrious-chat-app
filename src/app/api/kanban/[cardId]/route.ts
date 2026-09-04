// ─────────────────────────────────────────────────────────────
// /api/kanban/[cardId] — single kanban card mutations (Task R23-c)
//
// Contracts:
//   PATCH  body { userId, title?, column?, assigneeId? (null clears),
//                 position? }
//          → requester must be a participant of the card's
//            conversation (403 otherwise). Moving to another column
//            sets position = (max in the target column) + 1
//            (move-to-end semantics) unless an explicit integer
//            position is supplied. → 200 { card }
//   DELETE ?userId=
//          → allowed for the card creator OR a group admin
//            (ConversationParticipant.role === 'admin' when the
//            conversation isGroup). → 200 { ok: true } · 403 otherwise.
//
// Every write lands in Prisma/SQLite — no mocks, no shortcuts.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ cardId: string }>
}

const COLUMNS = ['todo', 'doing', 'done'] as const
type KanbanColumnId = (typeof COLUMNS)[number]
const TITLE_MIN = 1
const TITLE_MAX = 120

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

/** id → display-name map for the card's assignee/creator (users table). */
async function resolveNames(userIds: Array<string | null>): Promise<Map<string, string>> {
  const ids = [...new Set(userIds.filter((v): v is string => typeof v === 'string' && v !== ''))]
  if (ids.length === 0) return new Map()
  const users = await db.user.findMany({ where: { id: { in: ids } }, select: { id: true, name: true } })
  return new Map(users.map((u) => [u.id, u.name]))
}

function serializeCard(
  row: {
    id: string
    conversationId: string
    title: string
    column: string
    position: number
    assigneeId: string | null
    createdById: string | null
    createdAt: Date
    updatedAt: Date
  },
  names: Map<string, string>,
) {
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
 * PATCH /api/kanban/[cardId]
 * Edit / move / reassign one card. Fields are optional and only the
 * provided ones change. `assigneeId: null` clears the assignee.
 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { cardId } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const card = await db.kanbanCard.findUnique({ where: { id: cardId } })
  if (!card) {
    return NextResponse.json({ error: 'Card not found.' }, { status: 404 })
  }

  // Member check runs against the card's conversation, not the URL.
  const guard = await participantGuard(card.conversationId, userId)
  if (guard) return guard

  const data: {
    title?: string
    column?: string
    position?: number
    assigneeId?: string | null
  } = {}

  if (body.title !== undefined) {
    const title = strField(body.title)
    if (title.length < TITLE_MIN || title.length > TITLE_MAX) {
      return NextResponse.json(
        { error: `title must be ${TITLE_MIN}-${TITLE_MAX} characters.` },
        { status: 400 },
      )
    }
    data.title = title
  }

  let targetColumn: string | null = null
  if (body.column !== undefined) {
    const column = strField(body.column)
    if (!isColumn(column)) {
      return NextResponse.json({ error: 'column must be one of todo, doing, done.' }, { status: 400 })
    }
    data.column = column
    targetColumn = column
  }

  // Assignee: key present → null/'' clears, non-empty must be a member
  // of the card's conversation.
  if (body.assigneeId !== undefined) {
    const assigneeRaw = strField(body.assigneeId)
    if (assigneeRaw === '') {
      data.assigneeId = null
    } else {
      const assigneeParticipant = await db.conversationParticipant.findUnique({
        where: {
          userId_conversationId: { userId: assigneeRaw, conversationId: card.conversationId },
        },
        select: { userId: true },
      })
      if (!assigneeParticipant) {
        return NextResponse.json(
          { error: 'Assignee must be a participant of this conversation.' },
          { status: 400 },
        )
      }
      data.assigneeId = assigneeRaw
    }
  }

  const columnChanged = targetColumn !== null && targetColumn !== card.column

  if (body.position !== undefined) {
    const raw = typeof body.position === 'number' ? body.position : Number(body.position)
    if (!Number.isInteger(raw) || raw < 0) {
      return NextResponse.json(
        { error: 'position must be a non-negative integer.' },
        { status: 400 },
      )
    }
    data.position = raw
  } else if (columnChanged) {
    // Move-to-end semantics: land at (max in the target column) + 1.
    const agg = await db.kanbanCard.aggregate({
      where: { conversationId: card.conversationId, column: targetColumn as string },
      _max: { position: true },
    })
    data.position = (agg._max.position ?? -1) + 1
  }

  const updated = await db.kanbanCard.update({ where: { id: cardId }, data })
  const names = await resolveNames([updated.assigneeId, updated.createdById])

  return NextResponse.json({ card: serializeCard(updated, names) })
}

/**
 * DELETE /api/kanban/[cardId]?userId=
 * Card creator OR group admin only — plain members cannot remove
 * other people's cards (403).
 */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { cardId } = await params
  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const card = await db.kanbanCard.findUnique({ where: { id: cardId } })
  if (!card) {
    return NextResponse.json({ error: 'Card not found.' }, { status: 404 })
  }

  const guard = await participantGuard(card.conversationId, userId)
  if (guard) return guard

  const [conv, participant] = await Promise.all([
    db.conversation.findUnique({
      where: { id: card.conversationId },
      select: { isGroup: true },
    }),
    db.conversationParticipant.findUnique({
      where: { userId_conversationId: { userId, conversationId: card.conversationId } },
      select: { role: true },
    }),
  ])

  const isCreator = card.createdById === userId
  const isRoomAdmin = conv?.isGroup === true && participant?.role === 'admin'
  if (!isCreator && !isRoomAdmin) {
    return NextResponse.json(
      { error: 'Only the card creator or a group admin can delete this card.' },
      { status: 403 },
    )
  }

  await db.kanbanCard.delete({ where: { id: cardId } })

  return NextResponse.json({ ok: true })
}

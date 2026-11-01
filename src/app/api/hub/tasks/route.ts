import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/hub/tasks - personal kanban (Hub → Tasks panel)
// Section 10 items 4/5: durable creation identity (a lost ACK replays the
// SAME clientId into the SAME stored task) and bounded history pages.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { clientIdFrom, decodeKeysetCursor, keysetBefore, opHash, pageLimit, pageVerdict } from '@/lib/section10-contracts'

export const dynamic = 'force-dynamic'

const TASK_TITLE_MAX = 120
const TASK_PAGE_MAX = 100

/**
 * GET /api/hub/tasks?userId=x [&limit=1..100&cursor=<epochMs>.<id>]
 * Legacy: doing → todo → done, newest first within a column.
 * Paged (item 5): bounded (createdAt, id) DESC history pages.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'personal tasks')
  if (denied) return denied

  const limitVerdict = pageLimit(url.searchParams.get('limit'), TASK_PAGE_MAX)
  if (!limitVerdict.ok) {
    return NextResponse.json({ error: `limit must be an integer from 1 to ${TASK_PAGE_MAX}.` }, { status: 400 })
  }

  if (limitVerdict.limit !== null) {
    const cursor = decodeKeysetCursor(url.searchParams.get('cursor'))
    if (!cursor.ok) {
      return NextResponse.json({ error: 'cursor must be a task continuation token.' }, { status: 400 })
    }
    const found = await db.hubTask.findMany({
      where: {
        ownerId: userId,
        ...('atMs' in cursor && cursor.atMs !== undefined ? keysetBefore(cursor.atMs, cursor.id) : {}),
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: limitVerdict.limit + 1,
    })
    const { rows, hasMore, nextCursor } = pageVerdict(found, limitVerdict.limit, (row) => ({ atMs: row.createdAt.getTime(), id: row.id }))
    return NextResponse.json({
      userId,
      tasks: rows.map(serializeTask),
      hasMore,
      nextCursor,
    }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
  }

  const tasks = await db.hubTask.findMany({
    where: { ownerId: userId },
    orderBy: [{ updatedAt: 'desc' }],
  })

  const rank: Record<string, number> = { doing: 0, todo: 1, done: 2 }
  const ordered = tasks.sort(
    (a, b) => (rank[a.status] ?? 1) - (rank[b.status] ?? 1) || b.updatedAt.getTime() - a.updatedAt.getTime(),
  )

  return NextResponse.json({
    userId,
    tasks: ordered.map(serializeTask),
  }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
}

function serializeTask(t: { id: string; ownerId: string; title: string; status: string; createdAt: Date; updatedAt: Date }) {
  return {
    id: t.id,
    ownerId: t.ownerId,
    title: t.title,
    status: t.status,
    createdAt: t.createdAt.toISOString(),
    updatedAt: t.updatedAt.toISOString(),
  }
}

/** POST /api/hub/tasks { userId, title, status?[, clientId] }
 * → 201 { task, replayed: false } | 200 { task, replayed: true } (item 4) */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const title = strField(body.title)
  const status = strField(body.status)

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, userId, 'personal tasks')
  if (denied) return denied
  if (!title || title.length > TASK_TITLE_MAX) {
    return NextResponse.json({ error: `Title must be 1–${TASK_TITLE_MAX} characters.` }, { status: 400 })
  }
  if (status && !['todo', 'doing', 'done'].includes(status)) {
    return NextResponse.json({ error: 'Status must be todo | doing | done.' }, { status: 400 })
  }
  const clientIdVerdict = clientIdFrom(body)
  if (!clientIdVerdict.ok) {
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  }
  const clientId = clientIdVerdict.clientId
  const settledStatus = status || 'todo'
  const requestHash = opHash(['task-create', userId, title, settledStatus])

  if (clientId) {
    const prior = await db.taskCreationOperation.findUnique({
      where: { userId_clientId: { userId, clientId } },
    })
    if (prior) {
      if (prior.requestHash !== requestHash) {
        return NextResponse.json({ error: 'This task operation ID was already used for different instructions.' }, { status: 409 })
      }
      const existing = await db.hubTask.findUnique({ where: { id: prior.taskId } })
      if (!existing) {
        return NextResponse.json({ error: 'This created task no longer exists.' }, { status: 410 })
      }
      return NextResponse.json({ task: serializeTask(existing), replayed: true }, { status: 200 })
    }
  }

  const task = await db.hubTask.create({
    data: { ownerId: userId, title, status: settledStatus },
  })
  if (clientId) {
    await db.taskCreationOperation.create({
      data: { userId, clientId, requestHash, taskId: task.id },
    })
  }

  return NextResponse.json(
    { task: serializeTask(task), replayed: false },
    { status: 201 },
  )
}

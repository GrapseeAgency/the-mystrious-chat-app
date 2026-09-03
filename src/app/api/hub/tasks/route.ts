// ─────────────────────────────────────────────────────────────
// /api/hub/tasks — personal kanban (Hub → Tasks panel)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const TASK_TITLE_MAX = 120

/**
 * GET /api/hub/tasks?userId=x → { tasks: HubTaskDTO[] }
 * Ordered: doing → todo → done, newest first within a column.
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
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
    tasks: ordered.map((t) => ({
      id: t.id,
      title: t.title,
      status: t.status,
      createdAt: t.createdAt.toISOString(),
      updatedAt: t.updatedAt.toISOString(),
    })),
  })
}

/** POST /api/hub/tasks { userId, title, status? } → 201 { task } */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const userId = strField(body.userId)
  const title = strField(body.title)
  const status = strField(body.status)

  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!title || title.length > TASK_TITLE_MAX) {
    return NextResponse.json({ error: `Title must be 1–${TASK_TITLE_MAX} characters.` }, { status: 400 })
  }
  if (status && !['todo', 'doing', 'done'].includes(status)) {
    return NextResponse.json({ error: 'Status must be todo | doing | done.' }, { status: 400 })
  }

  const task = await db.hubTask.create({
    data: { ownerId: userId, title, status: status || 'todo' },
  })

  return NextResponse.json(
    {
      task: {
        id: task.id,
        title: task.title,
        status: task.status,
        createdAt: task.createdAt.toISOString(),
        updatedAt: task.updatedAt.toISOString(),
      },
    },
    { status: 201 },
  )
}

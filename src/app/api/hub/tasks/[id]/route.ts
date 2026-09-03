// ─────────────────────────────────────────────────────────────
// /api/hub/tasks/[id] — move / rename / delete one kanban task
// Owner-guarded: only the task's creator can touch it.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** PATCH /api/hub/tasks/[id] { userId, title?, status? } → { task } */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const task = await db.hubTask.findUnique({ where: { id } })
  if (!task) {
    return NextResponse.json({ error: 'Task not found.' }, { status: 404 })
  }
  if (task.ownerId !== userId) {
    return NextResponse.json({ error: 'Only the owner can edit this task.' }, { status: 403 })
  }

  const data: { title?: string; status?: string } = {}
  if (body.title !== undefined) {
    const title = strField(body.title)
    if (!title || title.length > 120) {
      return NextResponse.json({ error: 'Title must be 1–120 characters.' }, { status: 400 })
    }
    data.title = title
  }
  if (body.status !== undefined) {
    const status = strField(body.status)
    if (!['todo', 'doing', 'done'].includes(status)) {
      return NextResponse.json({ error: 'Status must be todo | doing | done.' }, { status: 400 })
    }
    data.status = status
  }

  const updated = await db.hubTask.update({ where: { id }, data })

  return NextResponse.json({
    task: {
      id: updated.id,
      title: updated.title,
      status: updated.status,
      createdAt: updated.createdAt.toISOString(),
      updatedAt: updated.updatedAt.toISOString(),
    },
  })
}

/** DELETE /api/hub/tasks/[id]?userId=x → { ok: true } */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = url.searchParams.get('userId')?.trim() ?? ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const task = await db.hubTask.findUnique({ where: { id } })
  if (!task) {
    return NextResponse.json({ error: 'Task not found.' }, { status: 404 })
  }
  if (task.ownerId !== userId) {
    return NextResponse.json({ error: 'Only the owner can delete this task.' }, { status: 403 })
  }

  await db.hubTask.delete({ where: { id } })
  return NextResponse.json({ ok: true })
}

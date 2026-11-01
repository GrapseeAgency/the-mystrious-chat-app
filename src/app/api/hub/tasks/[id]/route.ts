import { requireActionOwner } from '@/lib/wallet-authorization'
// /api/hub/tasks/[id] - move / rename / delete one kanban task
// Owner-guarded: only the task's creator can touch it.
// Section 10 item 5: durable offline write receipts - a retried update or
// delete carries the SAME clientId and replays the SAME committed outcome
// (update receipts return the CURRENT row: a newer edit wins, never clobbers).
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { clientIdFrom, clientIdFromQuery, opHash } from '@/lib/section10-contracts'

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
  const denied = await requireActionOwner(req, userId, 'personal tasks')
  if (denied) return denied

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
  const clientIdVerdict = clientIdFrom(body)
  if (!clientIdVerdict.ok) {
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  }
  const clientId = clientIdVerdict.clientId
  const requestHash = opHash(['task-update', id, data.title ?? null, data.status ?? null])

  if (clientId) {
    const prior = await db.taskCreationOperation.findUnique({
      where: { userId_clientId: { userId, clientId } },
    })
    if (prior) {
      if (prior.requestHash !== requestHash) {
        return NextResponse.json({ error: 'This task operation ID was already used for different instructions.' }, { status: 409 })
      }
      const existing = await db.hubTask.findUnique({ where: { id: prior.taskId } })
      if (!existing) return NextResponse.json({ error: 'This task no longer exists.' }, { status: 410 })
      // Replay returns the CURRENT state: a newer edit is never rolled back.
      return NextResponse.json({ task: {
        id: existing.id, ownerId: existing.ownerId, title: existing.title, status: existing.status,
        createdAt: existing.createdAt.toISOString(), updatedAt: existing.updatedAt.toISOString(),
      }, replayed: true })
    }
  }

  const updated = await db.hubTask.update({ where: { id, ownerId: userId }, data })
  if (clientId) {
    await db.taskCreationOperation.create({
      data: { userId, clientId, requestHash, taskId: updated.id },
    })
  }

  return NextResponse.json({
    task: {
      id: updated.id,
      ownerId: updated.ownerId,
      title: updated.title,
      status: updated.status,
      createdAt: updated.createdAt.toISOString(),
      updatedAt: updated.updatedAt.toISOString(),
    },
    replayed: false,
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
  const denied = await requireActionOwner(req, userId, 'personal tasks')
  if (denied) return denied
  const clientIdVerdict = clientIdFromQuery(url.searchParams.get('clientId'))
  if (!clientIdVerdict.ok) {
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  }
  const clientId = clientIdVerdict.clientId
  const requestHash = opHash(['task-delete', id])

  if (clientId) {
    const prior = await db.taskCreationOperation.findUnique({
      where: { userId_clientId: { userId, clientId } },
    })
    if (prior) {
      if (prior.requestHash !== requestHash) {
        return NextResponse.json({ error: 'This task operation ID was already used for different instructions.' }, { status: 409 })
      }
      return NextResponse.json({ ok: true, userId, taskId: id, replayed: true })
    }
  }

  const task = await db.hubTask.findUnique({ where: { id } })
  if (!task) {
    return NextResponse.json({ error: 'Task not found.' }, { status: 404 })
  }
  if (task.ownerId !== userId) {
    return NextResponse.json({ error: 'Only the owner can delete this task.' }, { status: 403 })
  }

  await db.hubTask.delete({ where: { id, ownerId: userId } })
  if (clientId) {
    await db.taskCreationOperation.create({
      data: { userId, clientId, requestHash, taskId: id },
    })
  }
  return NextResponse.json({ ok: true, userId, taskId: id, replayed: false })
}

import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { kanbanOperation, KanbanError } from '@/lib/kanban-operation'

export const dynamic = 'force-dynamic'

export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const userId = strField(new URL(req.url).searchParams.get('userId'))
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'board')
  if (denied) return denied
  try {
    return NextResponse.json(await kanbanOperation(db, userId, { kind: 'list', conversationId: id }), { status: 200,
      headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
  } catch (error) {
    if (error instanceof KanbanError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

export async function POST(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await safeJson(req), userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'board')
  if (denied) return denied
  try {
    return NextResponse.json(await kanbanOperation(db, userId, { kind: 'create', conversationId: id, input: body }), { status: 201,
      headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
  } catch (error) {
    if (error instanceof KanbanError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

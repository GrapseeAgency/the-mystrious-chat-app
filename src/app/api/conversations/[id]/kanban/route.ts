import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { kanbanOperation, KanbanError } from '@/lib/kanban-operation'

export const dynamic = 'force-dynamic'

export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const url = new URL(req.url)
  const userId = strField(url.searchParams.get('userId'))
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'board')
  if (denied) return denied
  // Section 10 item 8: ?limit=1..200[&cursor=] opts into bounded board pages;
  // legacy callers (no limit) keep the complete-or-error snapshot.
  const rawLimit = url.searchParams.get('limit')
  const rawCursor = url.searchParams.get('cursor')
  try {
    return NextResponse.json(await kanbanOperation(db, userId, {
      kind: 'list', conversationId: id,
      ...(rawLimit !== null ? { limit: rawLimit, cursor: rawCursor } : {}),
    }), { status: 200,
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
    const result = await kanbanOperation(db, userId, { kind: 'create', conversationId: id, input: body })
    // A replayed card creation is 200, never a second 201 (item 6).
    return NextResponse.json(result, { status: 'replayed' in result && result.replayed ? 200 : 201,
      headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
  } catch (error) {
    if (error instanceof KanbanError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

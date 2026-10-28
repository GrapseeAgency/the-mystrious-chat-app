// Compatibility route, now governed by the same immutable CAS contract as native
// composer-state. Older blind writers must fail closed, not undo another device.
import { NextResponse } from 'next/server'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { readComposer, writeComposer, composerFailure } from '@/lib/composer-operation'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }
const headers = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }

export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const userId = strField(new URL(req.url).searchParams.get('userId'))
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'draft')
  if (denied) return denied
  try {
    const state = await readComposer(db, userId, 'draft', id, '')
    return NextResponse.json({ draft: state.text || null, revision: state.revision, userId, conversationId: id }, { headers })
  } catch (error) { return composerFailure(error) }
}

export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req), userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'draft')
  if (denied) return denied
  if (typeof body.draft !== 'string' || body.draft.length > 2000) {
    return NextResponse.json({ error: 'Draft must be a string of at most 2000 UTF-16 units. Nothing was changed.' }, { status: 400, headers })
  }
  if (typeof body.clientId !== 'string' || typeof body.expectedRevision !== 'string') {
    return NextResponse.json({ error: 'An observed draft revision and immutable operation ID are required. Update the client; the saved draft was not changed.', code: 'draft_precondition_required' }, { status: 428, headers })
  }
  try {
    const state = await writeComposer(db, userId, 'draft', id, '', body.draft,
      { clientId: body.clientId, expectedRevision: body.expectedRevision })
    const ok = state.outcome === 'applied'
    return NextResponse.json({ ok, draftReceiptVersion: 1, conversationId: id, userId,
      draft: state.text, revision: state.revision, clientId: state.clientId, outcome: state.outcome },
    { status: ok ? 200 : 409, headers })
  } catch (error) { return composerFailure(error) }
}

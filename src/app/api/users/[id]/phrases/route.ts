// /api/users/[id]/phrases - quick-phrase rail CRUD
// (Among Us-style one-tap line wheel; user-authored rows)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

const PHRASE_MAX = 120
const PHRASE_COUNT_MAX = 12

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** GET /api/users/[id]/phrases → { phrases: [{id,text,position}] } */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const refusal = await requireActionOwner(req, id, 'quick-phrase')
  if (refusal) return refusal

  const user = await db.user.findUnique({ where: { id }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  const phrases = await db.quickPhrase.findMany({
    where: { userId: id },
    orderBy: [{ position: 'asc' }, { id: 'asc' }],
  })
  return NextResponse.json({
    phrases: phrases.map((p) => ({ id: p.id, text: p.text, position: p.position })),
  }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
}

/** POST /api/users/[id]/phrases  body { text } → 201 { phrase } (append at end) */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const refusal = await requireActionOwner(req, id, 'quick-phrase')
  if (refusal) return refusal

  const body = await safeJson(req)
  const text = strField(body.text)
  if (text.length === 0 || text.length > PHRASE_MAX) {
    return NextResponse.json(
      { error: `Phrase must be 1-${PHRASE_MAX} characters.` },
      { status: 400 },
    )
  }

  const user = await db.user.findUnique({ where: { id }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  try {
    // The quota and next position must be decided in the same write snapshot.
    // Concurrent taps must not both observe eleven rows and create a thirteenth.
    return await db.$transaction(async tx => {
      const count = await tx.quickPhrase.count({ where: { userId: id } })
      if (count >= PHRASE_COUNT_MAX) return NextResponse.json(
        { error: `You can store up to ${PHRASE_COUNT_MAX} quick phrases.` }, { status: 400 })
      const maxPosition = await tx.quickPhrase.aggregate({ where: { userId: id }, _max: { position: true } })
      const phrase = await tx.quickPhrase.create({
        data: { userId: id, text, position: (maxPosition._max.position ?? -1) + 1 },
      })
      return NextResponse.json({ phrase: { id: phrase.id, text: phrase.text, position: phrase.position } }, { status: 201 })
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      return NextResponse.json({ error: 'Quick phrases changed or are busy. Refresh before retrying.' }, { status: 409 })
    }
    throw error
  }
}

/** DELETE /api/users/[id]/phrases?phraseId=X → { ok: true } (owner-guarded) */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const refusal = await requireActionOwner(req, id, 'quick-phrase')
  if (refusal) return refusal
  const url = new URL(req.url)
  const phraseId = url.searchParams.get('phraseId')
  if (!phraseId) {
    return NextResponse.json({ error: 'phraseId query parameter is required.' }, { status: 400 })
  }

  const deleted = await db.quickPhrase.deleteMany({ where: { id: phraseId, userId: id } })
  if (deleted.count !== 1) return NextResponse.json({ error: 'Phrase not found for this owner.' }, { status: 404 })
  return NextResponse.json({ ok: true, phraseId, userId: id, deleted: true })
}

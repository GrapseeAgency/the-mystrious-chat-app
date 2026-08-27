// ─────────────────────────────────────────────────────────────
// /api/users/[id]/phrases — quick-phrase rail CRUD
// (Among Us-style one-tap line wheel; user-authored rows)
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

const PHRASE_MAX = 120
const PHRASE_COUNT_MAX = 12

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** GET /api/users/[id]/phrases → { phrases: [{id,text,position}] } */
export async function GET(_req: Request, { params }: RouteCtx) {
  const { id } = await params

  const user = await db.user.findUnique({ where: { id }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  const phrases = await db.quickPhrase.findMany({
    where: { userId: id },
    orderBy: { position: 'asc' },
  })
  return NextResponse.json({
    phrases: phrases.map((p) => ({ id: p.id, text: p.text, position: p.position })),
  })
}

/** POST /api/users/[id]/phrases  body { text } → 201 { phrase } (append at end) */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

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

  const count = await db.quickPhrase.count({ where: { userId: id } })
  if (count >= PHRASE_COUNT_MAX) {
    return NextResponse.json(
      { error: `You can store up to ${PHRASE_COUNT_MAX} quick phrases.` },
      { status: 400 },
    )
  }

  const maxPosition = await db.quickPhrase.aggregate({
    where: { userId: id },
    _max: { position: true },
  })
  const phrase = await db.quickPhrase.create({
    data: { userId: id, text, position: (maxPosition._max.position ?? -1) + 1 },
  })
  return NextResponse.json({ phrase: { id: phrase.id, text: phrase.text, position: phrase.position } }, { status: 201 })
}

/** DELETE /api/users/[id]/phrases?phraseId=X → { ok: true } (owner-guarded) */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const url = new URL(req.url)
  const phraseId = url.searchParams.get('phraseId')
  if (!phraseId) {
    return NextResponse.json({ error: 'phraseId query parameter is required.' }, { status: 400 })
  }

  const phrase = await db.quickPhrase.findUnique({ where: { id: phraseId } })
  if (!phrase) {
    return NextResponse.json({ error: 'Phrase not found.' }, { status: 404 })
  }
  if (phrase.userId !== id) {
    return NextResponse.json({ error: 'Not your phrase.' }, { status: 403 })
  }

  await db.quickPhrase.delete({ where: { id: phraseId } })
  return NextResponse.json({ ok: true })
}

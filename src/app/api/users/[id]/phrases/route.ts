// /api/users/[id]/phrases - quick-phrase rail CRUD
// (Among Us-style one-tap line wheel; user-authored rows)
// Section 8 item 10: every accepted write carries the rail's listRev (a
// per-user monotonic counter) and supports durable replay: a lost-ACK retry
// with the SAME clientId returns the SAME outcome instead of duplicating or
// clobbering a newer edit. Cross-device ordering = apply only newer listRev.
import { NextResponse } from 'next/server'
import { createHash } from 'node:crypto'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'

const PHRASE_MAX = 120
const PHRASE_COUNT_MAX = 12
const CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,128}$/

interface RouteCtx {
  params: Promise<{ id: string }>
}

function phraseHash(action: string, phraseId: string, text: string): string {
  return createHash('sha256').update(JSON.stringify([action, phraseId, text])).digest('hex')
}

type RawTx = { $executeRaw: (query: TemplateStringsArray, ...values: unknown[]) => Promise<number>; $queryRaw: (query: TemplateStringsArray, ...values: unknown[]) => Promise<unknown[]> }
async function bumpListRev(tx: RawTx, userId: string): Promise<number> {
  await tx.$executeRaw`INSERT INTO UserListRevision (userId, kind, rev) VALUES (${userId}, 'phrases', 1)
    ON CONFLICT (userId, kind) DO UPDATE SET rev = rev + 1`
  const row = await tx.$queryRaw<Array<{ rev: number }>>`
    SELECT rev FROM UserListRevision WHERE userId = ${userId} AND kind = 'phrases'`
  return row[0].rev
}

async function readListRev(db: Pick<RawTx, '$queryRaw'>, userId: string): Promise<number> {
  const row = await db.$queryRaw<Array<{ rev: number }>>`
    SELECT rev FROM UserListRevision WHERE userId = ${userId} AND kind = 'phrases'`
  return row[0]?.rev ?? 0
}

/** Replay a durable operation: the stored receipt for this clientId, or null
 * when the identity is fresh. A reused identity with different instructions
 * rejects with 409 so evidence can never silently diverge. */
async function replayOp(
  tx: Parameters<Parameters<typeof db.$transaction>[0]>[0],
  userId: string, clientId: string, action: string, phraseId: string, text: string,
): Promise<{ replayed: true; phrase: { id: string; text: string; position: number } | null } | { replayed: false } | { conflict: true }> {
  const prior = await tx.quickPhraseOperation.findUnique({
    where: { userId_clientId: { userId, clientId } },
  })
  if (!prior) return { replayed: false }
  // Create receipts carry the minted row id; the identity binds the words.
  if (prior.action !== action || (action !== 'create' && prior.phraseId !== phraseId) ||
    prior.requestHash !== phraseHash(action, phraseId, text))
    return { conflict: true }
  const phrase = prior.action === 'delete'
    ? null
    : await tx.quickPhrase.findUnique({ where: { id: prior.phraseId }, select: { id: true, text: true, position: true } })
  return { replayed: true, phrase }
}

/** GET /api/users/[id]/phrases → { listRev, phrases: [{id,text,position}] } */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const refusal = await requireActionOwner(req, id, 'quick-phrase')
  if (refusal) return refusal

  const user = await db.user.findUnique({ where: { id }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  const [phrases, listRev] = await Promise.all([
    db.quickPhrase.findMany({
      where: { userId: id },
      orderBy: [{ position: 'asc' }, { id: 'asc' }],
    }),
    readListRev(db, id),
  ])
  return NextResponse.json({
    listRev,
    phrases: phrases.map((p) => ({ id: p.id, text: p.text, position: p.position })),
  }, { headers: { 'Cache-Control': 'private, no-store', Vary: 'Authorization' } })
}

/** POST /api/users/[id]/phrases  body { text, clientId? } → 201 { phrase, listRev }
 * (append at end; a retried clientId replays the same phrase) */
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
  if (body.clientId !== undefined && body.clientId !== null &&
    (typeof body.clientId !== 'string' || !CLIENT_ID_RE.test(body.clientId)))
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  const clientId = typeof body.clientId === 'string' ? body.clientId : null

  const user = await db.user.findUnique({ where: { id }, select: { id: true } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  try {
    // The quota, next position, receipt and revision must be decided in the
    // same write snapshot. Concurrent taps must not both observe eleven rows
    // and create a thirteenth, and a lost ACK must not duplicate a phrase.
    return await db.$transaction(async tx => {
      if (clientId) {
        const prior = await replayOp(tx, id, clientId, 'create', '', text)
        if ('conflict' in prior)
          return NextResponse.json({ error: 'This phrase operation ID was already used for different instructions.' }, { status: 409 })
        if (prior.replayed) {
          if (!prior.phrase)
            return NextResponse.json({ error: 'This created phrase no longer exists.' }, { status: 410 })
          return NextResponse.json({ phrase: prior.phrase, listRev: await readListRev(tx, id), replayed: true }, { status: 200 })
        }
      }
      const count = await tx.quickPhrase.count({ where: { userId: id } })
      if (count >= PHRASE_COUNT_MAX) return NextResponse.json(
        { error: `You can store up to ${PHRASE_COUNT_MAX} quick phrases.` }, { status: 400 })
      const maxPosition = await tx.quickPhrase.aggregate({ where: { userId: id }, _max: { position: true } })
      const phrase = await tx.quickPhrase.create({
        data: { userId: id, text, position: (maxPosition._max.position ?? -1) + 1 },
      })
      if (clientId) await tx.quickPhraseOperation.create({ data: {
        userId: id, clientId: clientId!, action: 'create', phraseId: phrase.id,
        requestHash: phraseHash('create', '', text),
      } })
      return NextResponse.json({ phrase: { id: phrase.id, text: phrase.text, position: phrase.position }, listRev: await bumpListRev(tx, id), replayed: false }, { status: 201 })
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? '')) {
      return NextResponse.json({ error: 'Quick phrases changed or are busy. Refresh before retrying.' }, { status: 409 })
    }
    throw error
  }
}

/** PATCH /api/users/[id]/phrases  body { phraseId, text, clientId? }
 * → 200 { phrase, listRev } (revision-gated text edit) */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const refusal = await requireActionOwner(req, id, 'quick-phrase')
  if (refusal) return refusal
  const body = await safeJson(req)
  const phraseId = strField(body.phraseId)
  const text = strField(body.text)
  if (!phraseId) return NextResponse.json({ error: 'phraseId is required.' }, { status: 400 })
  if (text.length === 0 || text.length > PHRASE_MAX)
    return NextResponse.json({ error: `Phrase must be 1-${PHRASE_MAX} characters.` }, { status: 400 })
  if (body.clientId !== undefined && body.clientId !== null &&
    (typeof body.clientId !== 'string' || !CLIENT_ID_RE.test(body.clientId)))
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  const clientId = typeof body.clientId === 'string' ? body.clientId : null
  try {
    return await db.$transaction(async tx => {
      if (clientId) {
        const prior = await replayOp(tx, id, clientId, 'update', phraseId, text)
        if ('conflict' in prior)
          return NextResponse.json({ error: 'This phrase operation ID was already used for different instructions.' }, { status: 409 })
        if (prior.replayed) {
          if (!prior.phrase)
            return NextResponse.json({ error: 'This edited phrase no longer exists.' }, { status: 410 })
          return NextResponse.json({ phrase: prior.phrase, listRev: await readListRev(tx, id), replayed: true })
        }
      }
      const updated = await tx.quickPhrase.updateMany({ where: { id: phraseId, userId: id }, data: { text } })
      if (updated.count !== 1)
        return NextResponse.json({ error: 'Phrase not found for this owner.' }, { status: 404 })
      if (clientId) await tx.quickPhraseOperation.create({ data: {
        userId: id, clientId: clientId!, action: 'update', phraseId,
        requestHash: phraseHash('update', phraseId, text),
      } })
      const phrase = await tx.quickPhrase.findUnique({ where: { id: phraseId }, select: { id: true, text: true, position: true } })
      return NextResponse.json({ phrase, listRev: await bumpListRev(tx, id), replayed: false })
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      return NextResponse.json({ error: 'Quick phrases changed or are busy. Refresh before retrying.' }, { status: 409 })
    throw error
  }
}

/** DELETE /api/users/[id]/phrases?phraseId=X[&clientId=] → { ok, listRev } (owner-guarded) */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const refusal = await requireActionOwner(req, id, 'quick-phrase')
  if (refusal) return refusal
  const url = new URL(req.url)
  const phraseId = url.searchParams.get('phraseId')
  if (!phraseId) {
    return NextResponse.json({ error: 'phraseId query parameter is required.' }, { status: 400 })
  }
  const clientIdRaw = url.searchParams.get('clientId')
  if (clientIdRaw !== null && !CLIENT_ID_RE.test(clientIdRaw))
    return NextResponse.json({ error: 'clientId must contain 16-128 ASCII letters, digits, underscores or hyphens.' }, { status: 400 })
  try {
    return await db.$transaction(async tx => {
      if (clientIdRaw) {
        const prior = await replayOp(tx, id, clientIdRaw, 'delete', phraseId, '')
        if ('conflict' in prior)
          return NextResponse.json({ error: 'This phrase operation ID was already used for different instructions.' }, { status: 409 })
        if (prior.replayed) return NextResponse.json({ ok: true, phraseId, userId: id, deleted: true, listRev: await readListRev(tx, id), replayed: true })
      }
      const deleted = await tx.quickPhrase.deleteMany({ where: { id: phraseId, userId: id } })
      if (deleted.count !== 1) return NextResponse.json({ error: 'Phrase not found for this owner.' }, { status: 404 })
      if (clientIdRaw) await tx.quickPhraseOperation.create({ data: {
        userId: id, clientId: clientIdRaw, action: 'delete', phraseId,
        requestHash: phraseHash('delete', phraseId, ''),
      } })
      return NextResponse.json({ ok: true, phraseId, userId: id, deleted: true, listRev: await bumpListRev(tx, id), replayed: false })
    }, { isolationLevel: 'Serializable' })
  } catch (error) {
    if (['P1008', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      return NextResponse.json({ error: 'Quick phrases changed or are busy. Refresh before retrying.' }, { status: 409 })
    throw error
  }
}

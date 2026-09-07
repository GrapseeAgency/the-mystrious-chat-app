// ─────────────────────────────────────────────────────────────
// /api/users/[id]/block — R47 account blocking.
// `[id]` is the TARGET user; the ACTOR rides along as `userId`
// (query for GET/DELETE, JSON body for POST) — mirrors the R35-a
// safety route's pair convention.
//
// Contract:
//   POST   { userId }                    → 200 { ok, blocked: true }
//   DELETE ?userId={actorId}             → 200 { ok, blocked: false }
//   GET    ?userId={actorId}             → 200 { blocked: boolean }  (pair state)
//
// Semantics: idempotent both ways; self-block is an honest 400;
// unknown actor/target ids are 404 (uniform "account is gone" — same
// policy as the safety route). Enforcement of the block lives at the
// DM boundaries (POST /api/conversations + messages POST) — this
// route only manages the flag rows.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Resolve both ends; returns the first error response or confirmed ids. */
async function resolvePair(targetId: string, actorId: string) {
  const [target, actor] = await Promise.all([
    db.user.findUnique({ where: { id: targetId }, select: { id: true } }),
    db.user.findUnique({ where: { id: actorId }, select: { id: true } }),
  ])
  if (!target) {
    return { error: NextResponse.json({ error: 'User not found.' }, { status: 404 }) }
  }
  if (!actor) {
    return { error: NextResponse.json({ error: 'Acting user not found.' }, { status: 404 }) }
  }
  return { targetId: target.id, actorId: actor.id }
}

/** GET — is `[id]` blocked BY `?userId`? (drives the profile-sheet toggle) */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: targetId } = await params

  const actorId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (actorId.length === 0) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const pair = await resolvePair(targetId, actorId)
  if ('error' in pair) return pair.error

  const row = await db.userBlock.findUnique({
    where: { blockerId_blockedId: { blockerId: pair.actorId, blockedId: pair.targetId } },
    select: { id: true },
  })
  return NextResponse.json({ blocked: row !== null })
}

/** POST { userId } — `[userId]` blocks `[id]`. Idempotent. */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id: targetId } = await params
  const body = await safeJson(req)

  const actorId = strField(body.userId)
  if (!actorId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (actorId === targetId) {
    return NextResponse.json({ error: 'You cannot block yourself.' }, { status: 400 })
  }

  const pair = await resolvePair(targetId, actorId)
  if ('error' in pair) return pair.error

  await db.userBlock.upsert({
    where: { blockerId_blockedId: { blockerId: pair.actorId, blockedId: pair.targetId } },
    create: { blockerId: pair.actorId, blockedId: pair.targetId },
    update: {},
  })
  return NextResponse.json({ ok: true, blocked: true })
}

/** DELETE ?userId= — `[userId]` unblocks `[id]`. Idempotent. */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id: targetId } = await params

  const actorId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (actorId.length === 0) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }
  if (actorId === targetId) {
    return NextResponse.json({ error: 'You cannot block yourself.' }, { status: 400 })
  }

  const pair = await resolvePair(targetId, actorId)
  if ('error' in pair) return pair.error

  await db.userBlock.deleteMany({
    where: { blockerId: pair.actorId, blockedId: pair.targetId },
  })
  return NextResponse.json({ ok: true, blocked: false })
}

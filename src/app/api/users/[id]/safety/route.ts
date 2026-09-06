// ─────────────────────────────────────────────────────────────
// /api/users/[id]/safety — Signal-style safety-number verification
// (R35-a). `[id]` is the PEER's user id; the viewer rides along as
// `userId` (query for GET/DELETE, JSON body for POST).
//
// Contract (lead-fixed):
//   GET    ?userId={viewerId} → 200 { peerId, safetyNumber (12×5 digits
//          space-separated), verified, verifiedAt ISO|null }
//   POST   { userId } → upsert verification → 200 { verified: true, verifiedAt }
//   DELETE ?userId={viewerId} → deleteMany rows → 200 { verified: false }
//
// Status policy (documented judgment call): a MISSING viewer id is a
// malformed request → 400. An id that does not exist in the directory —
// viewer OR peer alike — is 404, so "this account is gone" is uniform for
// both sides of the pair.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { deriveSafetyNumber } from '@/lib/safety'
import { safeJson } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** Look up both ends; returns the first error response or the confirmed ids. */
async function resolvePair(peerId: string, viewerId: string) {
  const [peer, viewer] = await Promise.all([
    db.user.findUnique({ where: { id: peerId }, select: { id: true } }),
    db.user.findUnique({ where: { id: viewerId }, select: { id: true } }),
  ])
  if (!peer) {
    return { error: NextResponse.json({ error: 'User not found.' }, { status: 404 }) }
  }
  if (!viewer) {
    return { error: NextResponse.json({ error: 'Viewing user not found.' }, { status: 404 }) }
  }
  return { peerId: peer.id, viewerId: viewer.id }
}

/** GET — the pair's shared safety number + THIS viewer's verification state. */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: peerId } = await params

  const viewerId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (viewerId.length === 0) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const resolved = await resolvePair(peerId, viewerId)
  if ('error' in resolved) return resolved.error

  const verification = await db.userVerification.findUnique({
    where: { ownerId_peerId: { ownerId: resolved.viewerId, peerId: resolved.peerId } },
  })

  return NextResponse.json({
    peerId: resolved.peerId,
    safetyNumber: deriveSafetyNumber(resolved.viewerId, resolved.peerId),
    verified: verification !== null,
    verifiedAt: verification ? verification.verifiedAt.toISOString() : null,
  })
}

/** POST — mark the peer verified (upsert; re-verifying refreshes verifiedAt). */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id: peerId } = await params

  const body = await safeJson(req)
  const viewerId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (viewerId.length === 0) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }

  const resolved = await resolvePair(peerId, viewerId)
  if ('error' in resolved) return resolved.error

  const row = await db.userVerification.upsert({
    where: { ownerId_peerId: { ownerId: resolved.viewerId, peerId: resolved.peerId } },
    // Re-verification through the compare-digits flow stamps a fresh time.
    update: { verifiedAt: new Date() },
    create: { ownerId: resolved.viewerId, peerId: resolved.peerId },
  })

  return NextResponse.json({ verified: true, verifiedAt: row.verifiedAt.toISOString() })
}

/** DELETE — reset the verification (idempotent deleteMany). */
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id: peerId } = await params

  const viewerId = new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (viewerId.length === 0) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }

  const resolved = await resolvePair(peerId, viewerId)
  if ('error' in resolved) return resolved.error

  await db.userVerification.deleteMany({
    where: { ownerId: resolved.viewerId, peerId: resolved.peerId },
  })

  return NextResponse.json({ verified: false })
}

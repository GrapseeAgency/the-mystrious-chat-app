// This is an owner-private account comparison marker, not an E2E key proof.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { deriveSafetyNumber } from '@/lib/safety'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

async function act(req: Request, { params }: RouteCtx, desired: boolean | null) {
  const { id: peerId } = await params
  const ownerId = desired === true ? strField((await safeJson(req)).userId) : new URL(req.url).searchParams.get('userId')?.trim() ?? ''
  if (!ownerId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, ownerId, 'account comparison')
  if (denied) return denied
  if (desired !== null && ownerId === peerId) return NextResponse.json({ error: 'Choose another account.' }, { status: 400 })
  try {
    const receipt = await db.$transaction(async tx => {
      const users = await tx.user.findMany({ where: { id: { in: [ownerId, peerId] } }, select: { id: true } })
      if (!users.some(user => user.id === ownerId) || !users.some(user => user.id === peerId)) return null
      const pair = { ownerId, peerId }
      if (desired === true) await tx.userVerification.upsert({ where: { ownerId_peerId: pair },
        create: pair, update: { verifiedAt: new Date() } })
      if (desired === false) await tx.userVerification.deleteMany({ where: pair })
      const row = await tx.userVerification.findUnique({ where: { ownerId_peerId: pair } })
      return { ok: true, safetyReceiptVersion: 1, ownerId, peerId,
        safetyNumber: deriveSafetyNumber(ownerId, peerId), verified: row !== null, verifiedAt: row?.verifiedAt.toISOString() ?? null }
    }, { isolationLevel: 'Serializable' })
    if (!receipt) return NextResponse.json({ error: 'Account not found.' }, { status: 404 })
    return NextResponse.json(receipt, { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (['P1008', 'P2002', 'P2028', 'P2034'].includes((error as { code?: string })?.code ?? ''))
      return NextResponse.json({ error: 'Comparison state changed or is busy. Refresh before retrying.' }, { status: 409 })
    throw error
  }
}
export async function GET(req: Request, ctx: RouteCtx) { return act(req, ctx, null) }
export async function POST(req: Request, ctx: RouteCtx) { return act(req, ctx, true) }
export async function DELETE(req: Request, ctx: RouteCtx) { return act(req, ctx, false) }

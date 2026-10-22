import { requireWalletOwner } from '@/lib/wallet-authorization'
// Listing claim, buyer debit, seller credit and both ledger legs are atomic.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { buyMarketListing, walletErrorResponse } from '@/lib/wallet-contract'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)
  const userId = strField(body.userId)
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireWalletOwner(req, userId)
  if (denied) return denied
  try {
    const wallet = await buyMarketListing(db, userId, id)
    return NextResponse.json({ ok: true, wallet })
  } catch (error) { return walletErrorResponse(error) }
}

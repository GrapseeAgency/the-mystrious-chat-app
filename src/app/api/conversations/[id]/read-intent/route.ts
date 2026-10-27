import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { captureReadIntent, ReadReceiptError } from '@/lib/read-receipt'

export const dynamic = 'force-dynamic'
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const userId = new URL(req.url).searchParams.get('userId')?.trim()
  if (!userId) return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  const denied = await requireActionOwner(req, userId, 'read intent')
  if (denied) return denied
  try {
    return NextResponse.json(await captureReadIntent(db, id, userId), { headers: { 'Cache-Control': 'private, no-store' } })
  } catch (error) {
    if (error instanceof ReadReceiptError) return NextResponse.json({ error: error.message }, { status: error.status })
    throw error
  }
}

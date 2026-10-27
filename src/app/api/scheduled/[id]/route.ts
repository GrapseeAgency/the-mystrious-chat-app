import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { cancelScheduled, scheduledErrorResponse, scheduledHeaders } from '@/lib/scheduled-operation'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }
export async function DELETE(req: Request, { params }: RouteCtx) {
  const { id } = await params, body = await safeJson(req)
  const owner = strField(body.requesterId)
  const refusal = await requireActionOwner(req, owner, 'scheduled-message')
  if (refusal) return refusal
  try {
    return NextResponse.json(await cancelScheduled(db, owner, id), { headers: scheduledHeaders })
  } catch (error) { return scheduledErrorResponse(error) }
}

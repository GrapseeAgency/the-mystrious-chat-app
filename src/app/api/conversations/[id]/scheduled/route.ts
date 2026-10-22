import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { parseIsoDate, safeJson, strField, MESSAGE_MAX } from '@/lib/serializers'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { scheduledPage, createScheduled, scheduledErrorResponse, scheduledHeaders } from '@/lib/scheduled-operation'
export const dynamic = 'force-dynamic'
interface RouteCtx { params: Promise<{ id: string }> }

export async function GET(req: Request, { params }: RouteCtx) {
  const { id } = await params, url = new URL(req.url)
  const owner = url.searchParams.get('userId') ?? ''
  const refusal = await requireActionOwner(req, owner, 'scheduled-message')
  if (refusal) return refusal
  try {
    return NextResponse.json(await scheduledPage(db, owner, id, url.searchParams.get('cursor')), { headers: scheduledHeaders })
  } catch (error) { return scheduledErrorResponse(error) }
}
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params, body = await safeJson(req)
  const owner = strField(body.senderId)
  const refusal = await requireActionOwner(req, owner, 'scheduled-message')
  if (refusal) return refusal
  const content = strField(body.content), when = parseIsoDate(body.scheduledAt)
  if (!content || content.length > MESSAGE_MAX) return NextResponse.json({ error: `Message must be 1-${MESSAGE_MAX} characters.` }, { status: 400 })
  if (!when) return NextResponse.json({ error: 'scheduledAt must be a valid ISO date string.' }, { status: 400 })
  try {
    return NextResponse.json(await createScheduled(db, owner, id, content, when), { status: 201, headers: scheduledHeaders })
  } catch (error) { return scheduledErrorResponse(error) }
}

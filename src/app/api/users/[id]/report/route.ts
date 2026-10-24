// /api/users/[id]/report - R48 "Report account" (WhatsApp-style)
// Private by design: the reported account is NEVER notified and reports
// are not readable by other users - rows land in AccountReport for
// moderation review. One row per (reporter, reported, reason): a repeat
// submission refreshes the details/timestamp instead of duplicating.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

const REPORT_REASONS = [
  'spam',
  'harassment',
  'impersonation',
  'inappropriate',
  'scam',
  'other',
] as const

const DETAILS_MAX = 500

/**
 * POST /api/users/[id]/report  body { userId, reason, details? }
 * `id` param = the account BEING reported; body.userId = the reporter.
 * → 201 { reported: true } (repeat with the same reason → 200 { reported: true, updated: true })
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id: reportedId } = await params

  const body = await safeJson(req)
  const reporterId = strField(body.userId)
  if (!reporterId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, reporterId, 'account report')
  if (denied) return denied
  if (reporterId === reportedId) {
    return NextResponse.json({ error: 'You cannot report your own account.' }, { status: 400 })
  }

  const reason = strField(body.reason)
  if (!(REPORT_REASONS as readonly string[]).includes(reason)) {
    return NextResponse.json(
      { error: `reason must be one of: ${REPORT_REASONS.join(', ')}.` },
      { status: 400 },
    )
  }

  if (body.details != null && typeof body.details !== 'string')
    return NextResponse.json({ error: 'Details must be text.' }, { status: 400 })
  // Preserve exactly the submitted text; do not silently truncate or normalize
  // evidence. The shared limit is UTF-16 units (also Kotlin String.length).
  const details = typeof body.details === 'string' ? body.details : ''
  if (details.length > DETAILS_MAX) {
    return NextResponse.json(
      { error: `Details must be ${DETAILS_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  try {
    const outcome = await db.$transaction(async tx => {
      const users = await tx.user.findMany({where:{id:{in:[reporterId,reportedId]}},select:{id:true}})
      if (users.length !== 2) return null
      const key = { reporterId, reportedId, reason }
      const existing = await tx.accountReport.findUnique({where:{reporterId_reportedId_reason:key},select:{id:true}})
      const row = await tx.accountReport.upsert({where:{reporterId_reportedId_reason:key},
        create:{...key,details},update:{details,createdAt:new Date()}})
      return {ok:true,reportReceiptVersion:1,reporterId,reportedId,reason,details:row.details,
        reported:true,updated:existing !== null,reportedAt:row.createdAt.toISOString()}
    },{isolationLevel:'Serializable'})
    if (!outcome) return NextResponse.json({error:'Account not found.'},{status:404})
    return NextResponse.json(outcome,{status:outcome.updated?200:201,headers:{'Cache-Control':'private, no-store'}})
  } catch(error) {
    if (['P1008','P2002','P2028','P2034'].includes((error as {code?:string})?.code ?? ''))
      return NextResponse.json({error:'Report state changed or is busy. Refresh before retrying.'},{status:409})
    throw error
  }
}

/**
 * GET /api/users/[id]/report?userId=X - self-service: the reporter's OWN
 * submissions about this account (drives the "already reported" hint).
 * Cross-viewing someone else's reports is impossible - reports stay private.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: reportedId } = await params
  const url = new URL(req.url)
  const reporterId = url.searchParams.get('userId')?.trim()
  if (!reporterId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }
  const denied = await requireActionOwner(req, reporterId, 'account report history')
  if (denied) return denied
  const rows = await db.$transaction(async tx => {
    const users = await tx.user.findMany({where:{id:{in:[reporterId,reportedId]}},select:{id:true}})
    if (!users.some(user=>user.id===reporterId) || !users.some(user=>user.id===reportedId)) return null
    return tx.accountReport.findMany({where:{reporterId,reportedId},select:{reason:true,createdAt:true},
      orderBy:[{createdAt:'desc'},{id:'asc'}]})
  },{isolationLevel:'Serializable'})
  if (!rows) return NextResponse.json({error:'Account not found.'},{status:404})
  return NextResponse.json({reportHistoryVersion:1,reporterId,reportedId,
    reasons:rows.map(row=>({reason:row.reason,at:row.createdAt.toISOString()})),
  },{headers:{'Cache-Control':'private, no-store'}})
}

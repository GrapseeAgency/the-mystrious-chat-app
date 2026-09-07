// ─────────────────────────────────────────────────────────────
// /api/users/[id]/report — R48 "Report account" (WhatsApp-style)
// ─────────────────────────────────────────────────────────────
// Private by design: the reported account is NEVER notified and reports
// are not readable by other users — rows land in AccountReport for
// moderation review. One row per (reporter, reported, reason): a repeat
// submission refreshes the details/timestamp instead of duplicating.
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
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

  const details = strField(body.details)
  if (details.length > DETAILS_MAX) {
    return NextResponse.json(
      { error: `Details must be ${DETAILS_MAX} characters or fewer.` },
      { status: 400 },
    )
  }

  const [reporter, reported] = await Promise.all([
    db.user.findUnique({ where: { id: reporterId }, select: { id: true } }),
    db.user.findUnique({ where: { id: reportedId }, select: { id: true } }),
  ])
  if (!reporter || !reported) {
    return NextResponse.json({ error: 'Account not found.' }, { status: 404 })
  }

  const existing = await db.accountReport.findUnique({
    where: {
      reporterId_reportedId_reason: { reporterId, reportedId, reason },
    },
    select: { id: true },
  })

  if (existing) {
    await db.accountReport.update({
      where: { id: existing.id },
      data: { details, createdAt: new Date() },
    })
    return NextResponse.json({ reported: true, updated: true })
  }

  await db.accountReport.create({
    data: { reporterId, reportedId, reason, details },
  })
  return NextResponse.json({ reported: true }, { status: 201 })
}

/**
 * GET /api/users/[id]/report?userId=X — self-service: the reporter's OWN
 * submissions about this account (drives the "already reported" hint).
 * Cross-viewing someone else's reports is impossible — reports stay private.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { id: reportedId } = await params
  const url = new URL(req.url)
  const reporterId = url.searchParams.get('userId')
  if (!reporterId) {
    return NextResponse.json({ error: 'userId query parameter is required.' }, { status: 400 })
  }
  if (reporterId === reportedId) {
    return NextResponse.json({ reasons: [] })
  }

  const rows = await db.accountReport.findMany({
    where: { reporterId, reportedId },
    select: { reason: true, createdAt: true },
    orderBy: { createdAt: 'desc' },
  })
  return NextResponse.json({
    reasons: rows.map((r) => ({ reason: r.reason, at: r.createdAt.toISOString() })),
  })
}

// ─────────────────────────────────────────────────────────────
// /api/hub/logs — DevOps terminal stream (Hub → Logs panel)
// Real events written by the economy/social endpoints.
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'

export const dynamic = 'force-dynamic'

/** GET /api/hub/logs?limit=60&kind=transfer → { logs: LogEntryDTO[] } */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const limitRaw = Number.parseInt(url.searchParams.get('limit') ?? '60', 10)
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 200) : 60
  const kind = url.searchParams.get('kind')?.trim() ?? ''

  const logs = await db.logEvent.findMany({
    where: kind ? { kind } : undefined,
    orderBy: { createdAt: 'desc' },
    take: limit,
    include: {
      user: { select: { id: true, name: true, username: true, color: true } },
    },
  })

  return NextResponse.json({
    logs: logs.map((l) => ({
      id: l.id,
      kind: l.kind,
      message: l.message,
      meta: l.meta ?? null,
      createdAt: l.createdAt.toISOString(),
      user: l.user,
    })),
  })
}

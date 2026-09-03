// ─────────────────────────────────────────────────────────────
// /api/users/check-username?username=x — live handle availability
// Powers the onboarding username picker ("@x is free" / taken+suggestion).
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { normalizeUsername, suggestUsername } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/**
 * GET /api/users/check-username?username=alice
 * → { available: true, suggestion: null }
 * → { available: false, suggestion: 'alice2' }  (nearest free variant)
 * → 400 on structurally invalid handles
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const raw = url.searchParams.get('username') ?? ''

  const username = normalizeUsername(raw)
  if (!username) {
    return NextResponse.json(
      { error: 'Handle must be 3–20 chars: lowercase letters, digits, underscore.' },
      { status: 400 },
    )
  }

  const clash = await db.user.findUnique({
    where: { username },
    select: { id: true },
  })

  if (!clash) {
    return NextResponse.json({ available: true, suggestion: null })
  }

  const suggestion = await suggestUsername(username)
  return NextResponse.json({ available: false, suggestion })
}

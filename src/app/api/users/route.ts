// /api/users - create a user, list all users (contacts)
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapUser,
  normalizeColor,
  normalizeUsername,
  safeJson,
  strField,
  suggestUsername,
  USER_NAME_MAX,
} from '@/lib/serializers'
import { ensurePulseBot } from '@/lib/ai-bot'
import { generateSessionToken, hashSessionToken } from '@/lib/session-token'

export const dynamic = 'force-dynamic'

/**
 * POST /api/users { name, color?, username? } → 201 { user: AppUser, token }
 * Names are unique case-insensitively; @handles are unique exactly and
 * lowercase-normalized (3–20 chars, a-z0-9_).
 * → 409 { error, code: 'username_taken', suggestion } when the handle clashes.
 * Wave 8 (spec §3.11 A-1): the response now ALSO carries `token` (random
 * 32 B hex, raw shown exactly once; only its sha256 is stored). Web ignores
 * the field today (token adoption is phase 2); natives persist it in
 * Keystore/Keychain and attach `Authorization: Bearer` + socket join token.
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const name = strField(body.name)
  if (!name || name.length > USER_NAME_MAX) {
    return NextResponse.json(
      { error: `Name is required and must be 1–${USER_NAME_MAX} characters.` },
      { status: 400 },
    )
  }

  // optional @handle - validated + collision-checked up front
  let username: string | null = null
  if (body.username !== undefined && strField(body.username).length > 0) {
    const candidate = normalizeUsername(body.username)
    if (!candidate) {
      return NextResponse.json(
        { error: 'Handle must be 3–20 chars: lowercase letters, digits, underscore.' },
        { status: 400 },
      )
    }
    const clash = await db.user.findUnique({ where: { username: candidate }, select: { id: true } })
    if (clash) {
      const suggestion = await suggestUsername(candidate)
      return NextResponse.json(
        { error: `@${candidate} is already taken.`, code: 'username_taken', suggestion },
        { status: 409 },
      )
    }
    username = candidate
  }

  // SQLite has no insensitive filter in Prisma - scan same-letter candidates and
  // compare folded in JS. The candidate set stays tiny at chat-app scale.
  const first = name.trim().charAt(0).toLowerCase()
  const candidates = await db.user.findMany({
    where: { OR: [{ name: { startsWith: first } }, { name: { startsWith: first.toUpperCase() } }] },
    select: { id: true, name: true },
  })
  if (candidates.some((u) => u.name.toLowerCase() === name.toLowerCase())) {
    return NextResponse.json(
      { error: 'That name is taken on this Pulse. Try another one.' },
      { status: 409 },
    )
  }

  const user = await db.user.create({
    data: { name, username, color: normalizeColor(body.color) },
  })

  // every new identity gets a Hub wallet (starting balance 100 PC / 5 GEM)
  await db.userWallet.upsert({ where: { userId: user.id }, create: { userId: user.id }, update: {} })

  // Wave 8 A-1: issue the session token (raw returned once, hash stored).
  const token = generateSessionToken()
  await db.user.update({ where: { id: user.id }, data: { sessionTokenHash: hashSessionToken(token) } })

  return NextResponse.json({ user: mapUser(user), token }, { status: 201 })
}

/**
 * GET /api/users                     → { users: AppUser[] } sorted by name asc
 *   Section 8 item 5: the bare directory is BOUNDED (max 500 rows) and carries
 *   { nextCursor, hasMore }; clients page with ?limit=&after=<last id>.
 * GET /api/users?name=Alice%20Chen   → { user: AppUser } | 404
 * GET /api/users?username=alice      → { user: AppUser } | 404  (@handle lookup)
 * Case-insensitive exact match lookup powering the ?login= deep-link and the
 * onboarding "that's you? log in" affordance (Pulse identities are name-keyed).
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const name = url.searchParams.get('name')?.trim() ?? ''
  const username = url.searchParams.get('username')?.trim() ?? ''

  if (username.length > 0) {
    const handle = username.toLowerCase().replace(/^@/, '')
    const user = await db.user.findUnique({ where: { username: handle } })
    if (!user) {
      return NextResponse.json({ error: 'No Pulse account with that handle.' }, { status: 404 })
    }
    return NextResponse.json({ user: mapUser(user) })
  }

  if (name.length > 0) {
    if (name.length > USER_NAME_MAX) {
      return NextResponse.json(
        { error: `Name must be 1–${USER_NAME_MAX} characters.` },
        { status: 400 },
      )
    }
    const first = name.charAt(0).toLowerCase()
    const candidates = await db.user.findMany({
      where: { OR: [{ name: { startsWith: first } }, { name: { startsWith: first.toUpperCase() } }] },
    })
    const user = candidates.find((u) => u.name.toLowerCase() === name.toLowerCase())
    if (!user) {
      return NextResponse.json({ error: 'No Pulse account with that name.' }, { status: 404 })
    }
    return NextResponse.json({ user: mapUser(user) })
  }

  // Section 8 item 5: keyset pagination over (name, id) - the only stable total
  // order for a large contact directory. The cursor is the last row's id; the
  // page size is bounded so "load everyone in one query" cannot return.
  const limitRaw = url.searchParams.get('limit')
  const afterId = url.searchParams.get('after')?.trim() ?? ''
  const limit = Math.min(Math.max(limitRaw ? Number.parseInt(limitRaw, 10) || 0 : 500, 1), 500)
  const after = afterId
    ? await db.user.findUnique({ where: { id: afterId }, select: { id: true, name: true } })
    : null
  if (afterId && !after) {
    return NextResponse.json({ error: 'Unknown after cursor.' }, { status: 400 })
  }
  // ensure the AI companion exists so it is addable like any user
  if (!after) await ensurePulseBot()
  const rows = await db.user.findMany({
    where: after
      ? { OR: [{ name: { gt: after.name } }, { name: after.name, id: { gt: after.id } }] }
      : undefined,
    orderBy: [{ name: 'asc' }, { id: 'asc' }],
    take: limit + 1,
  })
  const hasMore = rows.length > limit
  const page = rows.slice(0, limit)
  return NextResponse.json({
    users: page.map(mapUser),
    nextCursor: hasMore && page.length ? page[page.length - 1].id : null,
    hasMore,
    // Legacy full-list callers keep working: the first page is simply the
    // head of the same total order, and hasMore tells them to continue.
  })
}

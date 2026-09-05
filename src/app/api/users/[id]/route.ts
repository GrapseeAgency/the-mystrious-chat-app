// ─────────────────────────────────────────────────────────────
// /api/users/[id] — fetch one user (session validation), edit profile
// ─────────────────────────────────────────────────────────────
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import {
  mapUser,
  normalizeColor,
  normalizeUsername,
  safeJson,
  strField,
  suggestUsername,
  ABOUT_MAX,
  USER_NAME_MAX,
} from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ id: string }>
}

/** GET /api/users/[id] → { user: AppUser } | 404 */
export async function GET(_req: Request, { params }: RouteCtx) {
  const { id } = await params
  const user = await db.user.findUnique({ where: { id } })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  return NextResponse.json({ user: mapUser(user) })
}

/** PATCH /api/users/[id] { name?, about?, color? } → { user: AppUser } | 400 | 404 */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const body = await safeJson(req)

  const data: {
    name?: string
    username?: string | null
    about?: string
    color?: string
    avatar?: string | null
    statusEmoji?: string | null
    statusText?: string | null
    lastSeenAt: Date
  } = {
    lastSeenAt: new Date(), // any successful profile touch counts as "last active"
  }

  if (body.name !== undefined) {
    const name = strField(body.name)
    if (!name || name.length > USER_NAME_MAX) {
      return NextResponse.json(
        { error: `Name must be 1–${USER_NAME_MAX} characters.` },
        { status: 400 },
      )
    }
    data.name = name
  }

  // @handle edit — explicit key; '' clears, otherwise validate + clash-check
  if (body.username !== undefined) {
    const raw = strField(body.username)
    if (raw.length === 0) {
      data.username = null
    } else {
      const candidate = normalizeUsername(raw)
      if (!candidate) {
        return NextResponse.json(
          { error: 'Handle must be 3–20 chars: lowercase letters, digits, underscore.' },
          { status: 400 },
        )
      }
      const clash = await db.user.findUnique({
        where: { username: candidate },
        select: { id: true },
      })
      if (clash && clash.id !== id) {
        const suggestion = await suggestUsername(candidate)
        return NextResponse.json(
          { error: `@${candidate} is already taken.`, code: 'username_taken', suggestion },
          { status: 409 },
        )
      }
      data.username = candidate
    }
  }

  if (body.about !== undefined) {
    const about = strField(body.about)
    if (!about || about.length > ABOUT_MAX) {
      return NextResponse.json(
        { error: `About must be 1–${ABOUT_MAX} characters.` },
        { status: 400 },
      )
    }
    data.about = about
  }

  if (body.color !== undefined) {
    data.color = normalizeColor(body.color) // unknown colors fall back to 'emerald'
  }

  // Profile photo — explicit key; '' or null clears. Must be a server-managed /uploads/ path
  // (client uploads via POST /api/uploads first, then PATCHes the returned path).
  if (body.avatar !== undefined) {
    const avatar = strField(body.avatar)
    if (avatar.length === 0) {
      data.avatar = null
    } else if (!/^\/api\/uploads\/[A-Za-z0-9._-]{8,120}$/.test(avatar)) {
      return NextResponse.json(
        { error: "avatar must be a path returned by POST /api/uploads (e.g. /api/uploads/<uuid>.jpg), or empty to clear." },
        { status: 400 },
      )
    } else {
      data.avatar = avatar
    }
  }

  // Discord-style custom status — explicit keys only; null clears.
  if (body.statusEmoji !== undefined) {
    const emoji = strField(body.statusEmoji)
    if (emoji.length > 8) {
      return NextResponse.json({ error: 'statusEmoji must be 8 characters or fewer.' }, { status: 400 })
    }
    data.statusEmoji = emoji.length === 0 ? null : emoji
  }
  if (body.statusText !== undefined) {
    const text = strField(body.statusText)
    if (text.length > 48) {
      return NextResponse.json({ error: 'statusText must be 48 characters or fewer.' }, { status: 400 })
    }
    data.statusText = text.length === 0 ? null : text
  }

  const existing = await db.user.findUnique({ where: { id }, select: { id: true } })
  if (!existing) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  const user = await db.user.update({ where: { id }, data })
  return NextResponse.json({ user: mapUser(user) })
}

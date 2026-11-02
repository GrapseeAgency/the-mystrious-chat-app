// /api/users/[id] - fetch one user (session validation), edit profile
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { Prisma } from '../../../../../prisma/generated-client'
import {
  mapUser,
  notifySocket,
  normalizeColor,
  normalizeUsername,
  safeJson,
  strField,
  suggestUsername,
  ABOUT_MAX,
  USER_NAME_MAX,
} from '@/lib/serializers'
import { privacyPreferenceEnabled } from '@/lib/privacy-preferences'
import { STATUS_ICON_IDS } from '@/lib/icon-ids'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { clientIdFrom } from '@/lib/section10-contracts'
import { probeSettingsOp, settleSettingsOp } from '@/lib/settings-contracts'

/** The recognized profile instruction fields (identity for the durable
 * replay receipt - everything else in the body is ignored noise). */
const PROFILE_FIELDS = ['name', 'username', 'about', 'color', 'avatar', 'coverImage', 'statusEmoji', 'statusText'] as const

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
  // Section 13 item 9: devices diff their cached profile against this
  // monotonic revision instead of field-by-field guessing.
  return NextResponse.json({ user: mapUser(user), revision: user.profileRev })
}

/** PATCH /api/users/[id] { name?, about?, color?, clientId?, intentAt? } → { user, revision } | 400 | 404
 * Section 13 items 2/7/9: with a clientId the write carries a durable
 * identity (a lost ACK replays the STORED profile instead of re-guessing),
 * every accepted write bumps and returns the monotonic profileRev, and ANY
 * accepted field change is pushed to the owner's other surfaces (not just
 * renames). Legacy callers keep the immediate-apply behavior. */
export async function PATCH(req: Request, { params }: RouteCtx) {
  const { id } = await params
  const denied = await requireActionOwner(req, id, 'profile')
  if (denied) return denied
  const body = await safeJson(req)
  // Section 13 item 2: durable write identity, probed before any read so a
  // lost-ACK retry replays the original commit's exact response.
  const clientIdVerdict = clientIdFrom(body)
  if (!clientIdVerdict.ok) {
    return NextResponse.json({ error: 'clientId must be 16-128 url-safe characters.' }, { status: 400 })
  }
  const clientId: string | null = clientIdVerdict.clientId
  if (clientId) {
    const identity = [id, PROFILE_FIELDS.filter(f => body[f] !== undefined).map(f => [f, body[f]])]
    const probe = await probeSettingsOp<{ user: ReturnType<typeof mapUser>; revision: number }>(
      db,
      { kind: 'profile.patch', clientId, userId: id, identity },
    )
    if (probe.status === 'mismatch') {
      return NextResponse.json({ error: 'This clientId was already used with different profile instructions.', code: 'profile_operation_mismatch' }, { status: 409 })
    }
    if (probe.status === 'replay') {
      return NextResponse.json({ ...probe.result, replayed: true })
    }
  }
  // A malformed value is not an instruction to clear a field. In particular,
  // strField(false/object) must not silently erase a photo, handle or bio.
  const nullable = new Set(['username', 'avatar', 'coverImage', 'statusEmoji', 'statusText'])
  for (const field of ['name', 'username', 'about', 'color', 'avatar', 'coverImage', 'statusEmoji', 'statusText']) {
    const value = body[field]
    if (value !== undefined && typeof value !== 'string' && !(value === null && nullable.has(field))) {
      return NextResponse.json({ error: `${field} must be text${nullable.has(field) ? ' or null to clear' : ''}.` }, { status: 400 })
    }
  }

  const data: {
    name?: string
    username?: string | null
    about?: string
    color?: string
    avatar?: string | null
    coverImage?: string | null
    statusEmoji?: string | null
    statusText?: string | null
    lastSeenAt?: Date
    profileRev?: { increment: number }
  } = {}

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

  // @handle edit - explicit key; '' clears, otherwise validate + clash-check
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
    if (about.length > ABOUT_MAX) {
      return NextResponse.json(
        { error: `About must be ${ABOUT_MAX} characters or fewer.` },
        { status: 400 },
      )
    }
    data.about = about
  }

  if (body.color !== undefined) {
    data.color = normalizeColor(body.color) // unknown colors fall back to 'emerald'
  }

  // Profile photo - explicit key; '' or null clears. Must be a server-managed /uploads/ path
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

  // Profile cover picture - explicit key; '' or null clears. Same server-managed
  // /uploads/ path contract as the avatar (upload first, PATCH the path second).
  if (body.coverImage !== undefined) {
    const cover = strField(body.coverImage)
    if (cover.length === 0) {
      data.coverImage = null
    } else if (!/^\/api\/uploads\/[A-Za-z0-9._-]{8,120}$/.test(cover)) {
      return NextResponse.json(
        { error: "coverImage must be a path returned by POST /api/uploads (e.g. /api/uploads/<uuid>.jpg), or empty to clear." },
        { status: 400 },
      )
    } else {
      data.coverImage = cover
    }
  }

  // Discord-style custom status - explicit keys only; null clears.
  // statusEmoji carries a status icon id from the registry, not a raw glyph.
  if (body.statusEmoji !== undefined) {
    const emoji = strField(body.statusEmoji)
    if (emoji.length > 0 && !(STATUS_ICON_IDS as readonly string[]).includes(emoji)) {
      return NextResponse.json({ error: 'Unknown status icon id.' }, { status: 400 })
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

  const existing = await db.user.findUnique({ where: { id }, select: { id: true, preferences: true } })
  if (!existing) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  // R46 - any successful profile touch counts as "last active", UNLESS the
  // user hides their last seen (then the stamp freezes - enforced privacy).
  if (privacyPreferenceEnabled(existing.preferences, 'lastSeenVisible')) {
    data.lastSeenAt = new Date()
  }

  // Section 13 item 9: the revision rides the SAME commit as the fields, so
  // a reader can never see new fields under an old revision.
  data.profileRev = { increment: 1 }

  const profileIdentity = [id, PROFILE_FIELDS.filter(f => body[f] !== undefined).map(f => [f, body[f]])]
  const commit = async (client: typeof db) => {
    const user = await client.user.update({ where: { id }, data })
    const revision = user.profileRev
    if (clientId) {
      const settled = await settleSettingsOp(client, { kind: 'profile.patch', clientId, userId: id, identity: profileIdentity },
        { user: mapUser(user), revision })
      if (settled.replayed) return { user, revision, replayed: true as const }
      return { user, revision, replayed: false as const }
    }
    return { user, revision, replayed: false as const }
  }

  try {
    const outcome = clientId
      ? await db.$transaction(tx => commit(tx as unknown as typeof db))
      : await commit(db)
    // Section 13 item 7: every accepted profile change (not just renames)
    // invalidates the owner's other open surfaces immediately.
    if (Object.keys(data).some(k => k !== 'profileRev' && k !== 'lastSeenAt')) {
      await notifySocket('private-feeds:changed', [id], { userId: id })
    }
    return NextResponse.json({ user: mapUser(outcome.user), revision: outcome.revision, ...(outcome.replayed ? { replayed: true } : {}) })
  } catch (error) {
    // Availability checks are advisory. Two owners can pass the check before
    // either write commits; the unique index is the final authority. The
    // durable receipt can also lose its own create race (concurrent duplicate
    // with the same clientId) - re-probe answers with the winner's receipt.
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
      const target = JSON.stringify((error as { meta?: { target?: unknown } }).meta?.target ?? '')
      if (data.username && target.includes('username')) {
        return NextResponse.json({ error: `@${data.username} was just taken.`, code: 'username_taken',
          suggestion: await suggestUsername(data.username) }, { status: 409 })
      }
      if (clientId && target.includes('clientId')) {
        const probe = await probeSettingsOp<{ user: ReturnType<typeof mapUser>; revision: number }>(
          db,
          { kind: 'profile.patch', clientId, userId: id, identity: profileIdentity },
        )
        if (probe.status === 'replay') {
          return NextResponse.json({ ...probe.result, replayed: true })
        }
      }
    }
    throw error
  }
}

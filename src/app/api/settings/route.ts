import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mergePrefs } from '@/lib/prefs-defaults'
import { privacyPreferenceSnapshot } from '@/lib/privacy-preferences'

/**
 * GET /api/settings?userId=<id>
 * → 200 { preferences: PulsePrefs }  (defaults merged over stored blob)
 */
export async function GET(req: Request) {
  const url = new URL(req.url)
  const userId = (url.searchParams.get('userId') || '').trim()
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  const user = await db.user.findUnique({
    where: { id: userId },
    select: { preferences: true },
  })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }
  let stored: unknown = null
  if (user.preferences) {
    try {
      stored = JSON.parse(user.preferences)
    } catch {
      stored = null
    }
  }
  return NextResponse.json({ preferences: { ...mergePrefs(stored), ...privacyPreferenceSnapshot(user.preferences) } })
}

/**
 * PATCH /api/settings  body { userId, preferences: Partial<PulsePrefs> }
 * Shallow-merges the patch over the stored blob, clamps every field, saves.
 * → 200 { preferences: PulsePrefs }
 */
export async function PATCH(req: Request) {
  let body: Record<string, unknown> = {}
  try {
    const raw: unknown = await req.json()
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      return NextResponse.json({ error: 'Body must be an object.' }, { status: 400 })
    }
    body = raw as Record<string, unknown>
  } catch {
    return NextResponse.json({ error: 'Body must be valid JSON.' }, { status: 400 })
  }
  const userId = typeof body.userId === 'string' ? body.userId.trim() : ''
  if (!userId) {
    return NextResponse.json({ error: 'userId is required.' }, { status: 400 })
  }
  if (!body.preferences || typeof body.preferences !== 'object' || Array.isArray(body.preferences)) {
    return NextResponse.json({ error: 'preferences object is required.' }, { status: 400 })
  }

  const user = await db.user.findUnique({
    where: { id: userId },
    select: { preferences: true },
  })
  if (!user) {
    return NextResponse.json({ error: 'User not found.' }, { status: 404 })
  }

  let stored: Record<string, unknown> = {}
  if (user.preferences) {
    try {
      const parsed = JSON.parse(user.preferences)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        stored = parsed as Record<string, unknown>
      }
    } catch {
      stored = {}
    }
  }

  const patch = body.preferences as Record<string, unknown>
  for (const key of ['readReceipts', 'lastSeenVisible', 'typingVisible', 'notifPreviews']) {
    if (Object.prototype.hasOwnProperty.call(patch, key) && typeof patch[key] !== 'boolean') {
      return NextResponse.json({ error: `${key} must be a boolean.` }, { status: 400 })
    }
  }
  const merged = mergePrefs({ ...stored, ...privacyPreferenceSnapshot(user.preferences), ...patch })
  const serialized = JSON.stringify(merged)
  if (serialized.length > 4096) {
    return NextResponse.json({ error: 'Preferences payload too large.' }, { status: 413 })
  }
  await db.user.update({ where: { id: userId }, data: { preferences: serialized } })
  return NextResponse.json({ preferences: merged })
}

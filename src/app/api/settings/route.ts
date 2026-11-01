import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mergePrefs } from '@/lib/prefs-defaults'
import { privacyPreferenceSnapshot } from '@/lib/privacy-preferences'
import { requireActionOwner } from '@/lib/wallet-authorization'

export const dynamic = 'force-dynamic'
const settingsHeaders = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }

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
  const denied = await requireActionOwner(req, userId, 'settings')
  if (denied) return denied
  const user = await db.user.findUnique({
    where: { id: userId },
    select: { preferences: true, storyViewReceipts: true },
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
  // Section 9 item 3: the storyViewReceipts COLUMN is the disclosure truth
  // (story receipt queries read the column, live); the JSON snapshot value is
  // never allowed to disagree with it in the merged response.
  return NextResponse.json({
    preferences: {
      ...mergePrefs(stored),
      ...privacyPreferenceSnapshot(user.preferences),
      storyViewReceipts: user.storyViewReceipts,
    },
  }, { headers: settingsHeaders })
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

  const denied = await requireActionOwner(req, userId, 'settings')
  if (denied) return denied
  const patch = body.preferences as Record<string, unknown>
  for (const key of ['readReceipts', 'lastSeenVisible', 'typingVisible', 'notifPreviews', 'storyViewReceipts']) {
    if (Object.prototype.hasOwnProperty.call(patch, key) && typeof patch[key] !== 'boolean') {
      return NextResponse.json({ error: `${key} must be a boolean.` }, { status: 400 })
    }
  }
  // Section 9 item 3: the story receipt disclosure lives in a real column so
  // owner-side viewer lists and view counts can filter it in SQL (live at
  // disclosure time). PATCH mirrors the JSON blob value into the column.
  const storyReceiptsPatch = typeof patch.storyViewReceipts === 'boolean' ? patch.storyViewReceipts : null

  // Compare-and-swap the exact snapshot used for the merge. Concurrent writes
  // to distinct fields must not erase each other; retry against the latest row.
  for (let attempt = 0; attempt < 4; attempt++) {
    const user = await db.user.findUnique({ where: { id: userId }, select: { preferences: true } })
    if (!user) return NextResponse.json({ error: 'User not found.' }, { status: 404 })
    let stored: Record<string, unknown> = {}
    if (user.preferences) {
      try {
        const parsed: unknown = JSON.parse(user.preferences)
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) stored = parsed as Record<string, unknown>
      } catch { /* Invalid privacy remains conservative until an explicit change. */ }
    }
    const merged = mergePrefs({ ...stored, ...privacyPreferenceSnapshot(user.preferences), ...patch })
    // The column (not the JSON blob) decides disclosure; keep both in the SAME
    // atomic update so a read can never see a torn preference pair.
    if (storyReceiptsPatch !== null) merged.storyViewReceipts = storyReceiptsPatch
    const serialized = JSON.stringify(merged)
    if (serialized.length > 4096) return NextResponse.json({ error: 'Preferences payload too large.' }, { status: 413 })
    const written = await db.user.updateMany({
      where: { id: userId, preferences: user.preferences },
      data: {
        preferences: serialized,
        ...(storyReceiptsPatch !== null ? { storyViewReceipts: storyReceiptsPatch } : {}),
      },
    })
    if (written.count === 1) return NextResponse.json({ preferences: merged }, { headers: settingsHeaders })
  }
  return NextResponse.json({ error: 'Settings changed while saving. Retry the retained changes.', code: 'settings_conflict' },
    { status: 409, headers: { ...settingsHeaders, 'Retry-After': '1' } })
}

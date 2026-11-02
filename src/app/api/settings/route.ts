import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { mergePrefs } from '@/lib/prefs-defaults'
import { privacyPreferenceSnapshot } from '@/lib/privacy-preferences'
import { requireActionOwner } from '@/lib/wallet-authorization'
import { clientIdFrom } from '@/lib/section10-contracts'
import { probeSettingsOp, settleSettingsOp, bumpSettingsRevision, readSettingsRevision, readSettingsFieldStamps, resolveSettingsFields, stampSettingsFields, type FieldIntent } from '@/lib/settings-contracts'
import { notifySocket } from '@/lib/serializers'

export const dynamic = 'force-dynamic'
const settingsHeaders = { 'Cache-Control': 'private, no-store', Vary: 'Authorization' }

/**
 * GET /api/settings?userId=<id>
 * → 200 { preferences: PulsePrefs, revision, fieldStamps }
 * (defaults merged over stored blob; revision + per-field stamps are the
 * Section 13 delivery contract - devices refresh on strictly-greater
 * revisions and can see which writer owns each field's value).
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
  const [revision, fieldStamps] = await Promise.all([
    readSettingsRevision(db, userId),
    readSettingsFieldStamps(db, userId),
  ])
  // Section 9 item 3: the storyViewReceipts COLUMN is the disclosure truth
  // (story receipt queries read the column, live); the JSON snapshot value is
  // never allowed to disagree with it in the merged response.
  return NextResponse.json({
    preferences: {
      ...mergePrefs(stored),
      ...privacyPreferenceSnapshot(user.preferences),
      storyViewReceipts: user.storyViewReceipts,
    },
    revision,
    fieldStamps,
  }, { headers: settingsHeaders })
}

/**
 * Parse the per-field intent times a client attaches to its patch. The
 * intent time is WHEN THE USER MADE THE CHANGE on that device, not when the
 * request arrived - it is the watermark the last-write-wins ledger compares.
 */
function intentTimes(
  patch: Record<string, unknown>,
  body: Record<string, unknown>,
): { intents: FieldIntent[]; error?: string } {
  const fallbackRaw = body.intentAt
  let fallback = new Date()
  if (fallbackRaw !== undefined) {
    if (typeof fallbackRaw !== 'string' || !Number.isFinite(Date.parse(fallbackRaw))) {
      return { intents: [], error: 'intentAt must be an ISO timestamp.' }
    }
    fallback = new Date(fallbackRaw)
  }
  const perFieldRaw = body.fieldTimes
  if (perFieldRaw !== undefined && (typeof perFieldRaw !== 'object' || perFieldRaw === null || Array.isArray(perFieldRaw))) {
    return { intents: [], error: 'fieldTimes must be an object of ISO timestamps.' }
  }
  const perField = (perFieldRaw ?? {}) as Record<string, unknown>
  const intents: FieldIntent[] = []
  for (const field of Object.keys(patch)) {
    const raw = perField[field]
    if (raw === undefined) {
      intents.push({ field, intentAt: fallback })
      continue
    }
    if (typeof raw !== 'string' || !Number.isFinite(Date.parse(raw))) {
      return { intents: [], error: `fieldTimes.${field} must be an ISO timestamp.` }
    }
    intents.push({ field, intentAt: new Date(raw) })
  }
  return { intents }
}

/**
 * PATCH /api/settings  body { userId, preferences, clientId?, intentAt?, fieldTimes? }
 * Shallow-merges the patch over the stored blob, clamps every field, saves.
 * Section 13:
 *  - item 2: with a clientId the write carries a durable identity; a lost
 *    ACK replays the STORED result instead of guessing, and a reused
 *    identity with different instructions is a 409.
 *  - item 1: each field is resolved against the per-field LWW ledger by its
 *    intent time, so a slow device's older write is reported as superseded
 *    instead of silently erasing the newer one from another device.
 *  - item 7: every accepted patch bumps the per-user revision and is pushed
 *    to the owner's other surfaces immediately.
 * → 200 { preferences: PulsePrefs, revision, applied, superseded, replayed? }
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
  const { intents, error } = intentTimes(patch, body)
  if (error) return NextResponse.json({ error }, { status: 400 })

  // Section 13 item 2: durable write identity. Probed BEFORE any read so a
  // lost-ACK retry replays the original commit's exact response.
  const clientIdVerdict = clientIdFrom(body)
  if (!clientIdVerdict.ok) {
    return NextResponse.json({ error: 'clientId must be 16-128 url-safe characters.' }, { status: 400 })
  }
  const clientId: string | null = clientIdVerdict.clientId
  if (clientId) {
    const probe = await probeSettingsOp<{ preferences: Record<string, unknown>; revision: number; applied: string[]; superseded: string[] }>(
      db,
      { kind: 'settings.patch', clientId, userId, identity: [userId, Object.entries(patch).sort()] },
    )
    if (probe.status === 'mismatch') {
      return NextResponse.json({ error: 'This clientId was already used with different settings instructions.', code: 'settings_operation_mismatch' },
        { status: 409, headers: settingsHeaders })
    }
    if (probe.status === 'replay') {
      return NextResponse.json({ ...probe.result, replayed: true }, { headers: settingsHeaders })
    }
  }

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
    // Section 13 item 1: resolve every field against the LWW ledger before
    // touching the blob - strictly-newer stored intent wins, ties go to the
    // caller so an honest retry converges.
    const { accepted, superseded } = await resolveSettingsFields(db, userId, intents)
    const effective: Record<string, unknown> = {}
    for (const { field } of accepted) effective[field] = patch[field]
    if (accepted.length === 0) {
      const revision = await readSettingsRevision(db, userId)
      const result = {
        preferences: mergePrefs({ ...stored, ...privacyPreferenceSnapshot(user.preferences) }),
        revision,
        applied: [] as string[],
        superseded,
      }
      if (clientId) {
        const settled = await settleSettingsOp(db, { kind: 'settings.patch', clientId, userId, identity: [userId, Object.entries(patch).sort()] }, result)
        return NextResponse.json({ ...settled.result, ...(settled.replayed ? { replayed: true } : {}) }, { headers: settingsHeaders })
      }
      return NextResponse.json(result, { headers: settingsHeaders })
    }
    const merged = mergePrefs({ ...stored, ...privacyPreferenceSnapshot(user.preferences), ...effective })
    // The column (not the JSON blob) decides disclosure; keep both in the SAME
    // atomic update so a read can never see a torn preference pair.
    if (storyReceiptsPatch !== null && effective.storyViewReceipts !== undefined) merged.storyViewReceipts = storyReceiptsPatch
    const serialized = JSON.stringify(merged)
    if (serialized.length > 4096) return NextResponse.json({ error: 'Preferences payload too large.' }, { status: 413 })
    const written = await db.user.updateMany({
      where: { id: userId, preferences: user.preferences },
      data: {
        preferences: serialized,
        ...(effective.storyViewReceipts !== undefined ? { storyViewReceipts: storyReceiptsPatch } : {}),
      },
    })
    if (written.count === 1) {
      // The write won: stamp the ledger, bump the revision, settle the
      // durable receipt, and push every other surface in that order.
      const revision = await bumpSettingsRevision(db, userId)
      await stampSettingsFields(db, userId, accepted, revision, clientId ?? 'legacy')
      const result = {
        preferences: merged,
        revision,
        applied: accepted.map(f => f.field),
        superseded,
      }
      if (clientId) {
        const settled = await settleSettingsOp(db, { kind: 'settings.patch', clientId, userId, identity: [userId, Object.entries(patch).sort()] }, result)
        if (settled.replayed) {
          return NextResponse.json({ ...settled.result, replayed: true }, { headers: settingsHeaders })
        }
      }
      // Section 13 item 7: the owner's other open surfaces converge now,
      // not on their next hydrate (relay also refreshes its privacy gates).
      await notifySocket('settings:changed', [userId], { userId, revision })
      return NextResponse.json(result, { headers: settingsHeaders })
    }
  }
  return NextResponse.json({ error: 'Settings changed while saving. Retry the retained changes.', code: 'settings_conflict' },
    { status: 409, headers: { ...settingsHeaders, 'Retry-After': '1' } })
}

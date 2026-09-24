// ─────────────────────────────────────────────────────────────────────────────
// Pulse — remote push transport (server-side only).
//
// One registry (`PushToken` rows) feeds THREE delivery channels:
//   • web     → Web Push (VAPID). Fully live: keys generated locally, the
//               browser service worker (public/sw-push.js) displays the
//               notification even with every Pulse tab closed.
//   • android → FCM HTTP v1. Code path complete; delivery arms the moment a
//               Firebase service account is configured (env below) — without
//               credentials the sender FAILS CLOSED (skips, honest log), it
//               never pretends to send.
//   • ios     → APNs token-based (JWT/ES256). Same fail-closed contract;
//               arms when the Apple p8 key + ids are configured.
//
// Fanout policy: a user is skipped entirely while a socket session is online
// (the socket already carries realtime events into the open app); everyone
// else gets one push per registered device. Dead web endpoints (404/410) and
// unregistered mobile tokens prune their registry row so the registry
// self-heals. Push must NEVER fail a user-facing request — every call site
// is fire-and-forget.
// ─────────────────────────────────────────────────────────────────────────────
import 'server-only'
import crypto from 'node:crypto'
import { db } from '@/lib/db'

export type PushPlatform = 'web' | 'android' | 'ios'

export interface PushPayload {
  kind: 'message' | 'gcall' | 'call'
  /** Notification title — sender display name (or "Group call"). */
  title: string
  /** Notification body — preview text, or a generic line when previews are off. */
  body: string
  conversationId: string
  messageId?: string
  callKind?: 'voice' | 'video'
  callerName?: string
}

const SOCKET_URL = 'http://localhost:3003'

// ── Web Push (VAPID) — live in this deployment ───────────────────────────────

let webPushLib: typeof import('web-push') | null = null
let webPushConfigured = false

async function webPushSender(): Promise<typeof import('web-push') | null> {
  if (webPushLib) return webPushConfigured ? webPushLib : null
  try {
    const publicKey = process.env.VAPID_PUBLIC_KEY ?? ''
    const privateKey = process.env.VAPID_PRIVATE_KEY ?? ''
    if (!publicKey || !privateKey) {
      console.warn('[push] web: VAPID keys missing — web push disabled (fail-closed)')
      webPushConfigured = false
      return null
    }
    const lib = (await import('web-push')).default as unknown as typeof import('web-push')
    lib.setVapidDetails(
      process.env.VAPID_SUBJECT || 'mailto:push@pulse.chat',
      publicKey,
      privateKey,
    )
    webPushLib = lib
    webPushConfigured = true
    return lib
  } catch {
    return null
  }
}

// ── FCM HTTP v1 (service-account OAuth2, cached) ─────────────────────────────

let fcmAccessToken: { token: string; expiresAt: number } | null = null

function fcmCredentials(): { projectId: string; clientEmail: string; privateKey: string } | null {
  const projectId = process.env.PULSE_FCM_PROJECT_ID ?? ''
  const clientEmail = process.env.PULSE_FCM_CLIENT_EMAIL ?? ''
  const privateKey = (process.env.PULSE_FCM_PRIVATE_KEY ?? '').replace(/\\n/g, '\n')
  if (!projectId || !clientEmail || !privateKey) return null
  return { projectId, clientEmail, privateKey }
}

/** RS256 JWT → OAuth2 access token for the FCM v1 API (cached ~50 min). */
async function fcmBearer(): Promise<string | null> {
  const creds = fcmCredentials()
  if (!creds) return null
  if (fcmAccessToken && Date.now() < fcmAccessToken.expiresAt) return fcmAccessToken.token
  try {
    const now = Math.floor(Date.now() / 1000)
    const header = { alg: 'RS256', typ: 'JWT' }
    const claims = {
      iss: creds.clientEmail,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token',
      iat: now,
      exp: now + 3600,
    }
    const b64 = (obj: unknown) => Buffer.from(JSON.stringify(obj)).toString('base64url')
    const unsigned = `${b64(header)}.${b64(claims)}`
    const signer = crypto.createSign('RSA-SHA256')
    signer.update(unsigned)
    const signature = signer.sign(creds.privateKey).toString('base64url')
    const assertion = `${unsigned}.${signature}`
    const res = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion,
      }),
      signal: AbortSignal.timeout(8000),
    })
    if (!res.ok) return null
    const body = (await res.json()) as { access_token?: string; expires_in?: number }
    if (!body.access_token) return null
    fcmAccessToken = {
      token: body.access_token,
      expiresAt: Date.now() + Math.max(60, (body.expires_in ?? 3600) - 300) * 1000,
    }
    return fcmAccessToken.token
  } catch {
    return null
  }
}

// ── APNs token-based provider token (ES256, cached) ──────────────────────────

let apnsProviderToken: { token: string; expiresAt: number } | null = null

function apnsCredentials(): { keyPem: string; keyId: string; teamId: string } | null {
  const keyPem = (process.env.PULSE_APNS_KEY ?? '').replace(/\\n/g, '\n')
  const keyId = process.env.PULSE_APNS_KEY_ID ?? ''
  const teamId = process.env.PULSE_APNS_TEAM_ID ?? ''
  if (!keyPem || !keyId || !teamId) return null
  return { keyPem, keyId, teamId }
}

/**
 * APNs requires the RAW r||s ECDSA signature (64 bytes), while node emits
 * ASN.1 DER (`30 <len> 02 <rLen> <r…> 02 <sLen> <s…>`). Parse the DER
 * structure, strip each INTEGER's leading zero pad, left-pad both halves
 * to exactly 32 bytes.
 */
function derEcdsaToRaw(der: Buffer): Buffer | null {
  try {
    if (der[0] !== 0x30) return null
    let offset = 2 // 0x30, total length byte
    const ints: Buffer[] = []
    while (offset < der.length && ints.length < 2) {
      if (der[offset] !== 0x02) return null
      const len = der[offset + 1]
      let value = der.subarray(offset + 2, offset + 2 + len)
      // strip leading zero padding (DER keeps it to mark positive integers)
      while (value.length > 1 && value[0] === 0) value = value.subarray(1)
      ints.push(Buffer.from(value))
      offset += 2 + len
    }
    if (ints.length !== 2) return null
    const [r, s] = ints
    const leftPad = (b: Buffer) => Buffer.concat([Buffer.alloc(32 - Math.min(32, b.length)), b.subarray(-32)])
    return Buffer.concat([leftPad(r), leftPad(s)])
  } catch {
    return null
  }
}

/** ES256 provider token for APNs (cached ~50 min, Apple allows up to 1 h). */
async function apnsBearer(): Promise<string | null> {
  const creds = apnsCredentials()
  if (!creds) return null
  if (apnsProviderToken && Date.now() < apnsProviderToken.expiresAt) return apnsProviderToken.token
  try {
    const now = Math.floor(Date.now() / 1000)
    const header = { alg: 'ES256', kid: creds.keyId }
    const claims = { iss: creds.teamId, iat: now }
    const b64 = (obj: unknown) => Buffer.from(JSON.stringify(obj)).toString('base64url')
    const unsigned = `${b64(header)}.${b64(claims)}`
    const signer = crypto.createSign('SHA256')
    signer.update(unsigned)
    const raw = derEcdsaToRaw(signer.sign(creds.keyPem))
    if (!raw) return null
    apnsProviderToken = {
      token: `${unsigned}.${raw.toString('base64url')}`,
      expiresAt: Date.now() + 50 * 60 * 1000,
    }
    return apnsProviderToken.token
  } catch {
    return null
  }
}

// ── online suppression ───────────────────────────────────────────────────────

async function onlineUserIds(): Promise<Set<string>> {
  try {
    const res = await fetch(`${SOCKET_URL}/online`, { signal: AbortSignal.timeout(1500) })
    if (!res.ok) return new Set()
    const body = (await res.json()) as { onlineUserIds?: unknown }
    if (!Array.isArray(body.onlineUserIds)) return new Set()
    return new Set(body.onlineUserIds.filter((id): id is string => typeof id === 'string'))
  } catch {
    return new Set()
  }
}

// ── per-platform senders ─────────────────────────────────────────────────────

type SendResult = { ok: boolean; pruneToken: boolean; skipped?: boolean }

async function sendWeb(token: string, payload: PushPayload): Promise<SendResult> {
  const lib = await webPushSender()
  if (!lib) return { ok: false, pruneToken: false, skipped: true }
  try {
    await lib.sendNotification(
      token,
      JSON.stringify({
        kind: payload.kind,
        title: payload.title,
        body: payload.body,
        conversationId: payload.conversationId,
        messageId: payload.messageId ?? null,
        callKind: payload.callKind ?? null,
        callerName: payload.callerName ?? null,
      }),
      { TTL: 3600, urgency: payload.kind === 'message' ? 'normal' : 'high' },
    )
    return { ok: true, pruneToken: false }
  } catch (err) {
    const statusCode = (err as { statusCode?: number }).statusCode
    const prune = statusCode === 404 || statusCode === 410
    return { ok: false, pruneToken: prune }
  }
}

async function sendAndroid(token: string, payload: PushPayload): Promise<SendResult> {
  const bearer = await fcmBearer()
  if (!bearer) return { ok: false, pruneToken: false, skipped: true }
  const projectId = fcmCredentials()?.projectId ?? ''
  try {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: payload.title, body: payload.body },
          data: {
            kind: payload.kind,
            conversationId: payload.conversationId,
            ...(payload.messageId ? { messageId: payload.messageId } : {}),
            ...(payload.callKind ? { callKind: payload.callKind } : {}),
            ...(payload.callerName ? { callerName: payload.callerName } : {}),
          },
          android: {
            priority: payload.kind === 'message' ? 'normal' : 'high',
          },
        },
      }),
      signal: AbortSignal.timeout(8000),
    })
    const prune = res.status === 404 || res.status === 410 || res.status === 400
    return { ok: res.ok, pruneToken: prune }
  } catch {
    return { ok: false, pruneToken: false }
  }
}

async function sendIos(token: string, payload: PushPayload): Promise<SendResult> {
  const bearer = await apnsBearer()
  if (!bearer) return { ok: false, pruneToken: false, skipped: true }
  try {
    const res = await fetch(`https://api.push.apple.com/3/device/${encodeURIComponent(token)}`, {
      method: 'POST',
      headers: {
        Authorization: `bearer ${bearer}`,
        'apns-topic': process.env.PULSE_APNS_BUNDLE_ID || 'chat.pulse.app',
        'apns-push-type': 'alert',
        'apns-priority': '10',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        aps: {
          alert: { title: payload.title, body: payload.body },
          sound: 'default',
          ...(payload.kind === 'message' ? {} : { 'interruption-level': 'time-sensitive' }),
        },
        kind: payload.kind,
        conversationId: payload.conversationId,
        ...(payload.messageId ? { messageId: payload.messageId } : {}),
        ...(payload.callKind ? { callKind: payload.callKind } : {}),
        ...(payload.callerName ? { callerName: payload.callerName } : {}),
      }),
      signal: AbortSignal.timeout(8000),
    })
    const prune = res.status === 404 || res.status === 410 || res.status === 403
    return { ok: res.ok, pruneToken: prune }
  } catch {
    return { ok: false, pruneToken: false }
  }
}

// ── fanout ───────────────────────────────────────────────────────────────────

export interface FanoutReport {
  users: number
  devices: number
  sent: number
  pruned: number
  skipped: number
}

export interface FanoutOptions {
  /**
   * Per-recipient body override (privacy gates like notifPreviews are a
   * property of the RECEIVER, so the body must be built per user).
   */
  bodyFor?: (userId: string) => string
}

/**
 * Send `payload` to every registered device of `userIds`, skipping users
 * whose socket session is currently online. Fire-and-forget safe: never
 * throws, always returns an honest report. Call sites `void` it.
 */
export async function fanoutPush(
  userIds: string[],
  payload: PushPayload,
  options?: FanoutOptions,
): Promise<FanoutReport> {
  const report: FanoutReport = { users: 0, devices: 0, sent: 0, pruned: 0, skipped: 0 }
  const ids = Array.from(new Set(userIds.filter((id) => typeof id === 'string' && id.trim())))
  if (ids.length === 0) return report
  try {
    const online = await onlineUserIds()
    const targets = ids.filter((id) => !online.has(id))
    report.users = targets.length
    if (targets.length === 0) return report
    const tokens = await db.pushToken.findMany({ where: { userId: { in: targets } } })
    report.devices = tokens.length
    for (const row of tokens) {
      const perUser: PushPayload = options?.bodyFor
        ? { ...payload, body: options.bodyFor(row.userId) }
        : payload
      const result =
        row.platform === 'web'
          ? await sendWeb(row.token, perUser)
          : row.platform === 'android'
            ? await sendAndroid(row.token, perUser)
            : row.platform === 'ios'
              ? await sendIos(row.token, perUser)
              : { ok: false, pruneToken: true, skipped: true }
      if (result.skipped) report.skipped += 1
      else if (result.ok) report.sent += 1
      if (result.pruneToken) {
        report.pruned += 1
        try {
          await db.pushToken.delete({ where: { id: row.id } })
        } catch {
          // already gone
        }
      }
    }
    if (report.devices > 0) {
      console.log(
        `[push] fanout kind=${payload.kind} users=${report.users} devices=${report.devices} sent=${report.sent} skipped=${report.skipped} pruned=${report.pruned}`,
      )
    }
    return report
  } catch (err) {
    console.warn('[push] fanout failed (never fatal):', err instanceof Error ? err.message : err)
    return report
  }
}

/** Public key served to web clients for PushManager.subscribe({ userVisibleOnly, applicationServerKey }). */
export function vapidPublicKey(): string {
  return process.env.VAPID_PUBLIC_KEY ?? ''
}

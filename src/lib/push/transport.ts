// 
// Pulse - remote push transport (server-side only).
//
// One registry (`PushToken` rows) feeds THREE delivery channels:
//   • web     → Web Push (VAPID). Fully live: keys generated locally, the
//               browser service worker (public/sw-push.js) displays the
//               notification even with every Pulse tab closed.
//   • android → FCM HTTP v1. Code path complete; delivery arms the moment a
//               Firebase service account is configured (env below) - without
//               credentials the sender FAILS CLOSED (skips, honest log), it
//               never pretends to send.
//   • ios     → APNs token-based (JWT/ES256). Same fail-closed contract;
//               arms when the Apple p8 key + ids are configured.
//
// Fanout policy: opted-in native message/read-state delivery is per device;
// an online desktop cannot suppress a phone. Legacy/web alerts and calls
// retain online-user suppression. Dead web endpoints (404/410) and
// unregistered mobile tokens prune their registry row so the registry
// self-heals. Push must NEVER fail a user-facing request - every call site
// is fire-and-forget.
// 
import 'server-only'
import crypto from 'node:crypto'
import { postApns, apnsOrigin } from './apns-http2.ts'
import { pushDeliveryWindow, shouldRetirePushToken, apnsInvalidatedAt } from './delivery-policy.ts'
import { db } from '@/lib/db'

export type PushPlatform = 'web' | 'android' | 'ios'

export interface PushPayload {
  kind: 'message' | 'gcall' | 'call' | 'read-state'
  /** Internal registry capability, overwritten by fanout (never caller authority). */
  nativeReadStateHints?: boolean
  /** Notification title - sender display name (or "Group call"). */
  title: string
  /** Notification body - preview text, or a generic line when previews are off. */
  body: string
  conversationId: string
  messageId?: string
  /** Assigned from the registration binding, never trusted from a caller. */
  recipientUserId?: string
  /** Absolute source/root deadline; never extend it during device fanout. */
  expiresAt?: string
  callId?: string
  callKind?: 'voice' | 'video'
  callerName?: string
}

const SOCKET_URL = 'http://localhost:3003'

// Web Push (VAPID) - live in this deployment 

let webPushLib: typeof import('web-push') | null = null
let webPushConfigured = false

async function webPushSender(): Promise<typeof import('web-push') | null> {
  if (webPushLib) return webPushConfigured ? webPushLib : null
  try {
    const publicKey = process.env.VAPID_PUBLIC_KEY ?? ''
    const privateKey = process.env.VAPID_PRIVATE_KEY ?? ''
    if (!publicKey || !privateKey) {
      console.warn('[push] web: VAPID keys missing - web push disabled (fail-closed)')
      webPushConfigured = false
      return null
    }
    // Bundler interop: web-push is CJS - `.default` may be undefined under
    // webpack/turbopack; fall back to the namespace itself.
    const mod = await import('web-push')
    const lib = mod.default ?? mod
    lib.setVapidDetails(
      process.env.VAPID_SUBJECT || 'mailto:push@pulse.chat',
      publicKey,
      privateKey,
    )
    webPushLib = lib
    webPushConfigured = true
    return lib
  } catch (err) {
    console.warn(
      '[push] web: sender init failed (fail-closed):',
      err instanceof Error ? err.message : err,
    )
    return null
  }
}

// FCM HTTP v1 (service-account OAuth2, cached) 

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

// APNs token-based provider token (ES256, cached) 

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

// online suppression 

async function onlineUserIds(): Promise<Set<string>> {
  const secret = process.env.CRON_SECRET
  if (!secret) return new Set()
  try {
    const res = await fetch(`${SOCKET_URL}/online`, { headers: { 'x-pulse-key': secret }, signal: AbortSignal.timeout(1500) })
    if (!res.ok) return new Set()
    const body = (await res.json()) as { onlineUserIds?: unknown }
    if (!Array.isArray(body.onlineUserIds)) return new Set()
    return new Set(body.onlineUserIds.filter((id): id is string => typeof id === 'string'))
  } catch {
    return new Set()
  }
}

// per-platform senders 

type SendResult = { ok: boolean; pruneToken: boolean; skipped?: boolean; retryable?: boolean; invalidatedAt?: number }

async function sendWeb(token: string, payload: PushPayload): Promise<SendResult> {
  const lib = await webPushSender()
  if (!lib) return { ok: false, pruneToken: false, skipped: true, retryable: true }
  // Registry stores the FULL PushSubscription JSON (endpoint + keys.p256dh/auth).
  // Payload encryption needs the keys; a keys-less row still gets a payload-less
  // notification (the service worker renders a default message - never silent).
  let subscription: { endpoint?: string; keys?: Record<string, string> } | null = null
  try {
    const parsed = JSON.parse(token) as { endpoint?: string; keys?: Record<string, string> }
    if (parsed && typeof parsed.endpoint === 'string') subscription = parsed
  } catch {
    subscription = null
  }
  if (!subscription) subscription = { endpoint: token }
  if (!subscription.endpoint) return { ok: false, pruneToken: true }
  const window = pushDeliveryWindow(payload)
  if (!window) return { ok: false, pruneToken: false, skipped: true }
  const hasKeys = typeof subscription.keys?.p256dh === 'string' && subscription.keys.p256dh.length > 0 &&
    typeof subscription.keys?.auth === 'string' && subscription.keys.auth.length > 0
  const body = hasKeys
    ? JSON.stringify({
        kind: payload.kind,
        title: payload.title,
        body: payload.body,
        conversationId: payload.conversationId,
        messageId: payload.messageId ?? null,
        ...(payload.recipientUserId ? { recipientUserId: payload.recipientUserId } : {}),
        ...(payload.expiresAt ? { expiresAt: payload.expiresAt } : {}),
        ...(payload.callId ? { callId: payload.callId } : {}),
        callKind: payload.callKind ?? null,
        callerName: payload.callerName ?? null,
      })
    : undefined
  try {
    // web-push does not require encryption keys for a payload-free legacy ping.
    // Its declarations require the keys object even in that mode.
    await lib.sendNotification({ endpoint: subscription.endpoint, keys: {
      p256dh: hasKeys ? subscription.keys!.p256dh : '',
      auth: hasKeys ? subscription.keys!.auth : '',
    } }, body, {
      TTL: window.ttlSeconds,
      urgency: payload.kind === 'message' ? 'normal' : 'high',
    })
    return { ok: true, pruneToken: false }
  } catch (err) {
    const statusCode = (err as { statusCode?: number }).statusCode
    const prune = statusCode === 404 || statusCode === 410
    console.warn(
      `[push] web: delivery failed status=${statusCode ?? 'none'} prune=${prune}:`,
      err instanceof Error ? err.message.slice(0, 120) : err,
    )
    return { ok: false, pruneToken: prune }
  }
}

async function sendAndroid(token: string, payload: PushPayload): Promise<SendResult> {
  const bearer = await fcmBearer()
  if (!bearer) return { ok: false, pruneToken: false, skipped: true, retryable: true }
  const projectId = fcmCredentials()?.projectId ?? ''
  const window = pushDeliveryWindow(payload)
  if (!window) return { ok: false, pruneToken: false, skipped: true }
  try {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          token,
          // Call data must reach the native deadline/owner guard even when
          // backgrounded; an OS-rendered mixed notification bypasses it.
          ...(payload.kind === 'message' && !payload.nativeReadStateHints ? { notification: { title: payload.title, body: payload.body } } : {}),
          data: {
            kind: payload.kind,
            ...(payload.kind !== 'message' || payload.nativeReadStateHints ? { title: payload.title, body: payload.body } : {}),
            conversationId: payload.conversationId,
            ...(payload.messageId ? { messageId: payload.messageId } : {}),
            ...(payload.recipientUserId ? { recipientUserId: payload.recipientUserId } : {}),
            ...(payload.expiresAt ? { expiresAt: payload.expiresAt } : {}),
            ...(payload.callId ? { callId: payload.callId } : {}),
            ...(payload.callKind ? { callKind: payload.callKind } : {}),
            ...(payload.callerName ? { callerName: payload.callerName } : {}),
          },
          android: {
            ttl: `${window.ttlSeconds}s`,
            ...(payload.kind === 'message' && !payload.nativeReadStateHints && payload.recipientUserId && payload.messageId ? {
              notification: { tag: 'pulse-msg-v1/' + [payload.recipientUserId, payload.conversationId, payload.messageId]
                .map(encodeURIComponent).join('/') },
            } : {}),
            priority: payload.kind === 'read-state' || (payload.kind === 'message' && !payload.nativeReadStateHints) ? 'normal' : 'high',
            ...(payload.kind === 'read-state' ? { collapse_key: 'pulse-read-state' } : {}),
          },
        },
      }),
      signal: AbortSignal.timeout(8000),
    })
    const error: unknown = res.ok ? null : await res.json().catch(() => null)
    return { ok: res.ok, pruneToken: shouldRetirePushToken('android', res.status, error) }
  } catch {
    return { ok: false, pruneToken: false }
  }
}

async function sendIos(token: string, payload: PushPayload): Promise<SendResult> {
  const bearer = await apnsBearer()
  if (!bearer) return { ok: false, pruneToken: false, skipped: true, retryable: true }
  const window = pushDeliveryWindow(payload)
  if (!window) return { ok: false, pruneToken: false, skipped: true }
  try {
    const origin = apnsOrigin(process.env.PULSE_APNS_ENVIRONMENT)
    if (!origin) return { ok: false, pruneToken: false, skipped: true }
    const res = await postApns(origin, token, {
      authorization: `bearer ${bearer}`,
      'apns-topic': process.env.PULSE_APNS_BUNDLE_ID || 'app.pulse.chat',
      'apns-push-type': payload.kind === 'read-state' ? 'background' : 'alert',
      'apns-priority': payload.kind === 'read-state' ? '5' : '10',
      ...(payload.kind === 'read-state' ? { 'apns-collapse-id': 'pulse-read-state' } : {}),
      'apns-expiration': String(window.expiresAtSeconds),
      'content-type': 'application/json',
    }, {
        aps: payload.kind === 'read-state' ? { 'content-available': 1 } : {
          ...(payload.kind === 'message' ? { 'content-available': 1 } : {}),
          alert: { title: payload.title, body: payload.body },
          sound: 'default',
          // R10 - remote message pushes render the OS quick-reply action: the
          // iOS client registers the PULSE_MSG category (PulseQuickReply.
          // registerCategory, UNTextInputNotificationAction) and the system
          // attaches its Reply control to banners carrying this aps.category.
          // Message pushes always carry conversationId; calls/reminders stay
          // action-less (a Reply on a call banner would be a dead control).
          ...(payload.kind === 'message' && payload.conversationId ? { category: 'PULSE_MSG' } : {}),
          ...(payload.kind === 'message' ? {} : { 'interruption-level': 'time-sensitive' }),
        },
        kind: payload.kind,
        conversationId: payload.conversationId,
        ...(payload.messageId ? { messageId: payload.messageId } : {}),
            ...(payload.recipientUserId ? { recipientUserId: payload.recipientUserId } : {}),
        ...(payload.expiresAt ? { expiresAt: payload.expiresAt } : {}),
        ...(payload.callId ? { callId: payload.callId } : {}),
        ...(payload.callKind ? { callKind: payload.callKind } : {}),
        ...(payload.callerName ? { callerName: payload.callerName } : {}),
      })
    return { ok: res.status === 200, pruneToken: shouldRetirePushToken('ios', res.status, res.body), invalidatedAt: apnsInvalidatedAt(res.body) }
  } catch {
    return { ok: false, pruneToken: false }
  }
}

// fanout 

export interface FanoutReport {
  users: number
  devices: number
  sent: number
  pruned: number
  skipped: number
  failed?: number
  nextCursor?: string | null
}

export interface FanoutOptions {
  afterTokenId?: string | null
  limit?: number
  alreadyDelivered?: (bindingHash: string) => Promise<boolean>
  recordDelivered?: (bindingHash: string) => Promise<void>
  /**
   * Per-recipient body override (privacy gates like notifPreviews are a
   * property of the RECEIVER, so the body must be built per user).
   */
  bodyFor?: (userId: string) => string
}

/**
 * Deliver per-device native message/read-state invalidations, retaining
 * online-user suppression for legacy/web alerts and call fanout. Fire-and-forget safe: never
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
    // Read invalidations must reach another device even while this user is
    // reading on an online device. They carry no count or read authority.
    const online = payload.kind === 'read-state' ? new Set<string>() : await onlineUserIds()
    const targets = payload.kind === 'message' ? ids : ids.filter((id) => !online.has(id))
    report.users = targets.length
    if (targets.length === 0) return report
    const capability = payload.kind === 'read-state' ? { readStateCapability: { is: { version: 1 } } } : {}
    const limit = options?.limit === undefined ? undefined : Math.max(1,Math.min(100,options.limit))
    const candidates = await db.pushToken.findMany({ where: { userId: { in: targets }, ...capability,
      ...(options?.afterTokenId ? { id: { gt: options.afterTokenId } } : {}) },
      ...(limit === undefined ? {} : { orderBy: { id: 'asc' as const }, take: limit + 1 }) })
    const tokens = limit === undefined ? candidates : candidates.slice(0,limit)
    if (limit !== undefined) report.nextCursor = candidates.length > limit ? tokens.at(-1)!.id : null
    report.devices = tokens.length
    for (const row of tokens) {
      // Web Push requires a visible notification; never turn a read hint into a banner.
      if (payload.kind === 'read-state' && row.platform === 'web') { report.skipped += 1; continue }
      if (!pushDeliveryWindow(payload)) { report.skipped += 1; continue }
      // A queued provider call must not use a registration rebound while earlier
      // devices were being processed. This is a preflight, not provider recall.
      const nativeReadStateHints = (row.platform === 'android' || row.platform === 'ios') &&
        !!await db.pushReadStateCapability.findFirst({ where: { tokenId: row.id, version: 1 }, select: { tokenId: true } })
      // User presence is not device presence. A desktop socket cannot suppress
      // an opted-in phone; the native foreground receiver avoids duplicate alerts.
      if (online.has(row.userId) && !nativeReadStateHints) { report.skipped += 1; continue }
      const expectedCapability = nativeReadStateHints ? { readStateCapability: { is: { version: 1 } } } : capability
      const binding = { id: row.id, userId: row.userId, platform: row.platform, token: row.token, updatedAt: row.updatedAt }
      if (!await db.pushToken.findFirst({ where: { ...binding, ...expectedCapability }, select: { id: true } })) { report.skipped += 1; continue }
      const bindingHash = crypto.createHash('sha256').update(JSON.stringify([row.id,row.userId,row.platform,row.token,row.updatedAt.toISOString()])).digest('hex')
      if (await options?.alreadyDelivered?.(bindingHash)) { report.skipped += 1; continue }
      const perUser: PushPayload = {
        ...payload,
        recipientUserId: row.userId,
        nativeReadStateHints,
        ...(options?.bodyFor ? { body: options.bodyFor(row.userId) } : {}),
      }
      const result: SendResult =
        row.platform === 'web'
          ? await sendWeb(row.token, perUser)
          : row.platform === 'android'
            ? await sendAndroid(row.token, perUser)
            : row.platform === 'ios'
              ? await sendIos(row.token, perUser)
              : { ok: false, pruneToken: false, skipped: true }
      if (result.retryable || (!result.ok && !result.pruneToken && !result.skipped)) report.failed = (report.failed ?? 0) + 1
      if (result.ok || result.pruneToken || (result.skipped && !result.retryable)) await options?.recordDelivered?.(bindingHash)
      if (result.skipped) report.skipped += 1
      else if (result.ok) report.sent += 1
      if (result.pruneToken && (result.invalidatedAt === undefined || result.invalidatedAt > row.updatedAt.getTime())) {
        try {
          const removed = await db.pushToken.deleteMany({ where: binding })
          report.pruned += removed.count
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
    report.failed = (report.failed ?? 0) + 1
    console.warn('[push] fanout failed (never fatal):', err instanceof Error ? err.message : err)
    return report
  }
}

/** Public key served to web clients for PushManager.subscribe({ userVisibleOnly, applicationServerKey }). */
export function vapidPublicKey(): string {
  return process.env.VAPID_PUBLIC_KEY ?? ''
}

/** A best-effort wake-up, never read authority. Foreground/reconnect fetches
 * remain necessary because providers/OSes may collapse or suppress delivery. */
export function invalidateNativeReadState(userIds: string | string[], conversationId: string): void {
  const recipients = typeof userIds === 'string' ? [userIds] : [...new Set(userIds)]
  if (recipients.length === 0) return
  void fanoutPush(recipients, { kind: 'read-state', title: '', body: '', conversationId })
}
